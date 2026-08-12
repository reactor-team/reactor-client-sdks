"""Speaker playback for the audio the SDK delivers.

The SDK does not open an audio device unless asked. That keeps the microphone shut —
a model declaring a sendonly audio track used to be enough to put live microphone
audio on the wire — but it also means nothing plays the decoded audio arriving from
the far end. Playing it is the application's job now, and this is the application
doing it.

The buffer is a byte FIFO rather than a queue of chunks, and that is not incidental.
PCM is a continuous stream that the device consumes in blocks of its own choosing,
which do not line up with the ~10 ms chunks the SDK delivers. A queue of chunks forces
you to deal with the leftover when a chunk straddles a block boundary, and putting the
leftover back on a FIFO puts it *behind* audio that comes after it — reordering the
stream a little on every block, which is heard as continuous crackle. With a byte FIFO
there is no leftover to misplace: the device takes exactly the bytes it asked for and
the next block continues where it stopped.

Two threads meet here and neither may wait for the other for long. The SDK delivers on
its own delivery thread, where blocking costs audio upstream; sounddevice pulls on a
callback thread with a deadline. The lock is held only long enough to copy bytes.

`sounddevice` is optional. Without it the example runs silently rather than refusing
to start, since the video is the point of the app.
"""

from __future__ import annotations

import logging
import threading
from typing import Any

logger = logging.getLogger(__name__)

BYTES_PER_SAMPLE = 2  # int16

#: How much audio may wait to be played, in milliseconds. Enough to absorb a scheduling
#: hiccup, short enough that a persistently slow consumer does not build up latency you
#: hear as delay.
MAX_BUFFER_MS = 300

#: How much to collect before playback starts. The device asks for audio the moment the
#: stream opens, which is before any has arrived, and every such request is silence
#: heard as a click. Waiting for a little first trades those for a delay nobody notices.
#:
#: The same amount is re-collected after an under-run. Continuing at the edge of an
#: empty buffer turns ordinary jitter into a click on nearly every block, so one short
#: silence is preferable to a stream of them — which is what a jitter buffer is for.
PREROLL_MS = 60


class AudioPlayer:
    """Plays PCM handed to it by :meth:`submit`, or does nothing if it cannot."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._pcm = bytearray()
        self._stream: Any | None = None
        self._sd: Any | None = None

        # Set from the first frame the SDK delivers rather than assumed: it reports the
        # rate and channel count, and guessing wrong renders noise rather than failing.
        self._format: tuple[int, int] | None = None
        self._frame_bytes = 0
        self._max_bytes = 0
        self._preroll_bytes = 0

        self._playing = False
        self._dropped_bytes = 0
        self._starved = 0
        self._unavailable = False

    # ------------------------------------------------------------------
    # Feeding
    # ------------------------------------------------------------------

    def submit(self, pcm: bytes, sample_rate: int, channels: int) -> None:
        """Queue PCM for playback, opening the device on the first frame.

        Called on the SDK's audio delivery thread. Never blocks for long: the
        alternative is holding up delivery, which costs audio before it reaches here.
        """
        if self._unavailable:
            return

        if self._format is None:
            self._open(sample_rate, channels)
            if self._stream is None:
                return
        elif self._format != (sample_rate, channels):
            # Renegotiation could in principle change this. Reopening mid-stream is more
            # than an example needs; saying so beats playing it at the wrong rate.
            logger.warning(
                "audio format changed from %s to %s; ignoring the new stream",
                self._format,
                (sample_rate, channels),
            )
            return

        with self._lock:
            self._pcm += pcm
            excess = len(self._pcm) - self._max_bytes
            if excess > 0:
                # The speaker is behind. Drop the oldest, so what plays next is the most
                # recent audio rather than a growing backlog of stale audio.
                del self._pcm[:excess]
                self._dropped_bytes += excess

    # ------------------------------------------------------------------
    # Device
    # ------------------------------------------------------------------

    def _open(self, sample_rate: int, channels: int) -> None:
        try:
            import sounddevice as sd
        except ModuleNotFoundError:
            logger.warning(
                "sounddevice is not installed, so received audio will not be played. "
                "`pip install sounddevice` to hear it."
            )
            self._unavailable = True
            return

        self._sd = sd
        self._format = (sample_rate, channels)
        self._frame_bytes = channels * BYTES_PER_SAMPLE
        self._max_bytes = int(sample_rate * MAX_BUFFER_MS / 1000) * self._frame_bytes
        self._preroll_bytes = int(sample_rate * PREROLL_MS / 1000) * self._frame_bytes

        try:
            self._stream = sd.RawOutputStream(
                samplerate=sample_rate,
                channels=channels,
                dtype="int16",
                callback=self._fill,
            )
            self._stream.start()
            logger.info("audio output open: %d Hz, %d channel(s)", sample_rate, channels)
        except Exception:
            # No output device, a rate the device will not take, an exclusive-mode
            # conflict. None of it is worth taking the app down for.
            logger.warning("could not open an audio output device", exc_info=True)
            self._stream = None
            self._unavailable = True

    def _fill(self, outdata: Any, frames: int, _time: Any, status: Any) -> None:
        """Hand the device its next block. Runs on sounddevice's thread, on a deadline."""
        if status:
            logger.debug("audio output status: %s", status)

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

    def start(self) -> None:
        """Present for symmetry with :meth:`stop`. The device opens on the first frame,
        because that is when its format is known."""

    def stop(self) -> None:
        stream, self._stream = self._stream, None
        if stream is not None:
            try:
                stream.stop()
                stream.close()
            except Exception:  # pragma: no cover - teardown
                logger.debug("error closing the audio output", exc_info=True)

        if self._dropped_bytes or self._starved:
            dropped_ms = 0
            if self._format is not None:
                dropped_ms = self._dropped_bytes * 1000 // (self._format[0] * self._frame_bytes)
            logger.info(
                "audio: %d ms dropped (speaker behind), %d under-run(s)",
                dropped_ms,
                self._starved,
            )
