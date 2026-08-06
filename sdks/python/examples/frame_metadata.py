#!/usr/bin/env python3
"""
Frame metadata example — inspect incoming video frames.

Registers an on_frame callback that logs width, height, and byte size for
every frame received.  With --numpy, converts each frame to a numpy array
(shape: height × width × 4, dtype uint8, BGRA channel order) and prints the
first pixel's BGRA values as a sanity check.

With --display, opens a pygame window showing the live video feed.
Requires: pip install numpy pygame   (numpy is already a dependency)

Usage:
    # Print per-frame metadata
    python examples/frame_metadata.py --duration 10

    # Also show numpy shape / first pixel
    python examples/frame_metadata.py --duration 10 --numpy

    # Live display window (requires pygame)
    python examples/frame_metadata.py --display

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
    p = argparse.ArgumentParser(description="Inspect incoming Reactor video frames")
    p.add_argument("--duration", metavar="SECS", type=float, default=15.0,
                   help="How long to run (default: 15 s)")
    p.add_argument("--numpy", action="store_true",
                   help="Convert each frame to a numpy array and print first-pixel info")
    p.add_argument("--display", action="store_true",
                   help="Show live video in a pygame window (requires pygame)")
    p.add_argument("--track", metavar="NAME", default=None,
                   help="Track name to listen for (default: first recvonly video track)")
    p.add_argument("--model", metavar="NAME", help="Model name (overrides REACTOR_MODEL)")
    p.add_argument("--api-url", metavar="URL")
    p.add_argument("--jwt", metavar="TOKEN")
    p.add_argument("--local", action="store_true", default=None)
    return p.parse_args()


async def main() -> None:  # noqa: C901
    args = _parse_args()

    # Optional imports
    np = None
    if args.numpy or args.display:
        try:
            import numpy as _np
            np = _np
        except ImportError:
            print("numpy is required for --numpy / --display.  pip install numpy",
                  file=sys.stderr)
            sys.exit(1)

    pygame = None
    screen = None
    if args.display:
        try:
            import pygame as _pg
            pygame = _pg
        except ImportError:
            print("pygame is required for --display.  pip install pygame", file=sys.stderr)
            sys.exit(1)

    reactor = make_reactor(
        api_url=args.api_url,
        model_name=args.model,
        jwt=args.jwt,
        local=args.local if args.local else None,
    )

    # Stats
    frame_count = 0
    last_w = last_h = 0
    t0 = time.monotonic()
    log_interval = 1.0
    last_log = t0

    def on_frame(data: bytes, width: int, height: int) -> None:
        nonlocal frame_count, last_w, last_h, last_log

        frame_count += 1
        last_w, last_h = width, height
        now = time.monotonic()

        if args.numpy and np is not None:
            arr = np.frombuffer(data, dtype=np.uint8).reshape(height, width, 4)
            # arr shape: (H, W, 4)  channels: B, G, R, A
            b, g, r, a = arr[0, 0]
            print(
                f"frame #{frame_count:5d}  {width}×{height}  "
                f"shape={arr.shape} dtype={arr.dtype}  "
                f"pixel[0,0] B={b} G={g} R={r} A={a}"
            )
        elif now - last_log >= log_interval:
            elapsed = now - t0
            fps = frame_count / elapsed if elapsed > 0 else 0.0
            print(
                f"t={elapsed:6.1f}s  frames={frame_count:5d}  "
                f"{width}×{height}  bytes={len(data)}  avg {fps:.1f} fps"
            )
            last_log = now

        if args.display and np is not None and pygame is not None and screen is not None:
            arr = np.frombuffer(data, dtype=np.uint8).reshape(height, width, 4)
            # Convert BGRA → RGB for pygame
            rgb = arr[:, :, [2, 1, 0]]
            surf = pygame.surfarray.make_surface(rgb.transpose(1, 0, 2))
            scaled = pygame.transform.scale(surf, screen.get_size())
            screen.blit(scaled, (0, 0))
            pygame.display.flip()

    reactor.on("frame", on_frame)
    reactor.on("error", lambda e: print(f"[error] {e}", file=sys.stderr))

    ready = asyncio.Event()
    reactor.on("status_changed", lambda s: ready.set() if s == "ready" else None)

    print("Connecting…", file=sys.stderr)
    await reactor.connect()
    print("Waiting for ready…", file=sys.stderr)
    await asyncio.wait_for(ready.wait(), timeout=60)
    print("Ready — collecting frames…", file=sys.stderr)

    if args.display and pygame is not None:
        pygame.init()
        screen = pygame.display.set_mode((1280, 720))
        pygame.display.set_caption("Reactor frame_metadata")

    deadline = asyncio.get_event_loop().time() + args.duration
    while asyncio.get_event_loop().time() < deadline:
        if args.display and pygame is not None:
            for ev in pygame.event.get():
                if ev.type == pygame.QUIT or (
                    ev.type == pygame.KEYDOWN and ev.key == pygame.K_ESCAPE
                ):
                    break
        await asyncio.sleep(0.016)

    elapsed = time.monotonic() - t0
    fps = frame_count / elapsed if elapsed > 0 else 0.0
    print(
        f"\nSummary: {frame_count} frames in {elapsed:.1f}s  "
        f"({fps:.1f} fps)  last size: {last_w}×{last_h}"
    )

    if args.display and pygame is not None:
        pygame.quit()

    await reactor.disconnect()
    reactor.close()


if __name__ == "__main__":
    asyncio.run(main())
