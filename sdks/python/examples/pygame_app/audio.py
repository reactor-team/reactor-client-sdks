"""Speaker playback for the audio the SDK delivers.

The SDK does not open an audio device unless asked. That keeps the microphone shut —
a model declaring a sendonly audio track used to be enough to put live microphone
audio on the wire — but it also means nothing plays the decoded audio arriving from
the far end. Playing it is the application's job now, and this is the application
doing it.

Two threads meet here and neither may wait for the other. The SDK delivers ~10 ms of
PCM on its own delivery thread, and blocking there costs dropped audio upstream.
sounddevice pulls from the output stream on a callback thread with a hard deadline of
its own. So a bounded queue sits between them: full means the speaker is behind, and
the oldest audio is dropped rather than the queue growing without limit.

`sounddevice` is optional. Without it the example runs silently rather than refusing
to start, since the video is the point of the app.
"""

from __future__ import annotations

import logging
import queue
from typing import Any

logger = logging.getLogger(__name__)

#: How much audio may wait to be played. At ~10 ms a chunk this is roughly 300 ms —
#: enough to absorb a scheduling hiccup, short enough that a persistently slow
#: consumer does not accumulate latency you can hear as delay.
QUEUE_DEPTH = 30

#: Chunks to collect before playback starts, ~50 ms. The device asks for audio as soon
#: as the stream opens, which is before any has arrived, and every such request is an
#: under-run heard as a click. Waiting for a little to accumulate first trades that for
#: a barely perceptible delay. Applies once, at the start of the stream.
PREROLL_CHUNKS = 5


class AudioPlayer:
    """Plays PCM handed to it by :meth:`submit`, or does nothing if it cannot."""

    def __init__(self, sample_rate: int = 48000, channels: int = 1) -> None:
        self.sample_rate = sample_rate
        self.channels = channels
        self._chunks: queue.Queue[bytes] = queue.Queue(maxsize=QUEUE_DEPTH)
        self._stream: Any | None = None
        self._dropped = 0
        self._starved = 0
        self._playing = False

    # ------------------------------------------------------------------
    # Lifecycle
    # ------------------------------------------------------------------

    def start(self) -> None:
        """Open the output stream. Safe to call when sounddevice is missing."""
        try:
            import numpy as np
            import sounddevice as sd
        except ModuleNotFoundError:
            logger.warning(
                "sounddevice is not installed, so received audio will not be played. "
                "`pip install sounddevice` to hear it."
            )
            return

        def callback(outdata: Any, frames: int, _time: Any, status: Any) -> None:
            # Runs on sounddevice's own thread, with a deadline. Never block here:
            # under-run and output silence instead, which is a click rather than a
            # stall.
            if status:
                logger.debug("audio output status: %s", status)

            wanted = frames * self.channels * 2  # int16

            # Hold off until a little has accumulated, then keep playing. Counting an
            # under-run here would be misleading: nothing is late, the stream has not
            # started.
            if not self._playing:
                if self._chunks.qsize() < PREROLL_CHUNKS:
                    outdata[:] = bytes(wanted)
                    return
                self._playing = True
            buffer = bytearray()
            while len(buffer) < wanted:
                try:
                    buffer += self._chunks.get_nowait()
                except queue.Empty:
                    break

            if len(buffer) < wanted:
                # Ran dry. Pad this block and go back to buffering rather than
                # continuing at the edge of empty, which turns steady jitter into a
                # click on nearly every block. One short silence beats a stream of
                # them, and it is the same reason a jitter buffer exists at all.
                self._starved += 1
                self._playing = False
                buffer += bytes(wanted - len(buffer))
            elif len(buffer) > wanted:
                # Chunks do not divide evenly into the requested block, so hand the
                # remainder back rather than truncating it away.
                extra = bytes(buffer[wanted:])
                del buffer[wanted:]
                self._requeue(extra)

            outdata[:] = np.frombuffer(bytes(buffer), dtype=np.int16).reshape(frames, self.channels)

        try:
            self._stream = sd.RawOutputStream(
                samplerate=self.sample_rate,
                channels=self.channels,
                dtype="int16",
                callback=callback,
            )
            self._stream.start()
            logger.info("audio output open: %d Hz, %d channel(s)", self.sample_rate, self.channels)
        except Exception:
            # No output device, a rate the device will not take, an exclusive-mode
            # conflict. None of it is worth taking the app down for.
            logger.warning("could not open an audio output device", exc_info=True)
            self._stream = None

    def stop(self) -> None:
        if self._stream is None:
            return
        try:
            self._stream.stop()
            self._stream.close()
        except Exception:  # pragma: no cover - teardown
            logger.debug("error closing the audio output", exc_info=True)
        finally:
            self._stream = None
            self._playing = False

        if self._dropped or self._starved:
            logger.info("audio: %d chunk(s) dropped, %d under-run(s)", self._dropped, self._starved)

    # ------------------------------------------------------------------
    # Feeding
    # ------------------------------------------------------------------

    def submit(self, pcm: bytes) -> None:
        """Queue PCM for playback. Called from the SDK's audio delivery thread.

        Never blocks: the alternative is holding up the SDK's delivery thread, which
        costs audio upstream as well as here.
        """
        if self._stream is None:
            return
        self._requeue(pcm)

    def _requeue(self, pcm: bytes) -> None:
        try:
            self._chunks.put_nowait(pcm)
        except queue.Full:
            # The speaker is behind. Drop the oldest so what plays next is the most
            # recent audio rather than a growing backlog of stale audio.
            try:
                self._chunks.get_nowait()
                self._chunks.put_nowait(pcm)
            except (queue.Empty, queue.Full):  # pragma: no cover - racing consumer
                pass
            self._dropped += 1
            if self._dropped in (1, 10, 100, 1000):
                logger.debug("audio queue full, dropped %d chunk(s)", self._dropped)
