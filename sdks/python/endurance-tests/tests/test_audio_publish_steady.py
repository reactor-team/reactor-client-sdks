"""Endurance: publish a single audio track once and hold it, continuously
pushing PCM chunks at a steady rate for the whole run — no pause, no
unpublish, no reconnect, no other operation in between.

Same steady-state shape as `test_video_publish_steady.py`, mirrored for
audio rather than parameterizing one scenario over both — the two paths
share nothing below `push_frame()` (`Track._audio_adapter` vs
`_video_adapter`, separate encoder/decoder threads in the native runtime),
so a leak in one is not evidence about the other, and a report that says
"audio-publish-steady failed" is more useful standing on its own than
folded into a single parameterized "media-publish-steady[audio]" name.

Manual-only for now (see ../README.md) — not wired into CI yet. Run with:

    ENDURANCE_DURATION_SECONDS=3600 mise run test:python:endurance-tests
"""

from __future__ import annotations

import asyncio
import sys
from pathlib import Path

from helpers import ENDURANCE_DURATION_SECONDS, ResourceSampler, sine_wave_samples
from trends import standard_resource_metrics

sys.path.insert(0, str(Path(__file__).parent.parent))
from report import LiveReporter, MetricResult, finish_and_check, now_iso  # noqa: E402

import reactor_sdk
from reactor_sdk import Reactor, Track

FPS = 30
SAMPLE_RATE = 48_000
NUM_CHANNELS = 1
CHUNK_SAMPLES = SAMPLE_RATE // FPS  # one chunk per tick, same convention as
# integration-tests/tests/test_audio.py's _pump_audio()
# reactor/echo declares a fixed set of track names (see echo_model.py) — the
# sendonly audio slot, held open here instead of republished every cycle.
TRACK_NAME = "mic"

DESCRIPTION = (
    f"One audio track, published once and held for the whole run: continuous "
    f"push_frame() PCM chunks at {FPS}/s ({SAMPLE_RATE} Hz), no pause, no "
    "unpublish, no reconnect — isolates the audio send path (separate from "
    "video's) in the same steady-state shape as video-publish-steady."
)


async def test_audio_publish_steady_has_no_sustained_growth(reactor: Reactor) -> None:
    sampler = ResourceSampler()
    live = LiveReporter("audio-publish-steady", ENDURANCE_DURATION_SECONDS)
    tone = sine_wave_samples(CHUNK_SAMPLES, sample_rate=SAMPLE_RATE, num_channels=NUM_CHANNELS)
    iteration = 0
    metrics: list[MetricResult] = []
    started_at = now_iso()
    # None until publish_track() below succeeds — guards the unpublish() in
    # the finally block against a publish that never got that far.
    track: Track | None = None

    try:
        track = await reactor.publish_track(TRACK_NAME)

        while not sampler.deadline_reached():
            # One second of continuous streaming, then one sample — same
            # reasoning as video-publish-steady's identical shape: keeps the
            # sample count bounded to roughly one per second rather than one
            # per push_frame() call.
            for _ in range(FPS):
                track.push_frame(tone, sample_rate=SAMPLE_RATE, num_channels=NUM_CHANNELS)
                await asyncio.sleep(1 / FPS)

            sampler.sample(cycle=iteration)
            live.update(sampler.samples)
            iteration += 1

        assert iteration >= 3, (
            f"only completed {iteration} second(s) of streaming — raise "
            "ENDURANCE_DURATION_SECONDS to get enough data for a trend"
        )
        # live_clients baseline is 1 (the fixture's one client, connected and
        # publishing for the whole run) — same reasoning as the other
        # long-lived-session scenarios.
        metrics.extend(standard_resource_metrics(sampler.samples))
    finally:
        if track is not None:
            track.unpublish()
        finish_and_check(
            test_name="audio-publish-steady",
            sdk="Python",
            description=DESCRIPTION,
            sdk_version=reactor_sdk.__version__,
            duration_s=ENDURANCE_DURATION_SECONDS,
            started_at=started_at,
            sampler=sampler,
            live=live,
            metrics=metrics,
            iterations=iteration,
        )
