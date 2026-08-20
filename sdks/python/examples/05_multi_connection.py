#!/usr/bin/env python3
"""05 · Multi-connection and session adoption — two clients, one session.

The first client creates the session; the second joins it by id. Only the creator
ends it on disconnect, which is what makes adoption safe from a tab that may
close at any moment. A backend-created session is the same story.

    uv run python examples/05_multi_connection.py

Docs: https://docs.reactor.inc/concepts/sessions#multiple-connections-per-session
      https://docs.reactor.inc/concepts/sessions#adopting-an-existing-session
      https://docs.reactor.inc/concepts/sessions#who-owns-the-session
"""

from __future__ import annotations

import asyncio
import os
import sys
from collections.abc import Callable

import display

from reactor_sdk import DEFAULT_API_URL, Reactor

API_KEY = os.environ.get("REACTOR_API_KEY")
MODEL = os.environ.get("REACTOR_MODEL", "reactor/helios")
SECONDS = float(os.environ.get("REACTOR_SECONDS", "10"))
SHOW = os.environ.get("REACTOR_SHOW") == "1"

PROMPT = "a forest at dawn, sunbeams through the canopy"
OUTPUT_TRACK = "main_video"


def client(label: str) -> Reactor:
    reactor = Reactor(
        model_name=MODEL,
        api_key=API_KEY,
        api_url=os.environ.get("REACTOR_API_URL", DEFAULT_API_URL),
        local=os.environ.get("REACTOR_LOCAL") == "1",
    )

    @reactor.on_status
    def status(new: str) -> None:
        print(f"[{label}] status: {new}")

    @reactor.on_error
    def failed(error: Exception) -> None:
        print(f"[{label}] error: {error}")

    return reactor


def count_frames(reactor: Reactor, window, tile: int) -> Callable[[], int]:
    frames = 0

    @reactor.track(OUTPUT_TRACK).on_raw_frame
    def count(data: bytes, width: int, height: int, *_: object) -> None:
        nonlocal frames
        frames += 1
        window.submit(data, width, height, tile=tile)

    return lambda: frames


async def main() -> None:
    if not API_KEY and os.environ.get("REACTOR_LOCAL") != "1":
        sys.exit("set REACTOR_API_KEY — https://www.reactor.inc/account/api-keys")

    creator, joiner = client("creator"), client("joiner")
    # One tile per client: the same session, arriving twice.
    window = display.window(f"{MODEL} · creator | joiner", tiles=2, enabled=SHOW)

    async with creator, joiner:
        await creator.connect()
        print(f"session: {creator.session_id}")
        await creator.send_command("set_prompt", {"prompt": PROMPT})
        await creator.send_command("start", {})
        creator_frames = count_frames(creator, window, tile=0)

        # The id is the whole handoff — no second session, no coordination.
        await joiner.connect(session_id=creator.session_id)
        joiner_frames = count_frames(joiner, window, tile=1)

        await window.hold(SECONDS)
        print(f"frames creator: {creator_frames()}")
        print(f"frames joiner: {joiner_frames()}")

    print("both disconnected; the session ended with its creator")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("interrupted")
