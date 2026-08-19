#!/usr/bin/env python3
"""01 · Connect, prompt, receive — the baseline every other example builds on.

Creates a client, connects, sends the one command the model needs, reads the
reply back, and counts frames off the output track.

    uv run python examples/01_connect_and_receive.py --seconds 15

Options come from flags or from REACTOR_MODEL / REACTOR_API_URL /
REACTOR_API_KEY / REACTOR_JWT / REACTOR_LOCAL.

Docs:
  Using the SDK       https://docs.reactor.inc/sdk-reference/using-the-sdk
  Sessions            https://docs.reactor.inc/concepts/sessions
  Commands & messages https://docs.reactor.inc/concepts/commands-and-messages
  Helios              https://docs.reactor.inc/model-api-reference/helios/overview
"""

from __future__ import annotations

import asyncio

import common

from reactor_sdk import Reactor


async def main() -> None:
    args = common.parse(__doc__)

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

    # Where a model's own answers arrive. A command's return value is its
    # correlated reply, and most handlers return nothing — what they emit
    # instead (`prompt_accepted`, `generation_started`, …) comes through here.
    # https://docs.reactor.inc/concepts/commands-and-messages
    @reactor.on_message
    def message(msg: dict) -> None:
        print(f"message: {msg}")

    # The context manager disconnects and releases the native client on the way
    # out, including when the body raises — which also ends the session, so a
    # crash does not leave one running on the platform.
    async with reactor:
        await reactor.connect()
        print(f"session: {reactor.session_id}")

        # What this model needs before it emits anything. For Helios that is a
        # prompt and then `start`; a model that begins on its own needs neither.
        await common.bootstrap(reactor, args.model)

        # Named by shape rather than by string: whatever this model calls its
        # video output, there is one recvonly video track and this is it.
        # https://docs.reactor.inc/concepts/tracks#output-tracks-model-to-app
        output = reactor.tracks.with_kind("video").with_direction("recvonly").one()
        print(f"track: {output.name}")

        frames = 0
        window = common.display(args, f"{args.model} · {output.name}")

        # on_raw_frame hands over the bytes WebRTC decoded (BGRA) and needs
        # nothing installed. on_frame is the same frames as a numpy array.
        @output.on_raw_frame
        def count(data: bytes, width: int, height: int, *_: object) -> None:
            nonlocal frames
            frames += 1
            if frames == 1:
                print(f"first frame: {width}x{height}")
            window.submit(data, width, height)

        # `--show` puts these on screen; without it this is a plain sleep.
        await window.hold(args.seconds)
        print(f"frames: {frames}")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("interrupted")
