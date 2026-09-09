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
    pump_until_frame_received,
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
        # Registered and torn down every iteration — unlike
        # test_lifecycle_churn.py, which registers once per client and lets
        # close() clean it up, this is the scenario for repeated
        # subscribe/unsubscribe on a *long-lived* track: does on_frame/
        # off_frame leak across many cycles on the same session, not just
        # survive one client's teardown.
        main_video = reactor.track("main_video")
        received: list[object] = []

        # A named function, not an inline lambda passed separately to each
        # call: off_frame() unregisters by matching the exact callable object
        # on_frame() was given (Track._adapters is keyed by it) — two
        # differently-created lambdas that merely *do* the same thing would
        # never match, and off_frame() would silently no-op (it pops with a
        # default rather than raising), leaking a handler every iteration.
        def on_video_frame(received_frame: object) -> None:
            received.append(received_frame)

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
        assert len(reactor._pending_completions) == 0, (
            f"{len(reactor._pending_completions)} pending completion(s) left "
            f"over after iteration {iteration} — a send_command reply was "
            "never settled"
        )
        # Same reasoning, for the receive side: off_frame() above should have
        # popped this iteration's handler back out, every time.
        assert len(main_video._adapters) == 0, (
            f"{len(main_video._adapters)} frame handler(s) left registered on "
            f"main_video after iteration {iteration} — off_frame() didn't clean "
            "up"
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
    # Trend-based, not "never past the start" like test_lifecycle_churn.py's:
    # one long-lived session can legitimately grow a thread or two / open a
    # few fds during warm-up (a connection pool, a worker thread spinning up)
    # and then plateau — same reasoning as RSS/CPU above, just with small
    # integers instead of bytes/seconds, hence the small min_absolute_delta.
    assert_no_sustained_growth(
        [float(s.num_threads) for s in sampler.samples],
        name="num_threads",
        max_growth_ratio=0.15,
        min_absolute_delta=2,
    )
    assert_no_sustained_growth(
        [float(s.num_fds) for s in sampler.samples],
        name="num_fds",
        max_growth_ratio=0.15,
        min_absolute_delta=3,
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
