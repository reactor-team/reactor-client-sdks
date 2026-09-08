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

import tracemalloc

from helpers import (
    ENDURANCE_DURATION_SECONDS,
    ResourceSampler,
    assert_never_grows,
    assert_no_sustained_growth,
    cpu_deltas,
    solid_rgb_frame,
)

from reactor_sdk import Reactor

WIDTH, HEIGHT = 64, 64
# reactor/echo declares a fixed set of track names (see echo_model.py) — there
# is no such thing as a fresh per-iteration name to publish, unlike a client
# handle, which is why this scenario republishes the same "webcam" slot every
# iteration instead of test_lifecycle_churn.py's fresh-object-per-cycle shape.
TRACK_NAME = "webcam"


async def test_session_churn_has_no_sustained_growth(reactor: Reactor) -> None:
    sampler = ResourceSampler()
    frame = solid_rgb_frame(WIDTH, HEIGHT, (30, 150, 90))
    tracemalloc.start()
    mid_snapshot = None
    iteration = 0

    while not sampler.deadline_reached():
        track = await reactor.publish_track(TRACK_NAME)
        for _ in range(3):
            track.push_frame(frame)
        await reactor.send_command("set_effect", {"effect": "invert"})
        track.unpublish()

        # Exact, not trend-based: every send_command above is awaited to
        # completion before this line runs, so nothing should still be
        # sitting in the pending-completions map between iterations. If
        # something is, that's a leaked awaitable, not noise.
        assert len(reactor._pending_completions) == 0, (
            f"{len(reactor._pending_completions)} pending completion(s) left "
            f"over after iteration {iteration} — a send_command reply was "
            "never settled"
        )

        sampler.sample(cycle=iteration)
        elapsed = sampler.samples[-1].elapsed_s
        if mid_snapshot is None and elapsed >= ENDURANCE_DURATION_SECONDS * 0.3:
            mid_snapshot = tracemalloc.take_snapshot()
        iteration += 1

    final_snapshot = tracemalloc.take_snapshot()
    tracemalloc.stop()

    sampler.print_report()
    assert iteration >= 3, (
        f"only completed {iteration} iteration(s) — raise ENDURANCE_DURATION_SECONDS "
        "to get enough data for a trend"
    )

    # live_clients/orphaned_callbacks should stay exactly flat (not 0 — the
    # `reactor` fixture's one client is connected for the whole test).
    assert_never_grows(sampler.samples, field="live_clients")
    assert_never_grows(sampler.samples, field="orphaned_callbacks")
    assert_no_sustained_growth(
        [s.rss_bytes for s in sampler.samples],
        name="rss_bytes",
        max_growth_ratio=0.15,
        min_absolute_delta=5_000_000,
    )
    assert_no_sustained_growth(
        cpu_deltas(sampler.samples),
        name="cpu_s_per_cycle",
        max_growth_ratio=0.5,
        min_absolute_delta=0.05,
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
        biggest = diff[0].size_diff if diff else 0
        assert biggest < 5_000_000, (
            f"a single allocation site grew {biggest / 1e6:.1f} MB between the "
            "run's ~30% mark and its end — see the top-10 breakdown above"
        )
