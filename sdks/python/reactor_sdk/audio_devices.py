"""Speakers and microphones — the audio hardware the SDK deliberately never touches.

The SDK pins the synthetic audio device module, so nothing here opens a capture or
playout device on its own: a model declaring a sendonly audio track is not enough
to put your microphone on the wire, and a model sending audio does not make your
speakers play. That is the right default for scripts, servers and pipelines, and
it leaves both jobs to the application.

These are those jobs, done::

    output = reactor.tracks.with_direction("recvonly").with_kind("audio").one()
    mic = await reactor.track("mic").publish()

    with Speaker(output), Microphone(mic):
        await asyncio.sleep(30)

This module is deliberately not re-exported from `reactor_sdk`: it is the one part
of the SDK with a dependency, so importing the package never reaches it and only
an application that wants a device pays for PortAudio —
``pip install "reactor-sdk[audio]"``, then
``from reactor_sdk.audio_devices import Microphone, Speaker``.

A missing `sounddevice` raises rather than going quiet: a caller who constructed a
`Speaker` asked for sound, and silence with a log line is the failure mode this SDK
spends its effort removing.
"""

from __future__ import annotations

import logging
import threading
from types import TracebackType
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:  # pragma: no cover - types only
    from .track import Track

_log = logging.getLogger(__name__)

#: int16, which is the only sample format on this wire.
BYTES_PER_SAMPLE = 2

#: How much audio may wait to be played, in milliseconds. Enough to absorb a
#: scheduling hiccup, short enough that a persistently slow consumer does not build
#: up latency you hear as delay.
MAX_BUFFER_MS = 300

#: How much to collect before playback starts. The device asks for audio the moment
#: the stream opens, which is before any has arrived, and every such request is
#: silence heard as a click. Waiting for a little first trades those for a delay
#: nobody notices.
#:
#: The same amount is re-collected after an under-run. Continuing at the edge of an
#: empty buffer turns ordinary jitter into a click on nearly every block, so one
#: short silence is preferable to a stream of them — which is what a jitter buffer
#: is for.
PREROLL_MS = 60

#: What a capture block holds, in milliseconds. The FFI expects roughly this much
#: per push, and it is the granularity the far end's jitter buffer is tuned for.
CAPTURE_BLOCK_MS = 10


def _sounddevice() -> Any:
    """Import `sounddevice`, or explain what is missing and how to get it."""
    try:
        import sounddevice
    except ModuleNotFoundError as exc:  # pragma: no cover - depends on the environment
        raise ModuleNotFoundError(
            "audio devices need sounddevice, which is not a dependency of this "
            'package. Install it with `pip install "reactor-sdk[audio]"`. The SDK '
            "itself opens no audio device — see reactor_sdk.audio_devices."
        ) from exc
    return sounddevice


