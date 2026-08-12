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

Joining works against a local runtime as well as a real coordinator: a local runtime
holds one session and describes it at ``GET /session``, so the viewer joins by asking for
the id this prints.

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

Ctrl-C stops it cleanly, which matters here: the session belongs to the peer that
created it, so this process disconnecting is what releases it for the next run.

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
import signal
import sys
import time

from .reactor_client import make_reactor


def _parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(
        description="Continuously publish video frames tagged with metadata",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    p.add_argument(
        "--track",
        metavar="NAME",
        help="sendonly video track to publish on; defaults to the model's, when it has one",
    )
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


def _choose_track(capabilities: dict, requested: str | None) -> str:
    """Pick the sendonly video track to publish on, from what the model declares.

    Named explicitly, it is checked rather than trusted, because a name the model does
    not have fails in the worst way available: `push_video_frame` finds no local track,
    logs a warning nothing surfaces, and returns — so every frame is dropped while the
    send keeps reporting success.
    """
    video = [
        track["name"]
        for track in capabilities.get("tracks", [])
        if track.get("kind") == "video" and track.get("direction") == "sendonly"
    ]

    if requested is not None:
        if requested not in video:
            available = ", ".join(video) or "none"
            raise SystemExit(
                f"the model has no sendonly video track called '{requested}' (it has: {available})"
            )
        return requested

    if not video:
        raise SystemExit("the model declares no sendonly video track to publish on")
    if len(video) > 1:
        raise SystemExit(
            f"the model has several sendonly video tracks ({', '.join(video)}); "
            f"name one with --track"
        )
    return video[0]


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

    frame_secs = 1.0 / args.fps

    reactor = make_reactor(
        api_url=args.api_url,
        model_name=args.model,
        jwt=args.jwt,
        local=args.local if args.local else None,
    )
    reactor.on("error", lambda e: print(f"[error] {e}", file=sys.stderr))

    ready = asyncio.Event()

    # The model declares which tracks exist and which way each one goes, and the SDK
    # hands that over as `capabilities_received`. Picking the track from it beats
    # guessing a name: pushing to a track the model did not declare is silently dropped
    # — the frames go nowhere and the send still looks like it worked.
    capabilities: asyncio.Future[dict] = asyncio.get_running_loop().create_future()
    reactor.on(
        "capabilities_received",
        lambda caps: None if capabilities.done() else capabilities.set_result(caps),
    )

    # A published track stops going anywhere if the peer connection drops, and there is
    # nothing in the push path to say so, so watch the status instead of pushing into a
    # connection that has gone.
    live = asyncio.Event()
    live.set()

    def _on_status(status: str) -> None:
        if status == "ready":
            ready.set()
            live.set()
        elif ready.is_set():
            # Only after the first ready: the states on the way up are not a drop.
            live.clear()

    reactor.on("status_changed", _on_status)

    # Ctrl-C asks the loop to finish rather than raising through it. The teardown below
    # has to run: this process owns the session, and a creator that goes away without
    # disconnecting takes the session with it — the runtime marks it orphaned, and the
    # next run cannot start ("cannot start session while orphaned") until it is cleared.
    # A KeyboardInterrupt would unwind straight past `disconnect()`, and moving the
    # teardown into a `finally` alone would not help, since by then the task is being
    # cancelled and its remaining awaits are not guaranteed to complete.
    stopping = asyncio.Event()
    _install_interrupt_handler(stopping)

    print("Connecting…", file=sys.stderr)
    await reactor.connect(session_id=args.session_id)
    await asyncio.wait_for(ready.wait(), timeout=60)

    track = _choose_track(await asyncio.wait_for(capabilities, timeout=30), args.track)

    # Printed to stdout, and prominently: joining from another process is the whole point
    # of this example, and this is the value that makes it possible.
    session_id = reactor.session_id
    print(f"session-id: {session_id}", flush=True)
    print(
        f"Ready. Publishing {args.width}×{args.height} @ {args.fps:g} fps on "
        f"'{track}', every frame tagged.",
        file=sys.stderr,
    )
    print(
        f"Watch it with:  cd examples/pygame_app && python main.py "
        f"{'--local ' if args.local else ''}--model {reactor._model_name} "
        f"--session-id {session_id}",
        file=sys.stderr,
    )

    await reactor.publish_track(track)

    sent = 0
    started = time.monotonic()
    deadline = started + args.duration if args.duration > 0 else math.inf
    hue = 0.0
    hue_step = frame_secs / 10.0  # a full colour cycle every 10 s
    next_report = started + 5.0

    try:
        while time.monotonic() < deadline:
            if stopping.is_set():
                print("\nStopping…", file=sys.stderr)
                break

            if not live.is_set():
                print(
                    f"Connection dropped after {sent} frames — nothing published from "
                    f"here is reaching anyone. Stopping.",
                    file=sys.stderr,
                )
                break

            loop_start = time.monotonic()

            rgb = _hue_to_rgb(hue)
            reactor.push_video_frame(
                track,
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
    finally:
        elapsed = max(time.monotonic() - started, 1e-9)
        print(
            f"Sent {sent} tagged frames in {elapsed:.1f}s ({sent / elapsed:.1f} fps).",
            file=sys.stderr,
        )

        # Ending the session deliberately, rather than by vanishing, is what leaves the
        # runtime able to start the next one.
        reactor.unpublish_track(track)
        await reactor.disconnect()
        reactor.close()

    return 0


def _install_interrupt_handler(stopping: asyncio.Event) -> None:
    """Make Ctrl-C set ``stopping`` instead of raising.

    ``loop.add_signal_handler`` is the clean path but is not implemented on Windows, so
    fall back to ``signal.signal`` there and hop back onto the loop thread, since a
    handler installed that way runs wherever the signal lands.
    """
    loop = asyncio.get_running_loop()
    try:
        loop.add_signal_handler(signal.SIGINT, stopping.set)
    except NotImplementedError:
        signal.signal(signal.SIGINT, lambda *_: loop.call_soon_threadsafe(stopping.set))


if __name__ == "__main__":
    # Reached only if a second Ctrl-C arrives while the first one is still shutting down.
    with contextlib.suppress(KeyboardInterrupt):
        raise SystemExit(asyncio.run(main()))
