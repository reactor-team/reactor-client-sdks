#!/usr/bin/env python3
"""
Push video example — stream generated BGRA frames into a sendonly video track.

Generates frames with a smoothly cycling background hue so it is visually
obvious that frames are being delivered.  Each frame is a solid color (BGRA)
that rotates through the hue wheel over time.

Usage:
    python -m examples.push_video --track video_input

    # Custom resolution and frame rate
    python -m examples.push_video --track video_input --width 640 --height 360 --fps 15

    # Limit duration
    python -m examples.push_video --track video_input --duration 10

Environment variables (overridden by flags):
    REACTOR_API_URL, REACTOR_MODEL, REACTOR_JWT, REACTOR_LOCAL
"""

from __future__ import annotations

import argparse
import asyncio
import math
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from .reactor_client import make_reactor


def _parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Push generated video frames into Reactor")
    p.add_argument("--track", metavar="NAME", required=True,
                   help="Name of the sendonly video track")
    p.add_argument("--width", metavar="W", type=int, default=1280,
                   help="Frame width in pixels (default: 1280)")
    p.add_argument("--height", metavar="H", type=int, default=720,
                   help="Frame height in pixels (default: 720)")
    p.add_argument("--fps", metavar="FPS", type=float, default=30.0,
                   help="Target frame rate (default: 30)")
    p.add_argument("--duration", metavar="SECS", type=float, default=30.0,
                   help="Stop after N seconds (default: 30)")
    p.add_argument("--model", metavar="NAME")
    p.add_argument("--api-url", metavar="URL")
    p.add_argument("--jwt", metavar="TOKEN")
    p.add_argument("--local", action="store_true", default=None)
    return p.parse_args()


def _hue_to_rgb(h: float) -> tuple[int, int, int]:
    """Convert hue [0, 1) to (R, G, B) with full saturation and value."""
    h6 = h * 6.0
    i = int(h6)
    f = h6 - i
    match i % 6:
        case 0:
            return 255, int(255 * f), 0
        case 1:
            return int(255 * (1 - f)), 255, 0
        case 2:
            return 0, 255, int(255 * f)
        case 3:
            return 0, int(255 * (1 - f)), 255
        case 4:
            return int(255 * f), 0, 255
        case _:
            return 255, 0, int(255 * (1 - f))


def _make_frame(width: int, height: int, hue: float) -> bytes:
    """Return a solid-color BGRA frame (bytes, width*height*4 bytes)."""
    r, g, b = _hue_to_rgb(hue)
    pixel = bytes([b, g, r, 255])   # BGRA
    return pixel * (width * height)


async def main() -> None:
    args = _parse_args()
    frame_secs = 1.0 / args.fps

    reactor = make_reactor(
        api_url=args.api_url,
        model_name=args.model,
        jwt=args.jwt,
        local=args.local if args.local else None,
    )
    reactor.on("error", lambda e: print(f"[error] {e}", file=sys.stderr))

    ready = asyncio.Event()
    reactor.on("status_changed", lambda s: ready.set() if s == "ready" else None)

    print("Connecting…", file=sys.stderr)
    await reactor.connect()
    await asyncio.wait_for(ready.wait(), timeout=60)
    print(
        f"Ready. Pushing {args.width}×{args.height} @ {args.fps:.0f} fps "
        f"for {args.duration:.1f}s…",
        file=sys.stderr,
    )

    await reactor.publish_track(args.track)

    frames_sent = 0
    t_start = time.monotonic()
    deadline = t_start + args.duration
    hue = 0.0
    hue_step = frame_secs / 10.0   # full cycle every 10 s

    while True:
        loop_start = time.monotonic()
        if loop_start >= deadline:
            break

        frame = _make_frame(args.width, args.height, hue)
        reactor.push_video_frame(args.track, frame, args.width, args.height)
        frames_sent += 1
        hue = math.fmod(hue + hue_step, 1.0)

        elapsed = time.monotonic() - loop_start
        sleep_for = max(0.0, frame_secs - elapsed)
        await asyncio.sleep(sleep_for)

    total = time.monotonic() - t_start
    actual_fps = frames_sent / total if total > 0 else 0.0
    print(
        f"Done — {frames_sent} frames in {total:.2f}s  ({actual_fps:.1f} fps actual)",
        file=sys.stderr,
    )

    reactor.unpublish_track(args.track)
    await reactor.disconnect()
    reactor.close()


if __name__ == "__main__":
    asyncio.run(main())
