#!/usr/bin/env python3
"""
Frame metadata example — read per-frame metadata from an incoming video track.

Reactor models can attach a FrameMetadata trailer to each encoded video frame.
The trailer carries:
  - frame_id     : monotonically-increasing counter set by the sender
  - timestamp_us : wall-clock time in microseconds (set by the sender)
  - user_data    : arbitrary application bytes (UTF-8 text, JSON, binary, …)

This example connects to a model, receives frames from a named track, and
prints the metadata fields for every frame that carries a trailer.  Frames
without metadata (trailer absent) are counted but not printed unless --verbose.

Usage:
    python examples/frame_metadata.py --track video_output

    # Also print raw frame dimensions and BGRA pixel stats
    python examples/frame_metadata.py --track video_output --verbose

    # Run for a custom duration
    python examples/frame_metadata.py --track video_output --duration 30

Environment variables (overridden by flags):
    REACTOR_API_URL, REACTOR_MODEL, REACTOR_JWT, REACTOR_LOCAL
"""

from __future__ import annotations

import argparse
import asyncio
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from .reactor_client import make_reactor


def _parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(
        description="Print per-frame metadata from a Reactor video track"
    )
    p.add_argument("--track", metavar="NAME", required=True,
                   help="Name of the recvonly video track to listen on")
    p.add_argument("--duration", metavar="SECS", type=float, default=30.0,
                   help="How long to run (default: 30 s)")
    p.add_argument("--verbose", action="store_true",
                   help="Also print dimensions and first-pixel BGRA for every frame")
    p.add_argument("--model", metavar="NAME")
    p.add_argument("--api-url", metavar="URL")
    p.add_argument("--jwt", metavar="TOKEN")
    p.add_argument("--local", action="store_true", default=None)
    return p.parse_args()


async def main() -> None:
    args = _parse_args()

    reactor = make_reactor(
        api_url=args.api_url,
        model_name=args.model,
        jwt=args.jwt,
        local=args.local if args.local else None,
    )
    reactor.on("error", lambda e: print(f"[error] {e}", file=sys.stderr))

    total_frames = 0
    frames_with_meta = 0
    t0 = time.monotonic()

    def on_frame(
        data: bytes,
        width: int,
        height: int,
        frame_id: int,
        timestamp_us: int,
        user_data: bytes,
    ) -> None:
        nonlocal total_frames, frames_with_meta
        total_frames += 1
        has_meta = frame_id != 0 or timestamp_us != 0 or len(user_data) > 0

        if has_meta:
            frames_with_meta += 1
            ud_str = user_data.decode(errors="replace") if user_data else ""
            print(
                f"frame #{frame_id:<6}  ts={timestamp_us / 1_000_000:.6f}s"
                f"  user_data={ud_str!r:<30}"
                + (f"  {width}×{height}" if args.verbose else "")
            )
        elif args.verbose:
            elapsed = time.monotonic() - t0
            print(
                f"frame #{total_frames:<6}  (no metadata)  "
                f"{width}×{height}  t={elapsed:.2f}s"
            )

    reactor.on("frame", on_frame)

    ready = asyncio.Event()
    reactor.on("status_changed", lambda s: ready.set() if s == "ready" else None)

    print("Connecting…", file=sys.stderr)
    await reactor.connect()
    await asyncio.wait_for(ready.wait(), timeout=60)
    print(
        f"Ready. Listening on '{args.track}' for {args.duration:.0f}s…",
        file=sys.stderr,
    )

    await asyncio.sleep(args.duration)

    elapsed = time.monotonic() - t0
    fps = total_frames / elapsed if elapsed > 0 else 0.0
    print(
        f"\n── Summary ──────────────────────────────────\n"
        f"  total frames  : {total_frames}\n"
        f"  with metadata : {frames_with_meta}\n"
        f"  duration      : {elapsed:.1f}s  ({fps:.1f} fps)\n"
    )

    await reactor.disconnect()
    reactor.close()


if __name__ == "__main__":
    asyncio.run(main())
