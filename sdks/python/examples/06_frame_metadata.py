#!/usr/bin/env python3
"""06 · Frame metadata — tag a frame going out, find the tag coming back.

Every frame can carry bytes of your own alongside the pixels. Tag the frames you
push, read the tags off the frames you receive, and you can measure a real
round-trip through the model rather than guessing at it.

Needs a model that echoes metadata back, which is why this one runs against a
local runtime rather than a published model:

    REACTOR_LOCAL=1 REACTOR_MODEL=echo uv run python examples/06_frame_metadata.py

Native-only: a browser hands JS a MediaStreamTrack with no per-frame hook, so
there is no JS counterpart to this example.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import time

import common

from reactor_sdk import Reactor

WIDTH, HEIGHT, FPS = 320, 240, 30


def flags(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--frames", type=int, default=30, help="tagged frames to send")


async def main() -> int:
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

    sent: dict[int, float] = {}
    latencies: list[float] = []
    untagged = 0

    async with reactor:
        await reactor.connect()
        print(f"session: {reactor.session_id}")

        source = reactor.tracks.with_kind("video").with_direction("sendonly").one()
        output = reactor.tracks.with_kind("video").with_direction("recvonly").one()
        print(f"sending on: {source.name}, reading: {output.name}")

        # The sixth argument is the metadata the far end attached to this frame —
        # empty when there is none, which is what an untagged frame looks like.
        @output.on_raw_frame
        def match(
            data: bytes,
            width: int,
            height: int,
            frame_id: int,
            timestamp_us: int,
            user_data: bytes,
        ) -> None:
            nonlocal untagged
            if not user_data:
                untagged += 1
                return
            try:
                seq = int(json.loads(user_data)["seq"])
            except (ValueError, KeyError, TypeError):
                print(f"unrecognised tag: {user_data!r}")
                return
            start = sent.get(seq)
            if start is not None:
                latencies.append((time.monotonic() - start) * 1000)

        await source.publish()

        for seq in range(args.frames):
            sent[seq] = time.monotonic()
            source.push_frame(
                common.frame(seq, WIDTH, HEIGHT),
                width=WIDTH,
                height=HEIGHT,
                user_data=json.dumps({"seq": seq}).encode(),
            )
            await asyncio.sleep(1 / FPS)

        # Frames keep arriving after the last push, so wait for the tail instead
        # of judging the round-trip on whatever happened to have landed already.
        deadline = time.monotonic() + args.seconds
        while len(latencies) < args.frames and time.monotonic() < deadline:
            await asyncio.sleep(0.05)

        source.unpublish()

    print(f"tagged frames sent: {len(sent)}")
    print(f"tags returned: {len(latencies)}")
    print(f"frames without metadata: {untagged}")
    if not latencies:
        print("no tag came back — either the model does not echo metadata, or the")
        print("two peers did not negotiate the capability")
        return 1

    ordered = sorted(latencies)
    print(
        f"latency: min {ordered[0]:.0f} ms  median {ordered[len(ordered) // 2]:.0f} ms"
        f"  max {ordered[-1]:.0f} ms"
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(asyncio.run(main()))
    except KeyboardInterrupt:
        print("interrupted")
        raise SystemExit(130) from None
