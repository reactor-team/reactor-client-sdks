#!/usr/bin/env python3
"""03 · Publish a track, upload an image — sending media into a model.

morpheus-v4 generates from a reference image plus whatever you stream at it, and
produces nothing until it has both. The upload is therefore in this file rather
than in `common.py`: what a model demands is trivia, but `upload_file` is an SDK
capability, and a capability hidden in a helper is one nobody sees.

    uv run python examples/03_publish_track.py --image ref.png

Frames are tagged on the way out, which costs one argument and is the only way to
match a frame you sent to whatever comes back — see 06 for the other half.

Docs:
  File uploads    https://docs.reactor.inc/concepts/file-uploads
  Input tracks    https://docs.reactor.inc/concepts/tracks#input-tracks-app-to-model
  Frame metadata  https://docs.reactor.inc/concepts/frame-metadata
  Your model's commands, whichever model you point this at:
                  https://docs.reactor.inc/model-api-reference/overview
"""

from __future__ import annotations

import argparse
import asyncio
import os
import time

import common

from reactor_sdk import Reactor

# morpheus-v4 letterboxes the reference image to its 1280x720 output, and takes
# the client stream at whatever size it is sent.
WIDTH, HEIGHT, FPS = 640, 360, 15

# morpheus-v4 declares `set_image(image: UploadedFile)`, the same shape the docs
# use in https://docs.reactor.inc/concepts/file-uploads#uploading-a-file. Another
# model will name this differently — overridable rather than hardcoded, so
# pointing this example at one costs a variable instead of a patch.
IMAGE_COMMAND = os.environ.get("REACTOR_IMAGE_COMMAND", "set_image")
IMAGE_PARAM = os.environ.get("REACTOR_IMAGE_PARAM", "image")


def flags(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--image", required=True, help="conditioning image to upload")


async def main() -> None:
    args = common.parse(__doc__, flags, default_model="morpheus-v4")

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

    # `image_accepted` and `conditions_ready` arrive here, and `command_error`
    # does too — a rejected upload (wrong type, undecodable) is a message rather
    # than a failed command, so a client that ignores messages sees nothing wrong.
    @reactor.on_message
    def message(msg: dict) -> None:
        print(f"message: {msg}")

    async with reactor:
        await reactor.connect()
        print(f"session: {reactor.session_id}")

        # Uploading needs a ready session: the bytes go to the session's own
        # object store, and the runtime is told about them on the control channel.
        # What comes back is a reference to pass in a command, not the bytes again.
        uploaded = await reactor.upload_file(args.image)
        print(f"uploaded: {uploaded.name} ({uploaded.size} bytes) as {uploaded.upload_id}")
        # The FileRef goes into the command as a value: the SDK lifts it out into
        # the envelope's uploads section, so the model receives the file itself
        # rather than a string it has to resolve.
        # https://docs.reactor.inc/concepts/file-uploads#the-fileref-type
        await reactor.send_command(IMAGE_COMMAND, {IMAGE_PARAM: uploaded})
        await common.bootstrap(reactor, args.model)

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
            # `user_data` rides along with the frame as its metadata. Anything
            # goes — a sequence number here, so a frame can be identified later.
            # https://docs.reactor.inc/concepts/frame-metadata
            source.push_frame(
                common.frame(sent, WIDTH, HEIGHT),
                width=WIDTH,
                height=HEIGHT,
                user_data=f"seq={sent}".encode(),
            )
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
