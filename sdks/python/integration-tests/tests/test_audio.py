"""`Track.push_frame`/`on_frame`'s audio path — new to this suite, not
mirrored from anywhere: the JS suite publishes a `mic` track but never checks
what arrives on the other end, and this suite's own video coverage
(`test_tracks_and_frames.py`) never touched audio either. Same object model
as video — one `push_frame`/`on_frame` pair, dispatched on the track's kind —
so the gap was in what this suite exercised, not in the SDK.

`reactor/echo` declares `mic` (sendonly) and `main_audio` (recvonly) and
passes audio through unchanged (`echo_model.py`'s `EchoInput`/`EchoOutput`),
but its own tick loop only advances on a `webcam` read — `main_audio` never
emits unless `webcam` is also being pumped, regardless of what `mic` has
queued. Every test here publishes and pumps both for that reason, even the
ones that only assert on audio.

There is no `reactor::sdk_audio` equivalent to exclude here the way the C++
suite's build does: this SDK's optional device helpers
(`reactor_sdk.audio_devices`, `Speaker`/`Microphone`) live off the mandatory
import path already and this suite never imports them, so nothing pulls in
real device I/O to begin with.
"""

from __future__ import annotations

import asyncio

import numpy as np
import pytest
from conftest import sine_wave_samples, solid_rgb_frame, wait_until

from reactor_sdk import InvalidStateError, Reactor

WIDTH, HEIGHT = 64, 64
FPS = 30
SAMPLE_RATE = 48_000
AUDIO_CHUNK_SAMPLES = SAMPLE_RATE // FPS  # one chunk per video tick


async def _pump_video(track, *, seconds: float) -> None:
    frame = solid_rgb_frame(WIDTH, HEIGHT, (20, 20, 20))
    end = asyncio.get_running_loop().time() + seconds
    while asyncio.get_running_loop().time() < end:
        track.push_frame(frame)
        await asyncio.sleep(1 / FPS)


async def _pump_audio(track, *, seconds: float) -> None:
    tone = sine_wave_samples(AUDIO_CHUNK_SAMPLES, sample_rate=SAMPLE_RATE)
    end = asyncio.get_running_loop().time() + seconds
    while asyncio.get_running_loop().time() < end:
        track.push_frame(tone, sample_rate=SAMPLE_RATE, num_channels=1)
        await asyncio.sleep(1 / FPS)


async def test_publish_mic_and_push_frame_reaches_main_audio(reactor: Reactor) -> None:
    webcam = await reactor.publish_track("webcam")
    mic = await reactor.publish_track("mic")
    main_audio = reactor.track("main_audio")

    audible_chunks = 0

    def collect(frame, *_args) -> None:
        nonlocal audible_chunks
        if frame.size == 0:
            return
        # A real tone's mean absolute amplitude sits well above digital
        # silence; no attempt to match the pushed tone's exact amplitude —
        # this has gone through a real Opus encode/decode round trip, the
        # audio equivalent of test_tracks_and_frames.py's colour tolerance.
        if np.abs(frame.astype(np.float64)).mean() > 50.0:
            audible_chunks += 1

    main_audio.on_frame(collect)

    video_pump = asyncio.ensure_future(_pump_video(webcam, seconds=10.0))
    audio_pump = asyncio.ensure_future(_pump_audio(mic, seconds=10.0))
    try:
        await wait_until(lambda: audible_chunks >= 3, timeout=10.0)
    finally:
        video_pump.cancel()
        audio_pump.cancel()
        mic.unpublish()
        webcam.unpublish()


async def test_pushing_audio_before_publish_raises(reactor: Reactor) -> None:
    mic = reactor.track("mic")
    tone = sine_wave_samples(AUDIO_CHUNK_SAMPLES, sample_rate=SAMPLE_RATE)
    with pytest.raises(InvalidStateError):
        mic.push_frame(tone, sample_rate=SAMPLE_RATE, num_channels=1)


async def test_user_data_on_an_audio_track_is_refused(reactor: Reactor) -> None:
    # track.py's push_frame docstring: an audio frame has nowhere to carry a
    # tag, so passing one is an error rather than a silent no-op — unlike
    # sample_rate on a video track (see the next test), where the argument is
    # merely redundant.
    mic = await reactor.publish_track("mic")
    tone = sine_wave_samples(AUDIO_CHUNK_SAMPLES, sample_rate=SAMPLE_RATE)
    try:
        with pytest.raises(TypeError):
            mic.push_frame(tone, sample_rate=SAMPLE_RATE, user_data=b"tag")
    finally:
        mic.unpublish()


async def test_capture_time_us_on_an_audio_track_is_refused(reactor: Reactor) -> None:
    mic = await reactor.publish_track("mic")
    tone = sine_wave_samples(AUDIO_CHUNK_SAMPLES, sample_rate=SAMPLE_RATE)
    try:
        with pytest.raises(TypeError):
            mic.push_frame(tone, sample_rate=SAMPLE_RATE, capture_time_us=1)
    finally:
        mic.unpublish()


async def test_sample_rate_on_a_video_track_is_merely_redundant_not_an_error(reactor: Reactor) -> None:
    # The asymmetric case the two tests above imply but don't themselves
    # prove: an audio-only keyword on a video track is let through when
    # ignoring it loses nothing, per the same docstring. Verified directly
    # rather than assumed — the opposite of what this file's other two
    # refusal tests check.
    webcam = await reactor.publish_track("webcam")
    frame = solid_rgb_frame(WIDTH, HEIGHT, (1, 2, 3))
    try:
        webcam.push_frame(frame, sample_rate=SAMPLE_RATE)
    finally:
        webcam.unpublish()
