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
import sys
from pathlib import Path

from helpers import (
    ENDURANCE_DURATION_SECONDS,
    ResourceSampler,
    new_reactor,
    paced_connect,
    pump_until_frame_received,
    solid_rgb_frame,
)
from trends import standard_resource_metrics

sys.path.insert(0, str(Path(__file__).parent.parent))
from report import LiveReporter, MetricResult, finish_and_check, now_iso  # noqa: E402

import reactor_sdk

WIDTH, HEIGHT = 64, 64

DESCRIPTION = (
    "Fresh Reactor per cycle: connect, publish, push frames, send a command, "
    "disconnect, close. Exercises the whole native-handle lifecycle "
    "(_create_handle/_destroy_handle)."
)


async def test_lifecycle_churn_leaves_no_leftover_handles_or_growth() -> None:
    sampler = ResourceSampler()
    live = LiveReporter("lifecycle-churn", ENDURANCE_DURATION_SECONDS)
    frame = solid_rgb_frame(WIDTH, HEIGHT, (200, 80, 40))
    cycle = 0
    errors = 0
    metrics: list[MetricResult] = []
    started_at = now_iso()

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
                        # Counted, not just swallowed: close() below still runs
                        # regardless (this is a best-effort disconnect, not a
                        # thing worth failing a multi-hour run over), but a
                        # disconnect() that's failing on some cycles is itself
                        # a signal worth surfacing in the report rather than a
                        # hardcoded-looking "Errors 0" that never moves.
                        errors += 1
            finally:
                client.close()

            # Mirrors _close_live_clients()'s own assumption (client.py): a client
            # that's actually done should be fully collectible right after close(),
            # not just eventually. Forcing gc here makes _LIVE_CLIENTS a same-cycle
            # signal instead of one that lags behind by however long the collector
            # feels like waiting.
            gc.collect()
            sampler.sample(cycle=cycle)
            live.update(sampler.samples, errors=errors)
            cycle += 1

        assert cycle >= 3, (
            f"only completed {cycle} cycle(s) — raise ENDURANCE_DURATION_SECONDS to "
            "get enough data for a trend"
        )

        # baseline-zero: this scenario starts with no clients. fds_exact: socket/fd
        # teardown proved synchronous with disconnect()/close() returning in a real
        # run (constant every single cycle), so num_fds gets the strict "never past
        # its starting value" check instead of a trend — see
        # standard_resource_metrics()'s own docstring for both parameters.
        metrics.extend(
            standard_resource_metrics(
                sampler.samples, live_clients_baseline_zero=True, fds_exact=True
            )
        )
    finally:
        finish_and_check(
            test_name="lifecycle-churn",
            sdk="Python",
            description=DESCRIPTION,
            sdk_version=reactor_sdk.__version__,
            duration_s=ENDURANCE_DURATION_SECONDS,
            started_at=started_at,
            sampler=sampler,
            live=live,
            metrics=metrics,
            iterations=cycle,
            errors=errors,
        )
