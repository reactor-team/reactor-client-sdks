#!/usr/bin/env python3
"""01 · Connect, prompt, receive — the baseline the other examples build on.

    export REACTOR_API_KEY=rk_...
    uv run python examples/01_connect_and_receive.py

    REACTOR_SHOW=1    show the video in a window (needs pygame)
    REACTOR_LOCAL=1   use a local runtime instead of the cloud

Docs: https://docs.reactor.inc/sdk-reference/using-the-sdk
      https://docs.reactor.inc/concepts/sessions
      https://docs.reactor.inc/concepts/commands-and-messages
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
SECONDS = float(os.environ.get("REACTOR_SECONDS", "15"))
SHOW = os.environ.get("REACTOR_SHOW") == "1"

# Helios emits nothing until `start`, and `start` refuses without a prompt.
PROMPT = "a forest at dawn, sunbeams through the canopy"
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

    # Most handlers return nothing and answer with a message instead.
    @reactor.on_message
    def message(msg: dict) -> None:
        print(f"message: {msg}")

    # Disconnects and releases the client on the way out, even if the body raises.
    async with reactor:
        await reactor.connect()
        print(f"session: {reactor.session_id}")

        print(f"set_prompt -> {await reactor.send_command('set_prompt', {'prompt': PROMPT})}")
        print(f"start -> {await reactor.send_command('start', {})}")

        # By name, as the model's schema declares it. `reactor.tracks` lists them.
        output = reactor.track(OUTPUT_TRACK)
        frames = 0
        window = display.window(f"{MODEL} · {OUTPUT_TRACK}", enabled=SHOW)

        # Decoded BGRA bytes, no numpy. `on_frame` gives the same frames as arrays.
        @output.on_raw_frame
        def count(data: bytes, width: int, height: int, *_: object) -> None:
            nonlocal frames
            frames += 1
            if frames == 1:
                print(f"first frame: {width}x{height}")
            window.submit(data, width, height)

        # Sleeps, and draws while it waits when there is a window.
        await window.hold(SECONDS)
        print(f"frames: {frames}")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("interrupted")
