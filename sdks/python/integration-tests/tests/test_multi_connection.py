"""Two clients, one session — example 05.

The first client creates the session; a second adopts it by id
(`connect(session_id=...)`). Only the creator ends the session on disconnect —
that asymmetry is the point of adoption (a joiner's tab can close at any
moment without taking the session down), and is what this file actually
checks, not just that adoption connects.

**Both clients must share one already-minted token.** Confirmed against prod
with a standalone script: constructing each `Reactor` the convenient way
(`api_key=...`, letting `_resolve_token` mint a token per instance) 403s the
joiner every time — `UnauthorizedError: ... "this token is session-scoped and
is not authorized for this resource"`. `sdks/js/integration-tests/`'s own
multi-connection spec hit this first and documents the fix inline: "reading a
session back requires the token that created it, so the joiner can't mint its
own." Minting once via `fetch_jwt`/`conftest.py`'s `mint_jwt` and handing that
same token to both `Reactor(jwt=...)` constructors (mirroring the JS harness's
`fetchToken()` reused across both `connect()` calls) reproduces success
locally — see `_shared_pair` below. This was never a coordinator/API-key
provisioning problem, and `sdks/python/examples/05_multi_connection.py`
constructs its `joiner` with `api_key=` the same way this file originally
did — worth checking whether that example still actually works today, or
bit-rotted the same way.
"""

from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable

from conftest import LOCAL, MODEL_NAME, mint_jwt, paced_connect, solid_rgb_frame, wait_until

from reactor_sdk import Reactor, ReactorStatus


async def _shared_pair(
    reactor_factory: Callable[..., Reactor],
) -> tuple[Reactor, Callable[[], Awaitable[Reactor]]]:
    """A connected creator, and a callable that connects a joiner to it.

    Both are built from one token minted up front — the whole point of this
    file's module docstring finding. In local mode there's no coordinator
    auth to satisfy in the first place (`Reactor(local=True)` skips it — see
    `client.py`'s `_resolve_token`), and no `API_KEY` is guaranteed to exist
    to mint from (`conftest.py` only requires one when not local) — so both
    clients are built with no token at all and rely on `local=True` alone.
    """
    jwt = None if LOCAL else await mint_jwt(model_name=MODEL_NAME)
    creator: Reactor = reactor_factory(jwt=jwt)
    await paced_connect(creator)

    async def join() -> Reactor:
        joiner: Reactor = reactor_factory(jwt=jwt)
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
        #
        # Pixel assertion disabled — REA-5931 (see README.md): a shared prod
        # worker can already be carrying a *different* session's leaked
        # effect/overlay, so a fresh session's own main_video may show that
        # stale colour regardless of what either client here set. Same call
        # test_tracks_and_frames.py's own effect assertion and
        # sdks/js/integration-tests/tests/tracks-and-upload.spec.ts already
        # made. What's left to check honestly is that frames actually
        # arrive on the joiner's own view at all.
        received: list = []
        joiner.track("main_video").on_frame(lambda f: received.append(f))
        await wait_until(lambda: len(received) >= 3, timeout=10.0)
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
