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
    solid_rgb_frame,
)

WIDTH, HEIGHT = 64, 64


async def test_lifecycle_churn_leaves_no_leftover_handles_or_growth() -> None:
    sampler = ResourceSampler()
    frame = solid_rgb_frame(WIDTH, HEIGHT, (200, 80, 40))
    cycle = 0

    while not sampler.deadline_reached():
        client = new_reactor()
        await paced_connect(client)
        try:
            webcam = await client.publish_track("webcam")
            for _ in range(3):
                webcam.push_frame(frame)
            await client.send_command("set_intensity", {"intensity": 0.5})
            webcam.unpublish()
        finally:
            try:
                await client.disconnect()
            except Exception:
                pass
            client.close()

        # Mirrors _close_live_clients()'s own assumption (client.py): a client
        # that's actually done should be fully collectible right after close(),
        # not just eventually. Forcing gc here makes _LIVE_CLIENTS a same-cycle
        # signal instead of one that lags behind by however long the collector
        # feels like waiting.
        gc.collect()
        sampler.sample(cycle=cycle)
        cycle += 1

    sampler.print_report()
    assert cycle >= 3, (
        f"only completed {cycle} cycle(s) — raise ENDURANCE_DURATION_SECONDS to "
        "get enough data for a trend"
    )

    assert_always_zero(sampler.samples, field="live_clients")
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
