#!/usr/bin/env python3
"""03 · Publish a track — sending media into a model.

sana-streaming transforms what you stream at it: publish its `camera` input
track, push frames in, and the edited version comes back on the output track.
Publishing is what puts a sender behind the slot — pushing a frame before that
raises rather than dropping it silently.

    uv run python examples/03_publish_track.py --seconds 10

Frames go out tagged. It costs one argument and it is the only way to match a
frame you sent against whatever comes back — see 06 for the reading side.

Docs:
  Input tracks    https://docs.reactor.inc/concepts/tracks#input-tracks-app-to-model
  Frame metadata  https://docs.reactor.inc/concepts/frame-metadata
  SANA-Streaming  https://docs.reactor.inc/model-api-reference/sana-streaming/overview
  Its commands    https://docs.reactor.inc/model-api-reference/sana-streaming/schema
"""

from __future__ import annotations

import asyncio
import time

import common

from reactor_sdk import Reactor

# The model takes the client stream at whatever size it is sent.
WIDTH, HEIGHT, FPS = 640, 360, 15


async def main() -> None:
    args = common.parse(__doc__, default_model="sana-streaming")

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

    # `prompt_accepted`, `generation_started` and `command_error` arrive here — a
    # rejected command is a message, not a failed call, so a client that ignores
    # messages sees nothing wrong.
    # https://docs.reactor.inc/concepts/commands-and-messages
    @reactor.on_message
    def message(msg: dict) -> None:
        print(f"message: {msg}")

    async with reactor:
        await reactor.connect()
        print(f"session: {reactor.session_id}")

        # The input slot, found by shape rather than by name: this model declares
        # one sendonly video track, whatever it happens to call it.
        source = reactor.tracks.with_kind("video").with_direction("sendonly").one()
        print(f"publishing: {source.name}")
        await source.publish()

        # Publish first, then tell the model to begin: it transforms the live
        # track, so starting before there is a sender to read from buys nothing.
        await common.bootstrap(reactor, args.model)

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
