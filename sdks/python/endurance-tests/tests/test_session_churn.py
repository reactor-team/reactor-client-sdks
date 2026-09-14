"""Endurance: one long-lived session, repeated publish/push_frame/command/
unpublish cycles against it.

Manual-only for now (see ../README.md) — not wired into CI yet. Run with:

    ENDURANCE_DURATION_SECONDS=3600 mise run test:python:endurance-tests

No reconnect overhead here (unlike test_lifecycle_churn.py), so this packs
far more iterations into the same wall-clock window — the scenario for
per-operation leaks (frame buffers, `Track` objects, pending command
completions) that a coarser connect/close cycle wouldn't surface as clearly.
"""

from __future__ import annotations

import sys
import tracemalloc
from pathlib import Path

from helpers import (
    ENDURANCE_DURATION_SECONDS,
    ResourceSampler,
    assert_always_zero,
    assert_never_grows,
    assert_no_sustained_growth,
    cpu_deltas,
    pump_until_frame_received,
    solid_rgb_frame,
)

sys.path.insert(0, str(Path(__file__).parent.parent))
from report import LiveReporter, MetricResult, finish_run, now_iso  # noqa: E402

from reactor_sdk import Reactor

WIDTH, HEIGHT = 64, 64
# reactor/echo declares a fixed set of track names (see echo_model.py) — there
# is no such thing as a fresh per-iteration name to publish, unlike a client
# handle, which is why this scenario republishes the same "webcam" slot every
# iteration instead of test_lifecycle_churn.py's fresh-object-per-cycle shape.
TRACK_NAME = "webcam"


