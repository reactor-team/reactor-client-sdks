"""Concurrency and teardown races the happy-path specs don't reach.

The JS SDK needed a whole extra queue (`AwaitQueue` in `reactor.ts`, PR #137)
because its wasm binding exposes a separate JS-side client actor that races
connect/disconnect against in-flight calls; PR #136 fixed the equivalent race
one layer down, in `reactor-core`/`reactor-wasm`/`reactor-ffi` — the layer
Python's `Reactor` calls into directly (`client.py`'s `_async_op`). So there
is no known race here going in. This file is what would have found one if
there were: every test below is deliberately trying to make `_async_op`'s own
documented delicate case — "the trampoline's lifetime... a cancelled await
leaves the entry in place" — actually happen, with a live model on the other
end rather than a mock that always answers instantly.

Every test is wrapped in a bounded wait: a hang here is exactly the failure
mode being probed for, and an unbounded await would just make the suite hang
too instead of reporting it.

One of these tests did find something, and it's fixed now:
`test_close_while_a_command_is_in_flight_does_not_hang` used to fail against
prod, confirmed, not flaky — `close()`'s `_destroy_handle()` cleared
`self._pending_completions` without ever settling the `asyncio.Future` each
`_async_op` call was awaiting, so an operation still in flight when `close()`
ran left its caller hung for the life of the process. Fixed in #139 by
settling each pending future with `AbortedError` on the way through,
mirroring the pattern the C++ SDK's `destroy_handle()` already had
(`client_impl.hpp`) — the same fix the `sdk-from-ffi` skill's "Teardown
settles what it cannot cancel" section describes. `disconnect()` never had
this problem — an in-flight `send_command()` racing it settles on its own
(see `test_disconnect_while_a_command_is_in_flight_settles_it`).
"""

from __future__ import annotations

import asyncio

import pytest

from reactor_sdk import Reactor, ReactorStatus


async def test_many_concurrent_send_commands_all_resolve_without_cross_talk(
    reactor: Reactor,
) -> None:
    # Completion trampolines are keyed by id(fn) (client.py's _async_op) —
    # this is what would catch two in-flight calls' replies getting swapped.
    intensities = [round(0.05 * i, 2) for i in range(1, 16)]
    calls = [reactor.send_command("set_intensity", {"intensity": v}) for v in intensities]
    results = await asyncio.wait_for(asyncio.gather(*calls), timeout=20.0)
    assert all(r is None for r in results)


async def test_cancelling_an_in_flight_command_does_not_corrupt_the_client(
    reactor: Reactor,
) -> None:
    # _async_op's docstring: a cancelled await leaves the pending-completion
    # entry in place rather than freeing anything the library still holds a
    # pointer to — "a bounded leak until the call fires", not a crash. This
    # checks the client is still usable afterward, which a corrupted
    # trampoline table would not survive.
    with pytest.raises(asyncio.TimeoutError):
        await asyncio.wait_for(
            reactor.send_command("set_effect", {"effect": "grayscale"}), timeout=0.001
        )

    # The client must still work — a fresh command, unrelated to the
    # cancelled one, should complete normally.
    result = await asyncio.wait_for(
        reactor.send_command("set_effect", {"effect": "none"}), timeout=10.0
    )
    assert result is None


async def test_disconnect_while_a_command_is_in_flight_settles_it(reactor: Reactor) -> None:
    command = asyncio.ensure_future(reactor.send_command("set_effect", {"effect": "sepia"}))
    await asyncio.sleep(0)  # let it actually dispatch before racing disconnect against it
    await reactor.disconnect()

    # Either it completed just ahead of the disconnect, or the disconnect
    # settled it with an error — what must not happen is neither: a future
    # nothing ever resolves is the exact failure this suite exists to catch,
    # the same class of bug PR #136 fixed at the reactor-ffi layer.
    try:
        await asyncio.wait_for(asyncio.shield(command), timeout=10.0)
    except Exception:
        pass
    assert command.done()


async def test_rapid_connect_disconnect_connect_ends_up_ready_on_a_new_session(
    reactor_factory,
) -> None:
    r: Reactor = reactor_factory()
    await r.connect()
    first_session = r.session_id
    assert first_session

    await r.disconnect()
    assert r.status == ReactorStatus.DISCONNECTED

    await r.connect()
    assert r.status == ReactorStatus.READY
    assert r.session_id
    assert r.session_id != first_session


async def test_close_while_a_command_is_in_flight_does_not_hang(reactor_factory) -> None:
    r: Reactor = reactor_factory()
    await r.connect()

    command = asyncio.ensure_future(r.send_command("set_effect", {"effect": "blur"}))
    await asyncio.sleep(0)
    r.close()  # synchronous, unlike disconnect() — the abrupt teardown path

    try:
        await asyncio.wait_for(asyncio.shield(command), timeout=10.0)
    except Exception:
        pass
    assert command.done()
