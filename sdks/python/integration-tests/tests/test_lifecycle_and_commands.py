"""Connect, status, commands, schema — examples 01 and 08, automated.

One connected session per test, walked through the surface a caller actually
uses on it, the same "one connect/disconnect pair per concern" shape as the
JS suite's `lifecycle-and-commands.spec.ts`: real sessions against
`reactor/echo` aren't free or instant.
"""

from __future__ import annotations

import asyncio

import pytest
from conftest import paced_connect, wait_until

from reactor_sdk import DisconnectedError, Reactor, ReactorStatus


async def test_connect_walks_disconnected_to_ready_and_getters_agree(reactor_factory) -> None:
    r = reactor_factory()
    statuses: list[str] = []
    r.on_status(lambda s: statuses.append(s.value))

    assert r.status == ReactorStatus.DISCONNECTED
    assert r.session_id is None

    await paced_connect(r)

    assert r.status == ReactorStatus.READY
    assert r.session_id

    # connect()'s own completion and the "status_changed" event it triggers
    # are dispatched from native code independently (client.py's connect()
    # docstring), with no ordering promise between them — connect() can
    # return before the "ready" on_status callback has actually run. Poll
    # briefly for it to catch up rather than asserting on statuses[-1] the
    # instant connect() resolves.
    await wait_until(lambda: statuses[-1:] == ["ready"], timeout=2.0)

    # "connecting" and "waiting" may repeat under retry, but the sequence must
    # never go backwards and must end on "ready" — checked by rank, not just
    # first/last value, so a status silently skipped or reordered still fails
    # this. Same check as the JS suite's equivalent assertion.
    rank = {"disconnected": 0, "connecting": 1, "waiting": 2, "ready": 3}
    assert statuses[0] == "connecting"
    assert statuses[-1] == "ready"
    assert "waiting" in statuses
    for prev, cur in zip(statuses, statuses[1:]):
        assert rank[cur] >= rank[prev], statuses


async def test_send_command_round_trip_and_message_event(reactor: Reactor) -> None:
    messages: list[dict] = []
    reactor.on_message(messages.append)

    # set_effect acknowledges with no data (the handler returns nothing) and
    # separately sends EffectChanged as an application message — two
    # different channels, both exercised here.
    result = await reactor.send_command("set_effect", {"effect": "invert"})
    assert result is None

    # intensity isn't otherwise touched by this test; set it explicitly so the
    # assertion below is about the message round trip, not about whatever a
    # fresh session's own default happens to be.
    await reactor.send_command("set_intensity", {"intensity": 1.0})

    # The message may arrive a beat after the ack; give it a moment rather
    # than asserting immediately.
    for _ in range(50):
        if len(messages) >= 2:
            break
        await asyncio.sleep(0.1)
    assert messages, "no EffectChanged message arrived"
    # on_message delivers the full envelope — {"type", "data"} — not just the
    # message's own fields.
    assert messages[-1] == {
        "type": "effect_changed",
        "data": {"effect": "invert", "intensity": 1.0},
    }


async def test_get_status_returns_correlated_data(reactor: Reactor) -> None:
    """echo 1.8.0+ (REA-5973): the first echo command whose reply actually
    carries data, rather than a bodyless ack — every other command
    (set_effect, set_intensity, set_overlay_image) returns None."""
    result = await reactor.send_command("get_status", {})

    assert result is not None, "get_status returned no data — still an ack-only command?"
    assert result == {"type": "status", "data": {"effect": "none", "intensity": 1.0}}

    await reactor.send_command("set_effect", {"effect": "invert"})
    await reactor.send_command("set_intensity", {"intensity": 0.42})

    updated = await reactor.send_command("get_status", {})
    assert updated == {"type": "status", "data": {"effect": "invert", "intensity": 0.42}}


async def test_send_command_rejects_an_out_of_range_argument(reactor: Reactor) -> None:
    # set_intensity declares ge=0.0, le=1.0 — the model itself refuses this,
    # not the SDK, so the point of this test is that the refusal reaches the
    # caller as an exception rather than as a silently-accepted command.
    with pytest.raises(Exception):
        await reactor.send_command("set_intensity", {"intensity": 5.0})


async def test_request_schema_describes_echos_commands(reactor: Reactor) -> None:
    schema = await reactor.request_schema()

    paths = schema.get("paths", schema)  # tolerate either an OpenAPI doc or a flat map
    text = str(paths)
    for command in ("set_effect", "set_intensity", "set_overlay_image", "get_status"):
        assert command in text, f"{command!r} missing from the schema: {schema!r}"


async def test_reconnect_keeps_the_same_session(reactor: Reactor) -> None:
    session_id = reactor.session_id
    assert session_id is not None

    await reactor.reconnect()

    assert reactor.status == ReactorStatus.READY
    assert reactor.session_id == session_id


async def test_disconnect_ends_the_session_and_further_commands_refuse(reactor: Reactor) -> None:
    await reactor.disconnect()
    assert reactor.status == ReactorStatus.DISCONNECTED

    with pytest.raises((DisconnectedError, Exception)):
        await reactor.send_command("set_effect", {"effect": "none"})


async def test_disconnect_is_idempotent(reactor: Reactor) -> None:
    # client.py's disconnect() short-circuits when there is no handle — but
    # the first real disconnect tears the handle down too, and the second
    # call is what actually proves that path doesn't raise or hang.
    await reactor.disconnect()
    await reactor.disconnect()
    assert reactor.status == ReactorStatus.DISCONNECTED


async def test_two_clients_do_not_cross_talk(reactor_factory) -> None:
    # Two independent sessions on the same model — not adoption (see
    # test_multi_connection.py for that) — proving one client's commands
    # don't leak into the other's session.
    a = reactor_factory()
    b = reactor_factory()
    await asyncio.gather(paced_connect(a), paced_connect(b))
    assert a.session_id != b.session_id

    await a.send_command("set_effect", {"effect": "grayscale"})
    await b.send_command("set_effect", {"effect": "invert"})

    schema_a = await a.request_schema()
    schema_b = await b.request_schema()
    # Both describe the same model, so this only proves neither call raised
    # cross-session — the real assertion is that each session's own state
    # (exercised in test_tracks_and_frames.py's effect assertions) stayed
    # session-local.
    assert schema_a == schema_b
