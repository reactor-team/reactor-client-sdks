"""Endurance: one long-lived session, repeated pause/resume churn on a
recvonly track — no frames, no commands, no reconnect.

Isolates `Track.pause()`/`resume()` (`reactor_sdk/track.py`, backed by
`Reactor._pause_track`/`_resume_track`) as its own scenario rather than
folding it into `test_session_churn.py`'s already-mixed publish/frame/
command loop — a leak specific to the pause/resume path (e.g. the native
session-side pause state not fully clearing) should read as its own signal.

Manual-only for now (see ../README.md) — not wired into CI yet. Run with:

    ENDURANCE_DURATION_SECONDS=3600 mise run test:python:endurance-tests
"""

from __future__ import annotations

import sys
from pathlib import Path

from helpers import ENDURANCE_DURATION_SECONDS, ResourceSampler
from trends import standard_resource_metrics

sys.path.insert(0, str(Path(__file__).parent.parent))
from report import LiveReporter, MetricResult, finish_and_check, now_iso  # noqa: E402

import reactor_sdk
from reactor_sdk import Reactor

# The recvonly track reactor/echo always declares — same one
# test_lifecycle_churn.py/test_session_churn.py already subscribe to for
# on_frame, chosen here for the same reason: no need to invent a name.
TRACK_NAME = "main_video"

DESCRIPTION = (
    "One long-lived session: repeated pause/resume on a recvonly track. No "
    "frames, no commands, no reconnect — isolates Track.pause()/resume() as "
    "its own signal."
)


async def test_pause_resume_churn_has_no_sustained_growth(reactor: Reactor) -> None:
    sampler = ResourceSampler()
    live = LiveReporter("pause-resume-churn", ENDURANCE_DURATION_SECONDS)
    iteration = 0
    metrics: list[MetricResult] = []
    started_at = now_iso()
    # Resolves the track's direction from the session's already-declared
    # capabilities (see Reactor.track()) — a local, synchronous lookup, no
    # network round trip, so this is safe to do once before the loop rather
    # than on every iteration.
    track = reactor.track(TRACK_NAME)

    # Set on the first in-loop invariant violation below — same reasoning as
    # test_publish_churn.py's own flag.
    invariant_violated = False

    try:
        while not sampler.deadline_reached():
            await track.pause()
            await track.resume()

            # Exact, not trend-based: resume() above already returned, so the
            # session should report nothing paused on every single iteration —
            # `paused_tracks` is read fresh from the session (see its own
            # docstring), not cached, so a leftover entry here is a real
            # leftover pause state, not a stale local flag.
            paused = reactor.paused_tracks
            if paused:
                metrics.append(
                    MetricResult(
                        name="paused_tracks_after_resume",
                        start=0,
                        end=len(paused),
                        change=len(paused),
                        unit="count",
                        status="fail",
                        detail=(
                            f"{sorted(paused)} still reported paused after resume() "
                            f"on iteration {iteration}"
                        ),
                        threshold="always empty",
                        first_bad_cycle=iteration,
                    )
                )
                invariant_violated = True
                break

            sampler.sample(cycle=iteration)
            live.update(sampler.samples)
            iteration += 1

        if not invariant_violated:
            assert iteration >= 3, (
                f"only completed {iteration} iteration(s) — raise ENDURANCE_DURATION_SECONDS "
                "to get enough data for a trend"
            )
            # The fixture's one client is connected for the whole test, so the
            # baseline is 1, not 0 — same reasoning as the other scenarios.
            metrics.extend(standard_resource_metrics(sampler.samples))
    finally:
        finish_and_check(
            test_name="pause-resume-churn",
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
