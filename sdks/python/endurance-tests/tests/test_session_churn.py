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
    pump_until_frame_received,
    solid_rgb_frame,
)
from trends import standard_resource_metrics

sys.path.insert(0, str(Path(__file__).parent.parent))
from report import LiveReporter, MetricResult, finish_and_check, now_iso  # noqa: E402

import reactor_sdk
from reactor_sdk import Reactor

WIDTH, HEIGHT = 64, 64
# reactor/echo declares a fixed set of track names (see echo_model.py) — there
# is no such thing as a fresh per-iteration name to publish, unlike a client
# handle, which is why this scenario republishes the same "webcam" slot every
# iteration instead of test_lifecycle_churn.py's fresh-object-per-cycle shape.
TRACK_NAME = "webcam"

DESCRIPTION = (
    "One long-lived session (connects once): repeated publish, push_frame, "
    "command, unpublish cycles against it. No reconnect overhead, so it packs "
    "far more iterations per run than lifecycle-churn."
)


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

            # live_clients baseline is 1, not 0 — the `reactor` fixture's one
            # client is connected for the whole test. See
            # standard_resource_metrics()'s own docstring for both parameters.
            metrics.extend(standard_resource_metrics(sampler.samples))

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
        finish_and_check(
            test_name="session-churn",
            sdk="Python",
            description=DESCRIPTION,
            sdk_version=reactor_sdk.__version__,
            duration_s=ENDURANCE_DURATION_SECONDS,
            started_at=started_at,
            sampler=sampler,
            live=live,
            metrics=metrics,
            iterations=iteration,
            tracemalloc_top=tracemalloc_top,
            # Same field as every in-loop update() call above — reactor is
            # still connected here (this scenario never disconnects it), so
            # the final forced print can report it too instead of dropping it.
            extra={"Pending": str(len(reactor._pending_completions))},
        )
