#!/usr/bin/env python3
"""06 · Record a clip — capture what just happened, and download it.

Baseline plus `request_clip`, which asks the runtime for the last N seconds of
the session and answers with an HLS manifest. `download_clip` then fetches the
segments and writes one file.

    uv run python examples/06_record_clip.py --clip 5 --out clip.ts

`request_recording()` is the same call for the whole session instead of a window.

What lands on disk is interleaved MPEG-TS, which is why the default is `.ts`:
ffplay, VLC and mpv play it as-is, and `ffmpeg -i clip.ts -c copy clip.mp4`
remuxes it if that container is what you need. Naming it `.mp4` would be a lie
that some tools pick a demuxer from.

Docs:
  Recordings       https://docs.reactor.inc/concepts/recordings
  Clip (type)      https://docs.reactor.inc/sdk-reference/python/types#clip
"""

from __future__ import annotations

import argparse
import asyncio
from pathlib import Path

import common

from reactor_sdk import Reactor, download_clip


def flags(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--clip", type=float, default=5.0, help="seconds to capture (default: 5)")
    parser.add_argument("--out", default="clip.ts", help="where to write it (default: clip.ts)")


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
        await common.bootstrap(reactor, args.model)

        output = reactor.track(common.track_name(args.model, "output"))
        frames = 0
        window = common.display(args, f"{args.model} · {output.name}")

        @output.on_raw_frame
        def count(data: bytes, width: int, height: int, *_: object) -> None:
            nonlocal frames
            frames += 1
            window.submit(data, width, height)

        # There has to be something to capture: a clip is cut from what the
        # runtime already produced, so asking before any frames exist gets you an
        # empty window rather than an error.
        await window.hold(max(args.clip, args.seconds))
        print(f"frames: {frames}")

        clip = await reactor.request_clip(args.clip)
        print(f"clip: {clip.kind} {clip.playlist_url}")
        print(f"window: {clip.start_marker:.1f} → {clip.end_marker:.1f}")

        # The manifest is ready before every segment is: the runtime says when it
        # expects to be finished, and the download waits that out for you.
        # `reactor.download_clip(seconds, path)` does the request and this in one
        # call, for when the Clip itself is of no interest.
        await download_clip(clip, args.out)
        print(f"saved: {args.out} ({Path(args.out).stat().st_size // 1024} KB)")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("interrupted")
