"""Endurance: one long-lived session, repeated publish/unpublish churn on a
sendonly track slot — no frames, no commands, no reconnect.

Isolates the publish/unpublish slot-activation path itself
(`Reactor.publish_track`/`unpublish_track` in `reactor_sdk/client.py`) from
`test_session_churn.py`'s broader mix (which also pushes frames and sends a
command each iteration): a leak specific to *this* pair should read as its
own clear signal instead of being folded into a trend that several different
operations are all contributing to. Also packs in far more iterations per
minute than session-churn, since there is no per-cycle frame-pump wait or
command round trip.

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

# reactor/echo declares a fixed set of track names (see echo_model.py) — same
# reasoning as test_session_churn.py's own TRACK_NAME: republish the same
# slot every iteration rather than invent a fresh per-iteration name.
TRACK_NAME = "webcam"

DESCRIPTION = (
    "One long-lived session: repeated publish/unpublish on a single sendonly "
    "slot. No frames, no commands, no reconnect — isolates the publish/unpublish "
    "path from session-churn's broader mix."
)


async def test_publish_unpublish_churn_has_no_sustained_growth(reactor: Reactor) -> None:
    sampler = ResourceSampler()
    live = LiveReporter("publish-churn", ENDURANCE_DURATION_SECONDS)
    iteration = 0
    metrics: list[MetricResult] = []
    started_at = now_iso()

    # Set on the first in-loop invariant violation below — same reasoning as
    # test_session_churn.py's own flag: breaks the loop (further iterations'
    # trend data would be unreliable against a corrupted invariant) without
    # letting the AssertionError propagate into finish_run() as an "ERROR"
    # (which reads as "not a detected leak" — wrong for a check that *is* one).
    invariant_violated = False

    try:
        while not sampler.deadline_reached():
            track = await reactor.publish_track(TRACK_NAME)
            track.unpublish()

            # Exact, not trend-based: unpublish() above already returned, so the
            # slot should report not-published on every single iteration — a
            # leftover True here means the SDK-side flag didn't clear even
            # though the cycle otherwise completed normally.
            if track.published:
                metrics.append(
                    MetricResult(
                        name="track_published_after_unpublish",
                        start=0,
                        end=1,
                        change=1,
                        unit="count",
                        status="fail",
                        detail=(
                            f"{TRACK_NAME!r} still reports published=True after "
                            f"unpublish() on iteration {iteration}"
                        ),
                        threshold="always False",
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
            # baseline is 1, not 0 — same reasoning as test_session_churn.py.
            metrics.extend(standard_resource_metrics(sampler.samples))
    finally:
        finish_and_check(
            test_name="publish-churn",
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