class Speaker:
    """Plays a recvonly audio track through an output device.

    Given a track, it registers itself and plays what arrives::

        with Speaker(reactor.track("speech")):
            await asyncio.sleep(30)

    Without one, it is a sink you feed yourself — from a track's raw handler, or
    from anywhere else the PCM comes from::

        speaker = Speaker().start()

        @reactor.track("speech").on_raw_frame
        def play(pcm, _samples, rate, channels):
            speaker.submit(pcm, rate, channels)

    The device is opened on the first frame, not on `start`, because the first
    frame is what says at which rate and channel count to open it. The rest is a
    jitter buffer: see `MAX_BUFFER_MS` and `PREROLL_MS` for the two numbers that
    decide what it trades away when the device and the stream disagree.
    """

    def __init__(self, track: Track | None = None, *, device: int | str | None = None) -> None:
        """
        Args:
            track: A recvonly audio track to play. Attached on `start`; omit to
                feed the speaker yourself with `submit`.
            device: Which output device, in `sounddevice`'s terms — an index or a
                name substring. None uses the system default.
        """
        self._track = track
        self._device = device

        # The buffer is a byte FIFO rather than a queue of chunks, and that is not
        # incidental. PCM is a continuous stream that the device consumes in blocks
        # of its own choosing, which do not line up with the ~10 ms chunks the SDK
        # delivers. A queue of chunks forces you to deal with the leftover when a
        # chunk straddles a block boundary, and putting the leftover back on a FIFO
        # puts it *behind* audio that came after it — reordering the stream a little
        # on every block, which is heard as continuous crackle. With a byte FIFO
        # there is no leftover to misplace: the device takes exactly the bytes it
        # asked for, and the next block continues where it stopped.
        self._lock = threading.Lock()
        self._pcm = bytearray()
        self._stream: Any | None = None

        # Guards the lifecycle — `_closed`, `_stream`, and the open sequence — and is
        # deliberately not `_lock`. Without it, a frame arriving as the speaker is
        # being stopped can pass the `_closed` check, `stop` can then find no stream
        # to close, and the frame can go on to open one that nothing ever closes:
        # playback outliving the teardown that was supposed to end it.
        #
        # Separate from `_lock` because `_fill` takes that one on PortAudio's thread
        # while `_open` calls into PortAudio holding this one. Sharing them would let
        # a callback primed by `stream.start()` wait on the thread starting it.
        self._lifecycle = threading.Lock()

        # Set from the first frame rather than assumed: it reports the rate and the
        # channel count, and guessing wrong renders noise rather than failing.
        self._format: tuple[int, int] | None = None
        self._frame_bytes = 0
        self._max_bytes = 0
        self._preroll_bytes = 0

        self._playing = False
        self._dropped_bytes = 0
        self._starved = 0
        self._closed = False

    # ------------------------------------------------------------------
    # Lifecycle
    # ------------------------------------------------------------------

    def start(self) -> Speaker:
        """Begin playing, and attach to the track if one was given.

        Returns the speaker, so it reads in one line. Raises if `sounddevice` is
        missing — checked here rather than at the first frame, so a missing
        dependency surfaces where the caller can see it instead of on a media
        thread.
        """
        _sounddevice()
        if self._track is not None:
            self._track.on_raw_frame(self._on_raw_frame)
        return self

    def stop(self) -> None:
        """Close the device and stop playing. Safe to call twice."""
        # Under the lock so a frame in flight cannot be mid-open: it either sees
        # `_closed` and returns, or finishes opening and hands the stream over here.
        with self._lifecycle:
            self._closed = True
            stream, self._stream = self._stream, None

        if self._track is not None:
            self._track.off_frame(self._on_raw_frame)

        # Closed outside the lock: closing dispatches onto PortAudio's threads and
        # waits for them, and holding a lock across that is the shape that deadlocks.
        if stream is not None:
            try:
                stream.stop()
                stream.close()
            except Exception:  # pragma: no cover - teardown
                _log.debug("error closing the audio output", exc_info=True)

        if self._dropped_bytes or self._starved:
            _log.info(
                "speaker: %d ms dropped (device behind), %d under-run(s)",
                self.dropped_ms,
                self._starved,
            )

    def __enter__(self) -> Speaker:
        return self.start()

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        self.stop()

    # ------------------------------------------------------------------
    # State worth reporting
    # ------------------------------------------------------------------

    @property
    def dropped_ms(self) -> int:
        """How much audio was discarded because the device fell behind."""
        if self._format is None or not self._frame_bytes:
            return 0
        return self._dropped_bytes * 1000 // (self._format[0] * self._frame_bytes)

    @property
    def under_runs(self) -> int:
        """How often the buffer emptied and a silence had to be played."""
        return self._starved

    # ------------------------------------------------------------------
    # Feeding
    # ------------------------------------------------------------------

    def _on_raw_frame(self, pcm: bytes, _samples: int, sample_rate: int, channels: int) -> None:
        self.submit(pcm, sample_rate, channels)

    def submit(self, pcm: bytes, sample_rate: int, channels: int) -> None:
        """Queue PCM for playback, opening the device on the first frame.

        Called on the SDK's audio delivery thread when attached to a track. Never
        blocks for long: the alternative is holding up delivery, which costs audio
        before it reaches here.
        """
        with self._lifecycle:
            if self._closed:
                return

            if self._format is None:
                self._open(sample_rate, channels)
                if self._stream is None:
                    return
            elif self._format != (sample_rate, channels):
                # Renegotiation could in principle change this. Reopening mid-stream
                # would drop whatever is buffered; saying so beats playing it at the
                # wrong rate, which is heard as the wrong pitch.
                _log.warning(
                    "speaker: audio format changed from %s to %s; ignoring the new stream",
                    self._format,
                    (sample_rate, channels),
                )
                return

        with self._lock:
            self._pcm += pcm
            excess = len(self._pcm) - self._max_bytes
            if excess > 0:
                # The device is behind. Drop the oldest, so what plays next is the
                # most recent audio rather than a growing backlog of stale audio.
                del self._pcm[:excess]
                self._dropped_bytes += excess

    # ------------------------------------------------------------------
    # Device
    # ------------------------------------------------------------------

    def _open(self, sample_rate: int, channels: int) -> None:
        sd = _sounddevice()
        self._format = (sample_rate, channels)
        self._frame_bytes = channels * BYTES_PER_SAMPLE
        self._max_bytes = int(sample_rate * MAX_BUFFER_MS / 1000) * self._frame_bytes
        self._preroll_bytes = int(sample_rate * PREROLL_MS / 1000) * self._frame_bytes

        try:
            self._stream = sd.RawOutputStream(
                samplerate=sample_rate,
                channels=channels,
                dtype="int16",
                device=self._device,
                callback=self._fill,
            )
            self._stream.start()
            _log.info("speaker open: %d Hz, %d channel(s)", sample_rate, channels)
        except Exception:
            # No output device, a rate it will not take, an exclusive-mode
            # conflict. The stream is what failed, so the stream is what reports
            # it; the caller's session is not the SDK's to end over this.
            _log.warning("could not open an audio output device", exc_info=True)
            self._stream = None
            self._closed = True

    def _fill(self, outdata: Any, frames: int, _time: Any, status: Any) -> None:
        """Hand the device its next block. Runs on sounddevice's thread, on a deadline."""
        if status:
            _log.debug("speaker status: %s", status)

        wanted = frames * self._frame_bytes

        with self._lock:
            if not self._playing:
                if len(self._pcm) < self._preroll_bytes:
                    outdata[:] = bytes(wanted)
                    return
                self._playing = True

            if len(self._pcm) < wanted:
                self._starved += 1
                self._playing = False
                block = bytes(self._pcm) + bytes(wanted - len(self._pcm))
                self._pcm.clear()
            else:
                block = bytes(self._pcm[:wanted])
                del self._pcm[:wanted]

        outdata[:] = block


