#!/usr/bin/env python3
"""07 · Frame metadata — what arrives alongside the pixels.

Every decoded frame can carry a trailer the sender attached: a frame id, a
wall-clock timestamp, and arbitrary bytes of its own. `on_raw_frame` hands all of
it over; a frame with no trailer arrives with zeros and empty bytes instead.

    uv run python examples/07_frame_metadata.py --seconds 10

The timestamps are what makes this more than trivia: they are the sender's clock,
so they measure the model's own pacing rather than the network's — two frames
that arrive together were not necessarily produced together.

`user_data` is empty unless the model puts it there: a model that derives its
output from your frame can mirror the bytes back, and none of the published ones
does today. 04 shows the sending side of the same field.

Native-only: a browser hands JS a MediaStreamTrack with no per-frame hook, so
there is no JS counterpart to this example.

Docs:
  Frame metadata  https://docs.reactor.inc/concepts/frame-metadata
  Track (API)     https://docs.reactor.inc/sdk-reference/python/track
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

        window = common.display(args, f"{args.model} · {output.name}")

        with_trailer = 0
        without_trailer = 0
        tagged = 0
        previous_timestamp: int | None = None
        gaps: list[int] = []

        @output.on_raw_frame
        def inspect(
            data: bytes,
            width: int,
            height: int,
            frame_id: int,
            timestamp_us: int,
            user_data: bytes,
        ) -> None:
            nonlocal with_trailer, without_trailer, tagged, previous_timestamp
            window.submit(data, width, height)
            # No trailer at all looks like this: the ids and the timestamp are
            # zero and there are no bytes. Worth checking before trusting either.
            if not frame_id and not timestamp_us and not user_data:
                without_trailer += 1
                return

            with_trailer += 1
            if user_data:
                tagged += 1
            if previous_timestamp is not None and timestamp_us > previous_timestamp:
                gaps.append(timestamp_us - previous_timestamp)
            previous_timestamp = timestamp_us

            if with_trailer <= 3:
                print(
                    f"frame {frame_id}: {width}x{height} "
                    f"at {timestamp_us} us, user_data={user_data!r}"
                )

        await window.hold(args.seconds)

    print(f"frames with a trailer: {with_trailer}")
    print(f"frames without one: {without_trailer}")
    print(f"frames carrying user_data: {tagged}")
    if gaps:
        ordered = sorted(gaps)
        median_us = ordered[len(ordered) // 2]
        print(f"sender cadence: median {median_us / 1000:.1f} ms between frames")
        print(f"                {1_000_000 / median_us:.1f} fps as the sender timed it")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("interrupted")