async def test_session_churn_has_no_sustained_growth(reactor: Reactor) -> None:
    sampler = ResourceSampler()
    live = LiveReporter("session-churn", ENDURANCE_DURATION_SECONDS)
    frame = solid_rgb_frame(WIDTH, HEIGHT, (30, 150, 90))
    tracemalloc.start()
    mid_snapshot = None
    final_snapshot = None
    tracemalloc_top: list[str] | None = None
    iteration = 0
    # No `errors` counter here, unlike test_lifecycle_churn.py: nothing in
    # this loop catches and retries a transient failure (there's no
    # `except Exception: pass` to count) — the report's Errors row/live
    # status is left unset (None) rather than a hardcoded-looking 0 that
    # never actually measured anything. See report.py's RunResult.errors.
    metrics: list[MetricResult] = []
    started_at = now_iso()

    # Set on the first in-loop invariant violation below (pending completions
    # or an orphaned frame handler) — breaks the loop, since a corrupted
    # invariant makes further iterations' trend data unreliable, and skips
    # the post-loop trend metrics/tracemalloc diagnostics that assume a
    # normally-completed run. Deliberately *not* left to raise: an
    # AssertionError propagating out of the loop would land in `finish_run()`
    # as `run_error` (status "ERROR"), and _conclusion_lines() reports ERROR
    # as "not a detected leak — looks like a test error or infrastructure
    # problem" — exactly backwards for these two, which *are* leak checks.
    # Recording a normal "fail" MetricResult instead keeps the status "FAIL"
    # and the conclusion accurate.
    invariant_violated = False

    try:
        while not sampler.deadline_reached():
            # Registered and torn down every iteration — unlike
            # test_lifecycle_churn.py, which registers once per client and lets
            # close() clean it up, this is the scenario for repeated
            # subscribe/unsubscribe on a *long-lived* track: does on_frame/
            # off_frame leak across many cycles on the same session, not just
            # survive one client's teardown.
            main_video = reactor.track("main_video")
            # A bounded flag, not an accumulating frame buffer — see
            # test_lifecycle_churn.py's own comment on the same pattern: only
            # whether one frame arrived matters to pump_until_frame_received()
            # below, and this stays registered until off_frame() a few lines down.
            received: list[bool] = []

            # A named function, not an inline lambda passed separately to each
            # call: off_frame() unregisters by matching the exact callable object
            # on_frame() was given (Track._adapters is keyed by it) — two
            # differently-created lambdas that merely *do* the same thing would
            # never match, and off_frame() would silently no-op (it pops with a
            # default rather than raising), leaking a handler every iteration.
            def on_video_frame(_frame: object) -> None:
                if not received:
                    received.append(True)

            main_video.on_frame(on_video_frame)

            track = await reactor.publish_track(TRACK_NAME)
            await pump_until_frame_received(track, frame, received)
            await reactor.send_command("set_effect", {"effect": "invert"})
            track.unpublish()
            main_video.off_frame(on_video_frame)

            # Exact, not trend-based: every send_command above is awaited to
            # completion before this line runs, so nothing should still be
            # sitting in the pending-completions map between iterations. If
            # something is, that's a leaked awaitable, not noise.
            pending = len(reactor._pending_completions)
            if pending != 0:
                metrics.append(
                    MetricResult(
                        name="pending_completions",
                        start=0,
                        end=pending,
                        change=pending,
                        unit="count",
                        status="fail",
                        detail=(
                            f"{pending} pending completion(s) left over after "
                            f"iteration {iteration} — a send_command reply was "
                            "never settled"
                        ),
                        threshold="always 0",
                        first_bad_cycle=iteration,
                    )
                )
                invariant_violated = True
                break
            # Same reasoning, for the receive side: off_frame() above should have
            # popped this iteration's handler back out, every time.
            adapters = len(main_video._adapters)
            if adapters != 0:
                metrics.append(
                    MetricResult(
                        name="orphaned_frame_handlers",
                        start=0,
                        end=adapters,
                        change=adapters,
                        unit="count",
                        status="fail",
                        detail=(
                            f"{adapters} frame handler(s) left registered on "
                            f"main_video after iteration {iteration} — off_frame() "
                            "didn't clean up"
                        ),
                        threshold="always 0",
                        first_bad_cycle=iteration,
                    )
                )
                invariant_violated = True
                break

            sampler.sample(cycle=iteration)
            live.update(
                sampler.samples,
                extra={"Pending": str(len(reactor._pending_completions))},
            )
            elapsed = sampler.samples[-1].elapsed_s
            if mid_snapshot is None and elapsed >= ENDURANCE_DURATION_SECONDS * 0.3:
                mid_snapshot = tracemalloc.take_snapshot()
            iteration += 1

        final_snapshot = tracemalloc.take_snapshot()

        # Skipped once an in-loop invariant check above has already recorded
        # a "fail" and broken out: `iteration` may be too small for a
        # meaningful trend at that point, and there's already a clear reason
        # for the run's status — no need for the min-iteration guard or the
        # other metrics to also weigh in.
        if not invariant_violated:
            assert iteration >= 3, (
                f"only completed {iteration} iteration(s) — raise ENDURANCE_DURATION_SECONDS "
                "to get enough data for a trend"
            )

            # live_clients should stay exactly flat, but not at 0 — the `reactor`
            # fixture's one client is connected for the whole test, so its baseline
            # is 1, not 0.
            metrics.append(assert_never_grows(sampler.samples, field="live_clients"))
            # Always-zero, not never-grows: orphaned callbacks come only from
            # clients that have already closed, so the invariant is 0 regardless
            # of how many clients are live. assert_never_grows would only flag
            # growth *past* whatever the first sample happened to be, so a leak
            # already present at cycle 0 (fixture setup, an earlier test) would
            # pass silently forever.
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
            # Same trend check, on the % reading instead of the raw seconds — see
            # Sample.cpu_percent's own docstring for why the two can disagree.
            metrics.append(
                assert_no_sustained_growth(
                    [s.cpu_percent for s in sampler.samples],
                    name="cpu_percent",
                    unit="%",
                    max_growth_ratio=0.5,
                    min_absolute_delta=5.0,
                )
            )
            # Trend-based, not "never past the start" like test_lifecycle_churn.py's:
            # one long-lived session can legitimately grow a thread or two / open a
            # few fds during warm-up (a connection pool, a worker thread spinning up)
            # and then plateau — same reasoning as RSS/CPU above, just with small
            # integers instead of bytes/seconds. `use_median` and the wider floor —
            # see test_lifecycle_churn.py's own comment on the identical call — guard
            # against the same one-cycle double-counted-thread artifact skewing a
            # short run's small comparison windows.
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

        # Diagnostic always, hard-asserted only against a generous floor:
        # tracemalloc diffs are known-noisy (one-time caches, string interning),
        # and this suite runs manually for now, so a human reads the report
        # rather than a nightly job trusting a tight auto-threshold.
        if mid_snapshot is not None:
            diff = final_snapshot.compare_to(mid_snapshot, "lineno")
            print("\ntracemalloc top growth (~30% mark → end):")
            for stat in diff[:10]:
                print(f"  {stat}")
            tracemalloc_top = [str(stat) for stat in diff[:10]]
            # Max, not diff[0]: compare_to() sorts by *absolute* size_diff, so a
            # large negative (freed) entry can sort first and mask a smaller-in-
            # magnitude but still-over-the-floor positive (grown) entry elsewhere
            # in the list.
            biggest = max((stat.size_diff for stat in diff), default=0)
            tracemalloc_is_leak = biggest >= 5_000_000
            print(
                f"[tracemalloc] {'LEAK?' if tracemalloc_is_leak else 'ok'}: biggest "
                f"single-traceback growth was {biggest / 1e6:.2f} MB — "
                + (
                    "over the 5 MB floor, see the breakdown above for where"
                    if tracemalloc_is_leak
                    else "under the 5 MB floor treated as noise (one-time caches, "
                    "string interning — see README.md)"
                )
            )
            metrics.append(
                MetricResult(
                    name="tracemalloc_max_growth",
                    start=0,
                    end=biggest / 1e6,
                    change=biggest / 1e6,
                    unit="MB",
                    status="fail" if tracemalloc_is_leak else "ok",
                    detail="a single allocation site grew past the diagnostic floor"
                    if tracemalloc_is_leak
                    else "under the 5 MB floor, treated as noise",
                    threshold="< 5 MB (diagnostic)",
                )
            )
    finally:
        # Stopping tracemalloc unconditionally matters as much as the report
        # below: an in-loop assertion failure above (a real leak) would
        # otherwise leave tracemalloc tracing every allocation for the rest
        # of this pytest process, skewing RSS/CPU for whatever runs next.
        tracemalloc.stop()
        if sampler.samples:
            # Same `extra={"Pending": ...}` as every in-loop update() call
            # above — reactor is still connected here (this scenario never
            # disconnects it), so the final forced block can report the same
            # field instead of silently dropping it.
            live.update(
                sampler.samples,
                extra={"Pending": str(len(reactor._pending_completions))},
                force=True,
            )

        result = finish_run(
            test_name="session-churn",
            sdk="Python",
            duration_s=ENDURANCE_DURATION_SECONDS,
            started_at=started_at,
            samples=sampler.samples,
            metrics=metrics,
            iterations=iteration,
            tracemalloc_top=tracemalloc_top,
        )

    if result.status == "FAIL":
        names = ", ".join(m.name for m in metrics if m.status == "fail")
        raise AssertionError(
            f"endurance test detected a failure in: {names} — see the report for details"
        )