class Microphone:
    """Captures from an input device into a sendonly audio track.

        mic = await reactor.track("mic").publish()
        with Microphone(mic):
            await asyncio.sleep(30)

    Capture runs on `sounddevice`'s own thread and pushes straight through, so
    nothing here waits on the event loop and the loop never waits on the device.

    One thing to know about pushing: the SDK feeds every local audio track from
    one shared synthetic device, so two `Microphone`s running at once do not give
    two independent streams — they interleave into the same one. Capture from one
    device at a time.
    """

    def __init__(
        self,
        track: Track,
        *,
        sample_rate: int = 48_000,
        channels: int = 1,
        device: int | str | None = None,
    ) -> None:
        """
        Args:
            track: The sendonly audio track to push into. Publish it first —
                pushing into a slot that was never activated goes nowhere.
            sample_rate: Capture rate in Hz. 48 kHz is what the wire uses; another
                rate is resampled by the far end, or refused by the device.
            channels: 1 for mono, which is what a microphone usually is.
            device: Which input device, in `sounddevice`'s terms — an index or a
                name substring. None uses the system default.
        """
        self._track = track
        self._sample_rate = sample_rate
        self._channels = channels
        self._device = device
        self._stream: Any | None = None
        self._blocks = 0

    def start(self) -> Microphone:
        """Open the input device and begin pushing. Returns the microphone."""
        sd = _sounddevice()
        # A block per push, sized to what the far end expects. Left to the device,
        # the block size is whatever it prefers — often much larger, which arrives
        # as bursts the jitter buffer then has to smooth out.
        blocksize = int(self._sample_rate * CAPTURE_BLOCK_MS / 1000)
        self._stream = sd.RawInputStream(
            samplerate=self._sample_rate,
            channels=self._channels,
            dtype="int16",
            blocksize=blocksize,
            device=self._device,
            callback=self._captured,
        )
        self._stream.start()
        _log.info(
            "microphone open: %d Hz, %d channel(s) → track %r",
            self._sample_rate,
            self._channels,
            self._track.name,
        )
        return self

    def stop(self) -> None:
        """Close the input device. Safe to call twice."""
        stream, self._stream = self._stream, None
        if stream is not None:
            try:
                stream.stop()
                stream.close()
            except Exception:  # pragma: no cover - teardown
                _log.debug("error closing the audio input", exc_info=True)
            _log.info("microphone closed after %d block(s)", self._blocks)

    def __enter__(self) -> Microphone:
        return self.start()

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        tb: TracebackType | None,
    ) -> None:
        self.stop()

    @property
    def blocks_sent(self) -> int:
        """How many capture blocks have been pushed."""
        return self._blocks

    def _captured(self, indata: Any, _frames: int, _time: Any, status: Any) -> None:
        """Push one captured block. Runs on sounddevice's thread, on a deadline."""
        if status:
            _log.debug("microphone status: %s", status)
        self._track.push_frame(
            bytes(indata),
            sample_rate=self._sample_rate,
            num_channels=self._channels,
        )
        self._blocks += 1
