"""Two clients, one session — example 05.

The first client creates the session; a second adopts it by id
(`connect(session_id=...)`). Only the creator ends the session on disconnect —
that asymmetry is the point of adoption (a joiner's tab can close at any
moment without taking the session down), and is what this file actually
checks, not just that adoption connects.

Each client mints its own token from the API key, the way
`sdks/python/examples/05_multi_connection.py` builds them. The joiner's token
is bound to the creator's session (`client.py`'s `_resolve_token`): a scoped
token reaches only the sessions it created or was bound to, so a joiner with a
token not bound to the session would get a 403 on its first session call.
"""

from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable

from conftest import (
    assert_dominant_color,
    paced_connect,
    solid_rgb_frame,
    wait_until,
)

from reactor_sdk import Reactor, ReactorStatus


async def _shared_pair(
    reactor_factory: Callable[..., Reactor],
) -> tuple[Reactor, Callable[[], Awaitable[Reactor]]]:
    """A connected creator, and a callable that connects a joiner to it.

    Each client mints its own token from the suite's API key. In local mode,
    neither client mints a token, because local mode skips auth.
    """
    creator: Reactor = reactor_factory()
    await paced_connect(creator)

    async def join() -> Reactor:
        joiner: Reactor = reactor_factory()
        await paced_connect(joiner, session_id=creator.session_id)
        return joiner

    return creator, join


async def test_joiner_adopts_the_same_session(reactor_factory) -> None:
    creator, join = await _shared_pair(reactor_factory)
    joiner = await join()

    assert joiner.session_id == creator.session_id
    assert joiner.status == ReactorStatus.READY


async def test_joiner_observes_state_the_creator_set(reactor_factory) -> None:
    creator, join = await _shared_pair(reactor_factory)
    webcam = await creator.publish_track("webcam")

    color = (60, 150, 20)
    frame = solid_rgb_frame(64, 64, color)
    # Long enough to comfortably outlast the joiner's own connect handshake
    # (a second WebRTC negotiation on top of the creator's) before the
    # frame-count wait below even starts.
    pump = asyncio.ensure_future(_pump(webcam, frame, seconds=15.0))
    try:
        await creator.send_command("set_effect", {"effect": "invert"})
        await creator.send_command("set_intensity", {"intensity": 1.0})

        joiner = await join()

        # The effect is session (model-instance) state, set before the
        # joiner even connected — a fresh session would default to "none"
        # (see echo_model.py's load()), so seeing "invert" on the joiner's
        # own view of main_video is what proves this is the same session
        # rather than a second one that happens to share an id.
        received: list = []
        joiner.track("main_video").on_frame(lambda f: received.append(f))
        await wait_until(lambda: len(received) >= 3, timeout=10.0)
        assert_dominant_color(received[-1], tuple(255 - c for c in color))
    finally:
        pump.cancel()
        webcam.unpublish()


async def test_creator_disconnect_ends_the_session_for_the_joiner(reactor_factory) -> None:
    creator, join = await _shared_pair(reactor_factory)
    joiner = await join()

    await creator.disconnect()

    await wait_until(lambda: joiner.status != ReactorStatus.READY, timeout=10.0)


async def test_joiner_disconnect_leaves_the_session_running(reactor_factory) -> None:
    creator, join = await _shared_pair(reactor_factory)
    joiner = await join()

    await joiner.disconnect()
    await asyncio.sleep(1.0)

    assert creator.status == ReactorStatus.READY
    # The session is still alive server-side under the creator — a command
    # actually round-tripping is the proof, not just the cached status.
    await creator.send_command("set_effect", {"effect": "none"})


async def _pump(track, frame, *, seconds: float) -> None:
    end = asyncio.get_running_loop().time() + seconds
    while asyncio.get_running_loop().time() < end:
        track.push_frame(frame)
        await asyncio.sleep(1 / 30)
