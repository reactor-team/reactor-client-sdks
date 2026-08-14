#!/usr/bin/env python3
"""
Frame metadata round-trip — tag frames on the way out, match them on the way back.

The echo model returns each webcam frame's metadata on the processed frame it
produced, so a client can pair the two without a side channel. This example is
the client half of that loop: it sends frames tagged with a sequence number and
a send timestamp, then checks the same tag comes back and reports how many
round-tripped, in what order, and how long each took.

What it demonstrates:
  - ``push_video_frame(..., user_data=...)`` tags an outbound frame.
  - ``on("frame")`` surfaces the tag the sender attached, as ``user_data``.
  - Nothing negotiates or configures the capability: reactor-webrtc advertises it
    in the offer and the runtime's answer mirrors it. A model that does not echo
    metadata simply returns frames with none, and this example reports that
    rather than hanging.

Run it against a local runtime serving the echo model::

    # In the reactor-runtime checkout:
    #   mise run serve examples/echo
    REACTOR_LOCAL=1 REACTOR_API_URL=http://localhost:8080 REACTOR_MODEL=echo \\
        python -m examples.frame_metadata_roundtrip

Usage:
    python -m examples.frame_metadata_roundtrip
    python -m examples.frame_metadata_roundtrip --frames 60 --verbose

Environment variables (overridden by flags):
    REACTOR_API_URL, REACTOR_MODEL, REACTOR_JWT, REACTOR_LOCAL
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from .reactor_client import make_reactor

WIDTH = 320
HEIGHT = 240
FPS = 30


def _frame(seq: int) -> bytes:
    """Return a solid BGRA frame whose colour follows *seq*, so frames differ.

    Identical frames let the encoder emit almost nothing, which starves the
    round-trip; varying the content keeps real frames on the wire.
    """
    b = seq * 7 % 256
    g = seq * 13 % 256
    r = seq * 29 % 256
    return bytes([b, g, r, 255]) * (WIDTH * HEIGHT)


async def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--track", default="webcam", help="outbound track to send on")
    parser.add_argument("--in-track", default="main_video", help="inbound track to read")
    parser.add_argument("--frames", type=int, default=30, help="tagged frames to send")
    parser.add_argument("--timeout", type=float, default=30.0, help="seconds to wait for returns")
    parser.add_argument("--verbose", action="store_true", help="print every returned tag")
    args = parser.parse_args()

    reactor = make_reactor()

    # seq -> monotonic time it was sent, so a return can be timed.
    sent: dict[int, float] = {}
    returned: list[tuple[int, float]] = []
    untagged = 0
    malformed = 0

    def on_frame(
        _data: bytes,
        _w: int,
        _h: int,
        _frame_id: int,
        _timestamp_us: int,
        user_data: bytes,
    ) -> None:
        nonlocal untagged, malformed
        if not user_data:
            untagged += 1
            return
        try:
            tag = json.loads(user_data)
            seq = int(tag["seq"])
        except (ValueError, KeyError, TypeError):
            malformed += 1
            if args.verbose:
                print(f"  ✗ unrecognised tag: {user_data!r}")
            return
        start = sent.get(seq)
        if start is None:
            # A tag we never sent: the model invented it, or a stale frame from a
            # previous run is still in flight.
            malformed += 1
            return
        elapsed_ms = (time.monotonic() - start) * 1000
        returned.append((seq, elapsed_ms))
        if args.verbose:
            print(f"  ✓ seq={seq} back in {elapsed_ms:.0f} ms")

    reactor.on("frame", on_frame)

    print(f"connecting to {args.in_track!r} (sending on {args.track!r})…")
    await reactor.connect()
    try:
        for seq in range(args.frames):
            tag = json.dumps({"seq": seq, "sent_us": int(time.time() * 1e6)}).encode()
            sent[seq] = time.monotonic()
            reactor.push_video_frame(args.track, _frame(seq), WIDTH, HEIGHT, user_data=tag)
            await asyncio.sleep(1 / FPS)

        # Frames keep arriving after the last push; wait for the tail rather than
        # judging the round-trip on what happened to have landed already.
        deadline = time.monotonic() + args.timeout
        while len(returned) < args.frames and time.monotonic() < deadline:
            await asyncio.sleep(0.05)
    finally:
        await reactor.disconnect()

    print()
    print(f"sent      : {len(sent)} tagged frames")
    print(f"returned  : {len(returned)} tags matched")
    print(f"untagged  : {untagged} frames arrived with no metadata")
    if malformed:
        print(f"unmatched : {malformed} tags neither sent nor understood")

    if not returned:
        print()
        print("No tag came back. Either the model does not echo frame metadata, or")
        print("the two peers did not negotiate the capability — check that the")
        print("runtime is new enough to mirror it in its answer.")
        return 1

    ordered = all(a <= b for (a, _), (b, _) in zip(returned, returned[1:], strict=False))
    latencies = sorted(ms for _, ms in returned)
    print(f"order     : {'preserved' if ordered else 'out of order'}")
    print(
        f"latency   : min {latencies[0]:.0f} ms  median {latencies[len(latencies) // 2]:.0f} ms"
        f"  max {latencies[-1]:.0f} ms"
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(asyncio.run(main()))
    except KeyboardInterrupt:
        print("\ninterrupted")
        raise SystemExit(130) from None
