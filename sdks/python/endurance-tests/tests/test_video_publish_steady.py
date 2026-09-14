"""Endurance: publish a single video track once and hold it, continuously
pushing frames at a steady rate for the whole run — no pause, no unpublish,
no reconnect, no other operation in between.

Every other scenario in this suite is *churn* — repeatedly doing and undoing
something (publish/unpublish, pause/resume, a full connect/close cycle) to
surface a leak in that specific operation. This one is the opposite shape on
purpose: a real long call looks like "publish once, stream for a long time,"
not "publish and unpublish thousands of times a minute" — so this is the
scenario for a leak that only shows up under sustained steady-state
streaming (an encoder buffer that grows with elapsed time or frame count
rather than with churn count, say), which the churn scenarios would not be
positioned to catch even if they ran forever.

Manual-only for now (see ../README.md) — not wired into CI yet. Run with:

    ENDURANCE_DURATION_SECONDS=3600 mise run test:python:endurance-tests
"""

from __future__ import annotations

import asyncio
import sys
from pathlib import Path

from helpers import ENDURANCE_DURATION_SECONDS, ResourceSampler, solid_rgb_frame
from trends import standard_resource_metrics

sys.path.insert(0, str(Path(__file__).parent.parent))
from report import LiveReporter, MetricResult, finish_and_check, now_iso  # noqa: E402

import reactor_sdk
from reactor_sdk import Reactor, Track

WIDTH, HEIGHT = 64, 64
FPS = 30
# reactor/echo declares a fixed set of track names (see echo_model.py) — same
# sendonly slot the churn scenarios publish, held open here instead of
# republished every cycle.
TRACK_NAME = "webcam"

DESCRIPTION = (
    f"One video track, published once and held for the whole run: continuous "
    f"push_frame() at {FPS} fps, no pause, no unpublish, no reconnect — the "
    "steady-state shape a real long call actually takes, as opposed to the "
    "other scenarios' repeated setup/teardown."
)


async def test_video_publish_steady_has_no_sustained_growth(reactor: Reactor) -> None:
    sampler = ResourceSampler()
    live = LiveReporter("video-publish-steady", ENDURANCE_DURATION_SECONDS)
    frame = solid_rgb_frame(WIDTH, HEIGHT, (90, 140, 200))
    iteration = 0
    metrics: list[MetricResult] = []
    started_at = now_iso()
    # None until publish_track() below succeeds — guards the unpublish() in
    # the finally block against a publish that never got that far.
    track: Track | None = None

    try:
        track = await reactor.publish_track(TRACK_NAME)

        while not sampler.deadline_reached():
            # One second of continuous streaming, then one sample — a cycle
            # here is "how much streaming happened between samples," not a
            # discrete operation the way the other scenarios' cycles are.
            # Keeps the sample count (and so the JSON report size) bounded to
            # roughly one per second of ENDURANCE_DURATION_SECONDS, rather
            # than one per push_frame() call.
            for _ in range(FPS):
                track.push_frame(frame)
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
            test_name="video-publish-steady",
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
