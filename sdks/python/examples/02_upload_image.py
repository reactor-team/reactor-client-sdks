#!/usr/bin/env python3
"""02 · Upload an image — conditioning a model on a file.

`upload_file` puts bytes in the session's own store and hands back a `FileRef`.
Pass that reference into a command as a value and the model receives the file;
the SDK lifts it into the envelope's uploads section on the way out.

    uv run python examples/02_upload_image.py --image ref.png

Helios takes prompt and image together through `set_conditioning`, which its
schema recommends over separate `set_prompt` and `set_image` calls: the pair
cannot be split across the wire, so `start` can never observe a half-set session.
That race is the reason this command exists, and the reason this example uses it.

Docs:
  File uploads    https://docs.reactor.inc/concepts/file-uploads
  The FileRef     https://docs.reactor.inc/concepts/file-uploads#the-fileref-type
  Helios commands https://docs.reactor.inc/model-api-reference/helios/schema
"""

from __future__ import annotations

import argparse
import asyncio

import common

from reactor_sdk import Reactor


def flags(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--image", required=True, help="reference image to condition on")


async def main() -> None:
    args = common.parse(__doc__, flags)

    reactor = Reactor(
        model_name=args.model,
        api_key=args.api_key,
        jwt=args.jwt,
        api_url=args.api_url,
        local=args.local,
    )

    @reactor.on_status
    def status(new: str) -> None:
        print(f"status: {new}")

    @reactor.on_error
    def failed(error: Exception) -> None:
        print(f"error: {error}")

    # `image_accepted` says the model decoded the file and at what size;
    # `command_error` says it refused it. Either way it arrives as a message
    # rather than as the command's return value.
    @reactor.on_message
    def message(msg: dict) -> None:
        print(f"message: {msg}")

    async with reactor:
        await reactor.connect()
        print(f"session: {reactor.session_id}")

        # Only callable once the session is ready: the bytes go to that session's
        # store, and the runtime is told about them on the control channel. Name
        # and MIME type are inferred from the path unless given.
        uploaded = await reactor.upload_file(args.image)
        print(f"uploaded: {uploaded.name} {uploaded.mime_type} ({uploaded.size} bytes)")
        print(f"upload_id: {uploaded.upload_id}")

        # The FileRef as a plain argument value — no encoding, no second call.
        await reactor.send_command("set_conditioning", {"prompt": common.PROMPT, "image": uploaded})
        # Conditioning set atomically, so this cannot arrive too early.
        await reactor.send_command("start", {})

        output = reactor.tracks.with_kind("video").with_direction("recvonly").one()
        frames = 0

        @output.on_raw_frame
        def count(*_: object) -> None:
            nonlocal frames
            frames += 1

        await asyncio.sleep(args.seconds)
        print(f"frames: {frames}")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("interrupted")
