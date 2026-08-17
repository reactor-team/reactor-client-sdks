"""Speaker playback for the audio a Reactor session delivers.

The SDK never opens an audio device itself — a model declaring a `sendonly`
audio track used to be enough to put live microphone audio on the wire, and
that capability is gone on purpose (see the `Reactor` constructor's docs).
That decision cuts both ways, though: nothing plays the decoded audio arriving
from the far end either. Playing it back is the caller's job, and `AudioPlayer`
is that job done once, instead of once per application::

    output = reactor.track("speech")
    AudioPlayer().attach(output)

`sounddevice` is an optional dependency (``pip install reactor-sdk[audio]``,
or just ``pip install sounddevice``) — imported lazily, on the first frame,
so importing this module costs nothing if you never use it. Without it
installed, `AudioPlayer` logs once and plays nothing rather than raising:
the audio is usually a nice-to-have next to whatever the caller actually
came for (video, a command loop), and a missing optional dependency
shouldn't take that down too.

The buffer is a byte FIFO rather than a queue of chunks, and that is not
incidental. PCM is a continuous stream that the device consumes in blocks of
its own choosing, which do not line up with the ~10 ms chunks the SDK
delivers. A queue of chunks forces you to deal with the leftover when a chunk
straddles a block boundary, and putting the leftover back on a queue puts it
*behind* audio that comes after it — reordering the stream a little on every
block, which is heard as continuous crackle. With a byte FIFO there is no
leftover to misplace: the device takes exactly the bytes it asked for and the
next block continues where it stopped.

Two threads meet here and neither may wait for the other for long. The SDK
delivers on its own audio delivery thread, where blocking costs audio
upstream; sounddevice pulls on a callback thread with a deadline. The lock is
held only long enough to copy bytes.
"""

from __future__ import annotations

import logging
import threading
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:  # pragma: no cover - types only
    from .track import Track

_log = logging.getLogger(__name__)

_BYTES_PER_SAMPLE = 2  # int16

#: How much audio may wait to be played, in milliseconds. Enough to absorb a
#: scheduling hiccup, short enough that a persistently slow consumer does not
#: build up latency you hear as delay.
DEFAULT_MAX_BUFFER_MS = 300

#: How much to collect before playback starts. The device asks for audio the
#: moment the stream opens, which is before any has arrived, and every such
#: request is silence heard as a click. Waiting for a little first trades
#: those for a delay nobody notices.
#:
#: The same amount is re-collected after an under-run. Continuing at the edge
#: of an empty buffer turns ordinary jitter into a click on nearly every
#: block, so one short silence is preferable to a stream of them — which is
#: what a jitter buffer is for.
DEFAULT_PREROLL_MS = 60


