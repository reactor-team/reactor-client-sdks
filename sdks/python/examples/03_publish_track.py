#!/usr/bin/env python3
"""03 · Publish a track, upload an image — sending media into a model.

This model transforms what you send it, and needs a conditioning image before it
will produce anything. The upload is therefore in this file rather than in
`common.py`: what a model demands is trivia, but `upload_file` is an SDK
capability, and hiding a capability in a helper is how it ends up untested.

    REACTOR_MODEL=morpheus-v4 uv run python examples/03_publish_track.py --image ref.png

The command that takes the image differs per model; override it with
REACTOR_IMAGE_COMMAND / REACTOR_IMAGE_PARAM when it is not `set_image` /
`image`.
"""

from __future__ import annotations

import argparse
import asyncio
import os
import time

import common

from reactor_sdk import Reactor

WIDTH, HEIGHT, FPS = 512, 512, 15

# Unconfirmed for morpheus-v4 — see the open question on REA-5322. Overridable
# so a wrong default here costs a flag, not a patch.
IMAGE_COMMAND = os.environ.get("REACTOR_IMAGE_COMMAND", "set_image")
IMAGE_PARAM = os.environ.get("REACTOR_IMAGE_PARAM", "image")


def flags(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--image", required=True, help="conditioning image to upload")


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

    async with reactor:
        await reactor.connect()
        print(f"session: {reactor.session_id}")

        # Uploading needs a ready session: the bytes go to the session's own
        # object store, and the runtime is told about them on the control channel.
        # What comes back is a reference to pass in a command, not the bytes again.
        uploaded = await reactor.upload_file(args.image)
        print(f"uploaded: {uploaded.name} ({uploaded.size} bytes) as {uploaded.upload_id}")
        await reactor.send_command(IMAGE_COMMAND, {IMAGE_PARAM: uploaded})

        # The input slot, by shape again: the model declares one sendonly video
        # track, and publishing is what puts a sender behind it. Pushing frames
        # before that raises rather than dropping them silently.
        source = reactor.tracks.with_kind("video").with_direction("sendonly").one()
        print(f"publishing: {source.name}")
        await source.publish()

        output = reactor.tracks.with_kind("video").with_direction("recvonly").one()
        received = 0

        @output.on_raw_frame
        def count(*_: object) -> None:
            nonlocal received
            received += 1

        sent = 0
        deadline = time.monotonic() + args.seconds
        while time.monotonic() < deadline:
            source.push_frame(common.frame(sent, WIDTH, HEIGHT), width=WIDTH, height=HEIGHT)
            sent += 1
            await asyncio.sleep(1 / FPS)

        source.unpublish()
        print(f"frames sent: {sent}")
        print(f"frames received: {received}")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("interrupted")
