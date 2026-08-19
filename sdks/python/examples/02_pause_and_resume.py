#!/usr/bin/env python3
"""02 · Pause and resume — stop a track, then start it again.

Baseline plus `track.pause()` and `track.resume()`. Frames are counted per phase,
so the middle count being zero is the whole point.

    uv run python examples/02_pause_and_resume.py --seconds 6

Docs:
  Tracks       https://docs.reactor.inc/concepts/tracks
  Track (API)  https://docs.reactor.inc/sdk-reference/python/track
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

    async with reactor:
        await reactor.connect()
        print(f"session: {reactor.session_id}")
        await common.bootstrap(reactor, args.model)

        output = reactor.tracks.with_kind("video").with_direction("recvonly").one()
        print(f"track: {output.name}")

        frames = 0

        @output.on_raw_frame
        def count(*_: object) -> None:
            nonlocal frames
            frames += 1

        async def phase(label: str) -> int:
            nonlocal frames
            frames = 0
            await asyncio.sleep(args.seconds)
            print(f"frames {label}: {frames}")
            return frames

        await phase("receiving")

        # Pausing sets the receiver inactive and tells the runtime to stop
        # producing, so this is not just a local mute: nothing is generated,
        # nothing is sent, nothing is billed.
        #
        # Distinct from any `pause` *command* a model happens to expose (Helios
        # has one, which suspends generation between chunks — see its schema at
        # https://docs.reactor.inc/model-api-reference/helios/schema). This is
        # the transport-level control every model has, whatever it calls its own.
        await output.pause()
        print(f"paused: {output.paused}")
        during_pause = await phase("while paused")

        await output.resume()
        print(f"paused: {output.paused}")
        await phase("after resume")

        if during_pause:
            print(f"warning: {during_pause} frames arrived while paused")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("interrupted")