class AudioPlayer:
    """Plays PCM handed to it by `submit()`, or does nothing if it cannot.

    Args:
        max_buffer_ms: How much unplayed audio to keep before dropping the
            oldest. See `DEFAULT_MAX_BUFFER_MS`.
        preroll_ms: How much to collect before starting playback, and again
            after an under-run. See `DEFAULT_PREROLL_MS`.
    """

    def __init__(
        self,
        *,
        max_buffer_ms: int = DEFAULT_MAX_BUFFER_MS,
        preroll_ms: int = DEFAULT_PREROLL_MS,
    ) -> None:
        self._max_buffer_ms = max_buffer_ms
        self._preroll_ms = preroll_ms

        self._lock = threading.Lock()
        self._pcm = bytearray()
        self._stream: Any | None = None

        # Set from the first frame handed to submit() rather than assumed: it
        # reports the rate and channel count, and guessing wrong renders noise
        # rather than failing.
        self._format: tuple[int, int] | None = None
        self._frame_bytes = 0
        self._max_bytes = 0
        self._preroll_bytes = 0

        self._playing = False
        self._dropped_bytes = 0
        self._starved = 0
        self._unavailable = False

    # ------------------------------------------------------------------
    # Wiring
    # ------------------------------------------------------------------

    def attach(self, track: Track) -> Track:
        """Play `track`'s frames as they arrive. `track` must be `recvonly` audio.

        Registers on the track rather than the client, so a model with more
        than one recvonly audio track does not have them summed into one
        speaker with no way to tell which was which. Uses `on_raw_frame`
        because this wants the PCM and its format, not the NumPy array
        `on_frame` would decode — and decoding it would cost time on the SDK's
        audio delivery thread for a conversion nothing here needs.

        Returns `track`, so this composes with the line that looked it up::

            output = AudioPlayer().attach(reactor.track("speech"))
        """
        track.on_raw_frame(
            lambda pcm, _samples, sample_rate, channels: self.submit(pcm, sample_rate, channels)
        )
        return track

    # ------------------------------------------------------------------
    # Feeding
    # ------------------------------------------------------------------

    def submit(self, pcm: bytes, sample_rate: int, channels: int) -> None:
        """Queue PCM for playback, opening the device on the first call.

        Matches the shape `Track.on_raw_frame` and the raw `"audio"` event
        deliver (minus the sample count, redundant with `len(pcm)`), so it
        wires directly: ``track.on_raw_frame(lambda pcm, _n, rate, ch:
        player.submit(pcm, rate, ch))`` — which is exactly what `attach()`
        does.

        Never blocks for long: the alternative is holding up delivery, which
        costs audio before it reaches here.
        """
        if self._unavailable:
            return

        if self._format is None:
            self._open(sample_rate, channels)
            if self._stream is None:
                return
        elif self._format != (sample_rate, channels):
            # Renegotiation could in principle change this. Reopening
            # mid-stream is more than this class takes on; saying so beats
            # playing it at the wrong rate.
            _log.warning(
                "audio format changed from %s to %s; ignoring the new stream",
                self._format,
                (sample_rate, channels),
            )
            return

        with self._lock:
            self._pcm += pcm
            excess = len(self._pcm) - self._max_bytes
            if excess > 0:
                # The speaker is behind. Drop the oldest, so what plays next
                # is the most recent audio rather than a growing backlog of
                # stale audio.
                del self._pcm[:excess]
                self._dropped_bytes += excess

    # ------------------------------------------------------------------
    # Device
    # ------------------------------------------------------------------

    def _open(self, sample_rate: int, channels: int) -> None:
        try:
            import sounddevice as sd
        except ModuleNotFoundError:
            _log.warning(
                "sounddevice is not installed, so received audio will not be played. "
                "`pip install reactor-sdk[audio]` (or just `pip install sounddevice`) to hear it."
            )
            self._unavailable = True
            return

        self._format = (sample_rate, channels)
        self._frame_bytes = channels * _BYTES_PER_SAMPLE
        self._max_bytes = int(sample_rate * self._max_buffer_ms / 1000) * self._frame_bytes
        self._preroll_bytes = int(sample_rate * self._preroll_ms / 1000) * self._frame_bytes

        try:
            self._stream = sd.RawOutputStream(
                samplerate=sample_rate,
                channels=channels,
                dtype="int16",
                callback=self._fill,
            )
            self._stream.start()
            _log.info("audio output open: %d Hz, %d channel(s)", sample_rate, channels)
        except Exception:
            # No output device, a rate the device will not take, an
            # exclusive-mode conflict. None of it is worth raising for out of
            # a callback the SDK's delivery thread is waiting on.
            _log.warning("could not open an audio output device", exc_info=True)
            self._stream = None
            self._unavailable = True

    def _fill(self, outdata: Any, frames: int, _time: Any, status: Any) -> None:
        """Hand the device its next block. Runs on sounddevice's thread, on a deadline."""
        if status:
            _log.debug("audio output status: %s", status)

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

    def stop(self) -> None:
        """Close the output device, if one was opened."""
        stream, self._stream = self._stream, None
        if stream is not None:
            try:
                stream.stop()
                stream.close()
            except Exception:  # pragma: no cover - teardown
                _log.debug("error closing the audio output", exc_info=True)

        if self._dropped_bytes or self._starved:
            dropped_ms = 0
            if self._format is not None:
                dropped_ms = self._dropped_bytes * 1000 // (self._format[0] * self._frame_bytes)
            _log.info(
                "audio: %d ms dropped (speaker behind), %d under-run(s)",
                dropped_ms,
                self._starved,
            )
