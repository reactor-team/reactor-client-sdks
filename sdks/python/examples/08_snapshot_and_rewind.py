#!/usr/bin/env python3
"""08 · Snapshot and rewind — what `send_command` actually hands back.

Every earlier example either fires a command without waiting on its reply or
reads only its bare `type`. `save_snapshot`, `list_snapshots`, and `rewind`
each answer with a `data` payload worth reading: the assigned index, the full
snapshot list, where a rewind landed.

    export REACTOR_API_KEY=rk_...
    uv run python examples/08_snapshot_and_rewind.py

    REACTOR_SHOW=1    show the video in a window (needs pygame)
    REACTOR_LOCAL=1   use a local runtime instead of the cloud

Docs: https://docs.reactor.inc/concepts/commands-and-messages
      https://docs.reactor.inc/model-api-reference/helios/schema
"""

from __future__ import annotations

import asyncio
import os
import sys

import display

from reactor_sdk import DEFAULT_API_URL, Reactor

API_KEY = os.environ.get("REACTOR_API_KEY")
MODEL = os.environ.get("REACTOR_MODEL", "reactor/helios")
SHOW = os.environ.get("REACTOR_SHOW") == "1"

PROMPT = "a lighthouse in a storm"
OUTPUT_TRACK = "main_video"


async def main() -> None:
    if not API_KEY and os.environ.get("REACTOR_LOCAL") != "1":
        sys.exit("set REACTOR_API_KEY — https://www.reactor.inc/account/api-keys")

    reactor = Reactor(
        model_name=MODEL,
        api_key=API_KEY,
        api_url=os.environ.get("REACTOR_API_URL", DEFAULT_API_URL),
        local=os.environ.get("REACTOR_LOCAL") == "1",
    )

    @reactor.on_status
    def status(new: str) -> None:
        print(f"status: {new}")

    @reactor.on_error
    def failed(error: Exception) -> None:
        print(f"error: {error}")

    # A broadcast, not a reply — the correlated replies below arrive as each
    # send_command() call's own return value instead.
    @reactor.on_message
    def message(msg: dict) -> None:
        print(f"message: {msg}")

    async with reactor:
        await reactor.connect()
        print(f"session: {reactor.session_id}")
        await reactor.send_command("set_prompt", {"prompt": PROMPT})
        await reactor.send_command("start", {})

        output = reactor.track(OUTPUT_TRACK)
        frames = 0
        window = display.window(f"{MODEL} · {OUTPUT_TRACK}", enabled=SHOW)

        @output.on_raw_frame
        def count(data: bytes, width: int, height: int, *_: object) -> None:
            nonlocal frames
            frames += 1
            window.submit(data, width, height)

        # `save_snapshot` captures the current world state — nothing to save
        # until a frame has actually been generated.
        while frames == 0:
            await window.hold(0.5)

        first = await reactor.send_command("save_snapshot", {"label": "before the wave"})
        print(f"save_snapshot -> {first}")
        await window.hold(1)
        second = await reactor.send_command("save_snapshot", {"label": "after the wave"})
        print(f"save_snapshot -> {second}")

        listing = await reactor.send_command("list_snapshots", {})
        print(f"list_snapshots -> {listing}")
        data = (listing or {}).get("data") or {}
        snapshots = data.get("snapshots", [])

        if snapshots:
            target = snapshots[0]["snapshot_index"]
            rewound = await reactor.send_command("rewind", {"snapshot_index": target})
            print(f"rewind -> {rewound}")
        else:
            print("no snapshots to rewind to")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("interrupted")
