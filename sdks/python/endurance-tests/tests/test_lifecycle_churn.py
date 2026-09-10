"""Endurance: repeated full connect → publish → command → disconnect → close
cycles, each on a brand-new `Reactor`.

Manual-only for now (see ../README.md) — not wired into CI yet. Run with:

    ENDURANCE_DURATION_SECONDS=3600 mise run test:python:endurance-tests

A fresh client per cycle is what actually exercises the full native-handle
lifecycle (`_create_handle`/`_destroy_handle` in `reactor_sdk/client.py`),
unlike `test_session_churn.py`'s single long-lived session, which only ever
takes the per-operation paths.
"""

from __future__ import annotations

import gc

from helpers import (
    ResourceSampler,
    assert_always_zero,
    assert_never_grows,
    assert_no_sustained_growth,
    cpu_deltas,
    new_reactor,
    paced_connect,
    pump_until_frame_received,
    solid_rgb_frame,
)

WIDTH, HEIGHT = 64, 64


async def test_lifecycle_churn_leaves_no_leftover_handles_or_growth() -> None:
    sampler = ResourceSampler()
    frame = solid_rgb_frame(WIDTH, HEIGHT, (200, 80, 40))
    cycle = 0

    try:
        while not sampler.deadline_reached():
            client = new_reactor()
            try:
                # Inside the try, not before it: a `paced_connect()` failure
                # (a transient live-service error, say) can still leave a native
                # handle — and possibly a partially created server session —
                # behind, so close() in the outer finally below has to run
                # regardless of whether connect itself succeeded.
                await paced_connect(client)
                try:
                    # Registered and never explicitly torn down (unlike
                    # test_session_churn.py's on/off pair) — close() below has to
                    # clean this up on its own, on a client that may still have a
                    # frame in flight. That's the specific path
                    # _ORPHANED_CALLBACKS exists to catch (see client.py's own
                    # comment on it), and publish/push_frame alone never reaches
                    # it: nothing before this touched Track._adapters or
                    # Reactor._handlers at all.
                    # A bounded flag, not an accumulating frame buffer: only
                    # whether one arrived matters to pump_until_frame_received()
                    # below, and the callback stays registered for the rest of
                    # this cycle (through send_command/unpublish/disconnect) —
                    # appending every subsequent frame here would grow for no
                    # reason and read like a leak on a report someone's skimming.
                    received: list[bool] = []
                    main_video = client.track("main_video")

                    def _mark_received(_frame: object) -> None:
                        if not received:
                            received.append(True)

                    main_video.on_frame(_mark_received)

                    webcam = await client.publish_track("webcam")
                    await pump_until_frame_received(webcam, frame, received)
                    await client.send_command("set_intensity", {"intensity": 0.5})
                    webcam.unpublish()
                finally:
                    try:
                        await client.disconnect()
                    except Exception:
                        pass
            finally:
                client.close()

            # Mirrors _close_live_clients()'s own assumption (client.py): a client
            # that's actually done should be fully collectible right after close(),
            # not just eventually. Forcing gc here makes _LIVE_CLIENTS a same-cycle
            # signal instead of one that lags behind by however long the collector
            # feels like waiting.
            gc.collect()
            sampler.sample(cycle=cycle)
            cycle += 1
    finally:
        # A transient failure (a RateLimitedError, a network hiccup) inside
        # the loop above would otherwise abort the test before this ever
        # runs — losing the whole run's accumulated RSS/CPU/handle-count
        # trend to one hiccup defeats a soak test more than the hiccup
        # itself. Whatever was collected up to the failure still prints.
        if sampler.samples:
            sampler.print_report()

    assert cycle >= 3, (
        f"only completed {cycle} cycle(s) — raise ENDURANCE_DURATION_SECONDS to "
        "get enough data for a trend"
    )

    assert_always_zero(sampler.samples, field="live_clients")
    # Always-zero, not never-grows: this scenario starts with no clients, and
    # the documented invariant (helpers.py's column guide, README.md) is that
    # orphaned callbacks are always 0 — a leak already present on cycle 0
    # would slip past assert_never_grows, which only flags growth *past*
    # whatever the first sample happened to be.
    assert_always_zero(sampler.samples, field="orphaned_callbacks")
    # num_fds, unlike num_threads just below, proved rock-solid across a real
    # run (constant every single cycle) — socket/fd teardown is synchronous
    # with disconnect()/close() returning, so the strict "never past its
    # starting value" check orphaned_callbacks/live_clients also get is the
    # right one here too, not a trend.
    assert_never_grows(sampler.samples, field="num_fds")
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
    # Same trend check, on the % reading instead of the raw seconds — the two
    # answer different questions (see Sample.cpu_percent's own docstring), so
    # a leak that shows up as a growing *proportion* of the CPU being busy,
    # even without cpu_s_per_cycle itself trending, would still get caught.
    assert_no_sustained_growth(
        [s.cpu_percent for s in sampler.samples],
        name="cpu_percent",
        max_growth_ratio=0.5,
        min_absolute_delta=5.0,
    )
    # Trend-based, not exact like num_fds above: a real run showed
    # num_threads oscillating (25, 31, 25, 24, 24, 25, 31, 30, 25, 25) with no
    # sustained direction — native thread teardown isn't guaranteed
    # synchronous with close()/gc.collect() the way an fd close() or a Python
    # object's collection is, so some cycles still catch a previous cycle's
    # worker mid-exit. A strict "never past the first sample" check flags
    # that timing noise as a false leak; a trend survives it the same way it
    # already does for RSS/CPU. `use_median` and the wider floor exist
    # because a *short* run's "last third" window can be just 2-3 samples —
    # small enough that one of those double-counted cycles alone swings a
    # plain mean past the threshold (observed on a real 90s run: 25→30,
    # +20%, on a healthy process). See assert_no_sustained_growth's own
    # docstring for why median fixes this without hiding a real leak.
    assert_no_sustained_growth(
        [float(s.num_threads) for s in sampler.samples],
        name="num_threads",
        max_growth_ratio=0.15,
        min_absolute_delta=4,
        use_median=True,
    )
