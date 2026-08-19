#!/usr/bin/env python3
"""05 · Multi-connection and session adoption — two clients, one session.

The first client creates the session; the second joins the one that already
exists by passing its id. Both receive the same stream. Only the creator ends
the session on disconnect — an adopting client tears down its own transport and
leaves the session alone, which is what makes this safe to do from a browser tab
that may close at any moment.

    uv run python examples/05_multi_connection.py --seconds 10

A backend that created the session server-side is the same story: hand the id to
whoever should watch, and they adopt it exactly like this.

Docs:
  Multiple connections per session
      https://docs.reactor.inc/concepts/sessions#multiple-connections-per-session
  Adopting an existing session
      https://docs.reactor.inc/concepts/sessions#adopting-an-existing-session
  Who owns the session
      https://docs.reactor.inc/concepts/sessions#who-owns-the-session
"""

from __future__ import annotations

import asyncio
from collections.abc import Callable

import common

from reactor_sdk import Reactor


def client(args, label: str) -> Reactor:
    reactor = Reactor(
        model_name=args.model,
        api_key=args.api_key,
        jwt=args.jwt,
        api_url=args.api_url,
        local=args.local,
    )

    @reactor.on_status
    def status(new: str) -> None:
        print(f"[{label}] status: {new}")

    @reactor.on_error
    def failed(error: Exception) -> None:
        print(f"[{label}] error: {error}")

    return reactor


def count_frames(reactor: Reactor, label: str, window, tile: int) -> Callable[[], int]:
    output = reactor.tracks.with_kind("video").with_direction("recvonly").one()
    frames = 0

    @output.on_raw_frame
    def count(data: bytes, width: int, height: int, *_: object) -> None:
        nonlocal frames
        frames += 1
        window.submit(data, width, height, tile=tile)

    print(f"[{label}] track: {output.name}")
    return lambda: frames


async def main() -> None:
    args = common.parse(__doc__)

    creator = client(args, "creator")
    joiner = client(args, "joiner")

    # One tile per client: the same session, arriving twice.
    window = common.display(args, f"{args.model} · creator | joiner", tiles=2)

    async with creator, joiner:
        await creator.connect()
        session = creator.session_id
        print(f"session: {session}")
        await common.bootstrap(creator, args.model)
        creator_frames = count_frames(creator, "creator", window, tile=0)

        # Same session, second transport. The id is the whole handoff: no
        # coordination between the two clients, and no second session created.
        await joiner.connect(session_id=session)
        print(f"[joiner] joined: {joiner.session_id}")
        joiner_frames = count_frames(joiner, "joiner", window, tile=1)

        await window.hold(args.seconds)
        print(f"frames creator: {creator_frames()}")
        print(f"frames joiner: {joiner_frames()}")

    print("both disconnected; the session ended with its creator")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("interrupted")
