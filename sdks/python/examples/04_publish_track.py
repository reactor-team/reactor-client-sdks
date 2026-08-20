#!/usr/bin/env python3
"""04 · Publish a track — sending media into a model.

X2 edits what you stream at it: publish its `source` track, push frames, and the
re-rendered result comes back on `main_video`. Publishing is what puts a sender
behind the slot — pushing before it raises.

Frames go out tagged with `user_data`; example 07 reads the trailer.

    uv run python examples/04_publish_track.py

Docs: https://docs.reactor.inc/concepts/tracks#input-tracks-app-to-model
      https://docs.reactor.inc/concepts/frame-metadata
      https://docs.reactor.inc/model-api-reference/x2/schema
"""

from __future__ import annotations

import asyncio
import os
import sys
import time

import display

from reactor_sdk import DEFAULT_API_URL, Reactor

API_KEY = os.environ.get("REACTOR_API_KEY")
MODEL = os.environ.get("REACTOR_MODEL", "xmax/x2")
SECONDS = float(os.environ.get("REACTOR_SECONDS", "15"))
SHOW = os.environ.get("REACTOR_SHOW") == "1"

# X2 generates once it has a non-empty prompt and frames to edit. A reference
# image (`set_reference_image`) and a drag pointer (`set_pointer`) steer it
# further; neither is needed to start.
PROMPT = "repaint the scene as a watercolour painting"
INPUT_TRACK = "source"
OUTPUT_TRACK = "main_video"
WIDTH, HEIGHT, FPS = 640, 360, 15


def frame(seq: int) -> bytes:
    """A solid BGRA frame whose colour follows `seq` — an encoder fed identical
    frames sends almost nothing."""
    return bytes([seq * 7 % 256, seq * 13 % 256, seq * 29 % 256, 255]) * (WIDTH * HEIGHT)


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

    @reactor.on_message
    def message(msg: dict) -> None:
        print(f"message: {msg}")

    async with reactor:
        await reactor.connect()
        print(f"session: {reactor.session_id}")

        source = reactor.track(INPUT_TRACK)
        await source.publish()
        print(f"publishing: {source.name}")

        # Publish first: the model edits a live track, so a prompt with no sender
        # behind the slot buys nothing. There is no `start` — X2 begins once it
        # has both a prompt and frames.
        print(f"set_prompt -> {await reactor.send_command('set_prompt', {'prompt': PROMPT})}")

        output = reactor.track(OUTPUT_TRACK)
        received = 0
        # Two tiles: what goes out, and what comes back.
        window = display.window(f"{MODEL} · sent | received", tiles=2, enabled=SHOW)

        @output.on_raw_frame
        def count(data: bytes, width: int, height: int, *_: object) -> None:
            nonlocal received
            received += 1
            window.submit(data, width, height, tile=1)

        sent = 0
        deadline = time.monotonic() + SECONDS
        while time.monotonic() < deadline and not window.closed:
            outgoing = frame(sent)
            source.push_frame(
                outgoing, width=WIDTH, height=HEIGHT, user_data=f"seq={sent}".encode()
            )
            window.submit(outgoing, WIDTH, HEIGHT, tile=0)
            sent += 1
            # Paces the loop, and draws while it waits.
            await window.hold(1 / FPS)

        source.unpublish()
        print(f"frames sent: {sent}")
        print(f"frames received: {received}")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("interrupted")
