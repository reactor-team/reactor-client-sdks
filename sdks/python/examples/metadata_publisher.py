#!/usr/bin/env python3
"""
Publish tagged frames continuously, with no UI — the sending half of a two-process demo.

Every frame carries a metadata trailer, and the point is that a *different* process can
read it. Run this, note the session id it prints, then join the same session with the
pygame example to watch the frames arrive and see each one's tag on screen::

    # this process — creates the session and starts publishing
    python -m examples.metadata_publisher --model my-model --jwt "$TOKEN"

    # the other one — joins it and renders
    cd examples/pygame_app
    python main.py --model my-model --api-key "$KEY" --session-id <the id printed above>

Sharing a session that way needs a real coordinator. The local runtime cannot do it:
the SDK caches the session it created in this process and the local protocol has no
lookup by id, so a second process is told there is no cached local session. Against
``--local`` this example still runs — it just publishes into a session of its own, and
``frame_metadata_roundtrip.py`` is the example that shows tags surviving the trip
without a second process.

The tag here is JSON: a sequence number, the send time, and the colour, so a viewer can
tell frames apart, spot a gap, and measure how long the trip took. The bytes are opaque
to the SDK and to the runtime — JSON, protobuf, or anything else is between the sender
and whoever reads it.

Nothing configures the capability. reactor-webrtc advertises frame metadata in the offer
and the far end mirrors it in the answer; a peer that does not read trailers simply
receives frames without one. Tagging is safe regardless.

The sibling examples cover the other shapes: ``frame_metadata.py`` reads tags from an
incoming track, and ``frame_metadata_roundtrip.py`` tags frames and matches them coming
back through a model that echoes them.

Usage:
    python -m examples.metadata_publisher --local
    python -m examples.metadata_publisher --duration 0 --fps 15   # 0 = until Ctrl-C

Environment variables (overridden by flags):
    REACTOR_API_URL, REACTOR_MODEL, REACTOR_JWT, REACTOR_LOCAL
"""

from __future__ import annotations

import argparse
import asyncio
import contextlib
import json
import math
import sys
import time

from .reactor_client import make_reactor


def _parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(
        description="Continuously publish video frames tagged with metadata",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    p.add_argument("--track", default="video", metavar="NAME", help="sendonly track to publish")
    p.add_argument("--width", type=int, default=640)
    p.add_argument("--height", type=int, default=480)
    p.add_argument("--fps", type=float, default=30.0)
    p.add_argument(
        "--duration",
        type=float,
        default=0.0,
        metavar="SECONDS",
        help="stop after N seconds; 0 runs until Ctrl-C (default: 0)",
    )
    p.add_argument(
        "--session-id",
        metavar="ID",
        help="join an existing session instead of creating one",
    )
    p.add_argument("--model", metavar="NAME")
    p.add_argument("--api-url", metavar="URL")
    p.add_argument("--jwt", metavar="TOKEN")
    p.add_argument("--local", action="store_true", default=None)
    return p.parse_args()


def _hue_to_rgb(h: float) -> tuple[int, int, int]:
    """Hue in [0, 1) to (R, G, B), full saturation and value."""
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


def _make_frame(width: int, height: int, rgb: tuple[int, int, int]) -> bytes:
    """A solid BGRA frame. Flat colour keeps the encoder cheap and the tag the interesting
    part — the viewer identifies frames by their metadata, not by their content."""
    r, g, b = rgb
    return bytes([b, g, r, 255]) * (width * height)


def _tag(sequence: int, rgb: tuple[int, int, int]) -> bytes:
    """The trailer for one frame.

    Compact on purpose: this rides along with every frame, so it is a per-frame cost on
    the wire. Wall-clock rather than monotonic time, because whoever reads it is in
    another process and possibly on another machine.
    """
    return json.dumps(
        {
            "seq": sequence,
            "sent_at_ms": round(time.time() * 1000),
            "rgb": list(rgb),
        },
        separators=(",", ":"),
    ).encode()


async def main() -> int:
    args = _parse_args()

    # See the note on the pairing above: joining by id needs a coordinator that can look
    # a session up, which the local runtime cannot do across processes.
    if args.session_id and args.local:
        print(
            "--session-id cannot be combined with --local: the local runtime has no way "
            "to look up a session created by another process.",
            file=sys.stderr,
        )
        return 2

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
    await reactor.connect(session_id=args.session_id)
    await asyncio.wait_for(ready.wait(), timeout=60)

    # Printed to stdout, and prominently: joining from another process is the whole point
    # of this example, and this is the value that makes it possible.
    session_id = reactor.session_id
    print(f"session-id: {session_id}", flush=True)
    print(
        f"Ready. Publishing {args.width}×{args.height} @ {args.fps:g} fps on "
        f"'{args.track}', every frame tagged.",
        file=sys.stderr,
    )
    if args.local:
        # Saying "join this" here would be advertising something the local runtime cannot
        # do, and the id it reports is a placeholder rather than a handle.
        print(
            "Local runtime: this session cannot be joined from another process, so "
            "nothing will read these tags. Use a real coordinator for that.",
            file=sys.stderr,
        )
    else:
        print(
            f"Watch it with:  cd examples/pygame_app && python main.py "
            f"--model {reactor._model_name} --session-id {session_id}",
            file=sys.stderr,
        )

    await reactor.publish_track(args.track)

    sent = 0
    started = time.monotonic()
    deadline = started + args.duration if args.duration > 0 else math.inf
    hue = 0.0
    hue_step = frame_secs / 10.0  # a full colour cycle every 10 s
    next_report = started + 5.0

    try:
        while time.monotonic() < deadline:
            loop_start = time.monotonic()

            rgb = _hue_to_rgb(hue)
            reactor.push_video_frame(
                args.track,
                _make_frame(args.width, args.height, rgb),
                args.width,
                args.height,
                user_data=_tag(sent, rgb),
            )
            sent += 1
            hue = math.fmod(hue + hue_step, 1.0)

            if loop_start >= next_report:
                elapsed = loop_start - started
                print(
                    f"  {sent} frames in {elapsed:.0f}s ({sent / elapsed:.1f} fps)",
                    file=sys.stderr,
                )
                next_report = loop_start + 5.0

            # Sleep on the remainder rather than a fixed interval, so the rate does not
            # drift by however long the push took.
            await asyncio.sleep(max(0.0, frame_secs - (time.monotonic() - loop_start)))
    except asyncio.CancelledError:
        pass

    elapsed = max(time.monotonic() - started, 1e-9)
    print(
        f"Sent {sent} tagged frames in {elapsed:.1f}s ({sent / elapsed:.1f} fps).",
        file=sys.stderr,
    )

    reactor.unpublish_track(args.track)
    await reactor.disconnect()
    reactor.close()
    return 0


if __name__ == "__main__":
    with contextlib.suppress(KeyboardInterrupt):
        raise SystemExit(asyncio.run(main()))
