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

from helpers import (
    ENDURANCE_DURATION_SECONDS,
    ResourceSampler,
    assert_always_zero,
    assert_never_grows,
    assert_no_sustained_growth,
    cpu_deltas,
)

sys.path.insert(0, str(Path(__file__).parent.parent))
from report import LiveReporter, MetricResult, finish_run, now_iso  # noqa: E402

import reactor_sdk
from reactor_sdk import Reactor

# reactor/echo declares a fixed set of track names (see echo_model.py) — same
# reasoning as test_session_churn.py's own TRACK_NAME: republish the same
# slot every iteration rather than invent a fresh per-iteration name.
TRACK_NAME = "webcam"


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
            metrics.append(assert_never_grows(sampler.samples, field="live_clients"))
            metrics.append(assert_always_zero(sampler.samples, field="orphaned_callbacks"))
            metrics.append(
                assert_no_sustained_growth(
                    [s.rss_bytes / 1e6 for s in sampler.samples],
                    name="rss",
                    unit="MB",
                    max_growth_ratio=0.15,
                    min_absolute_delta=5.0,
                )
            )
            metrics.append(
                assert_no_sustained_growth(
                    cpu_deltas(sampler.samples),
                    name="cpu_s_per_cycle",
                    unit="s",
                    max_growth_ratio=0.5,
                    min_absolute_delta=0.05,
                )
            )
            metrics.append(
                assert_no_sustained_growth(
                    [s.cpu_percent for s in sampler.samples],
                    name="cpu_percent",
                    unit="%",
                    max_growth_ratio=0.5,
                    min_absolute_delta=5.0,
                )
            )
            # Trend, median-backed — same reasoning as the other two scenarios'
            # identical call: native thread teardown isn't guaranteed synchronous
            # cycle to cycle, so a strict "never past the start" check would flag
            # timing noise as a false leak.
            metrics.append(
                assert_no_sustained_growth(
                    [float(s.num_threads) for s in sampler.samples],
                    name="num_threads",
                    unit="count",
                    max_growth_ratio=0.15,
                    min_absolute_delta=4,
                    use_median=True,
                )
            )
            metrics.append(
                assert_no_sustained_growth(
                    [float(s.num_fds) for s in sampler.samples],
                    name="num_fds",
                    unit="count",
                    max_growth_ratio=0.15,
                    min_absolute_delta=3,
                )
            )
    finally:
        if sampler.samples:
            live.update(sampler.samples, force=True)

        result = finish_run(
            test_name="publish-churn",
            sdk="Python",
            duration_s=ENDURANCE_DURATION_SECONDS,
            started_at=started_at,
            samples=sampler.samples,
            metrics=metrics,
            iterations=iteration,
            sdk_version=reactor_sdk.__version__,
            description=(
                "One long-lived session: repeated publish/unpublish on a single "
                "sendonly slot. No frames, no commands, no reconnect — isolates "
                "the publish/unpublish path from session-churn's broader mix."
            ),
        )

    if result.status == "FAIL":
        names = ", ".join(m.name for m in metrics if m.status == "fail")
        raise AssertionError(
            f"endurance test detected a failure in: {names} — see the report for details"
        )
