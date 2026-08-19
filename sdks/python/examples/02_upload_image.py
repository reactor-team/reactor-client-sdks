#!/usr/bin/env python3
"""02 · Upload an image — conditioning a model on a file.

`upload_file` returns a `FileRef`; pass it into a command as a value and the
model receives the file. Helios takes prompt and image together through
`set_conditioning` so `start` cannot observe a half-set session.

    uv run python examples/02_upload_image.py ref.png

Docs: https://docs.reactor.inc/concepts/file-uploads
      https://docs.reactor.inc/model-api-reference/helios/schema
"""

from __future__ import annotations

import asyncio
import os
import sys

import display

from reactor_sdk import DEFAULT_API_URL, Reactor

API_KEY = os.environ.get("REACTOR_API_KEY")
MODEL = os.environ.get("REACTOR_MODEL", "helios")
SECONDS = float(os.environ.get("REACTOR_SECONDS", "15"))
SHOW = os.environ.get("REACTOR_SHOW") == "1"

PROMPT = "the same scene at night, lit by a campfire"
OUTPUT_TRACK = "main_video"


async def main() -> None:
    if len(sys.argv) != 2:
        sys.exit(f"usage: {sys.argv[0]} <image>")
    image = sys.argv[1]
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

    # A refused upload arrives as `command_error`, not as a failed call.
    @reactor.on_message
    def message(msg: dict) -> None:
        print(f"message: {msg}")

    async with reactor:
        await reactor.connect()
        print(f"session: {reactor.session_id}")

        # Needs a ready session: the bytes go to that session's store. Name and
        # MIME type are inferred from the path.
        uploaded = await reactor.upload_file(image)
        print(f"uploaded: {uploaded.name} {uploaded.mime_type} ({uploaded.size} bytes)")

        await reactor.send_command("set_conditioning", {"prompt": PROMPT, "image": uploaded})
        await reactor.send_command("start", {})

        output = reactor.track(OUTPUT_TRACK)
        frames = 0
        window = display.window(f"{MODEL} · {uploaded.name}", enabled=SHOW)

        @output.on_raw_frame
        def count(data: bytes, width: int, height: int, *_: object) -> None:
            nonlocal frames
            frames += 1
            window.submit(data, width, height)

        await window.hold(SECONDS)
        print(f"frames: {frames}")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("interrupted")
