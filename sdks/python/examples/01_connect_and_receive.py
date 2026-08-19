#!/usr/bin/env python3
"""01 · Connect, prompt, receive — the baseline every other example builds on.

Creates a client, connects, sends the one command the model needs, reads the
reply back, and counts frames off the output track.

    uv run python examples/01_connect_and_receive.py --seconds 15

Options come from flags or from REACTOR_MODEL / REACTOR_API_URL /
REACTOR_API_KEY / REACTOR_JWT / REACTOR_LOCAL.
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

    # The context manager disconnects and releases the native client on the way
    # out, including when the body raises — which also ends the session, so a
    # crash does not leave one running on the platform.
    async with reactor:
        await reactor.connect()
        print(f"session: {reactor.session_id}")

        print(f"reply: {await common.bootstrap(reactor, args.model)}")

        # Named by shape rather than by string: whatever this model calls its
        # video output, there is one recvonly video track and this is it.
        output = reactor.tracks.with_kind("video").with_direction("recvonly").one()
        print(f"track: {output.name}")

        frames = 0

        # on_raw_frame hands over the bytes WebRTC decoded (BGRA) and needs
        # nothing installed. on_frame is the same frames as a numpy array.
        @output.on_raw_frame
        def count(data: bytes, width: int, height: int, *_: object) -> None:
            nonlocal frames
            frames += 1
            if frames == 1:
                print(f"first frame: {width}x{height}")

        await asyncio.sleep(args.seconds)
        print(f"frames: {frames}")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("interrupted")
