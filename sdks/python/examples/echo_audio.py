#!/usr/bin/env python3
"""
Echo example — speak into the microphone and hear the model send it back.

The full audio duplex, which is the case `AudioDevices` exists for: the model's
recvonly audio track goes to the speakers, the microphone goes to its sendonly
track, and both devices open and close together.

The SDK opens no audio device of its own, so this needs PortAudio:

    pip install "reactor-sdk[audio]"

Usage:
    python -m examples.echo_audio --duration 30

    # Pick devices by name or index, as `python -m sounddevice` lists them
    python -m examples.echo_audio --input "MacBook Pro Microphone" --output 2

Environment variables (overridden by flags):
    REACTOR_API_URL, REACTOR_MODEL, REACTOR_JWT, REACTOR_LOCAL
"""

from __future__ import annotations

import argparse
import asyncio
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from reactor_sdk import AudioDevices

from .reactor_client import make_reactor


def _parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Send the microphone and play what comes back")
    p.add_argument(
        "--duration",
        metavar="SECS",
        type=float,
        default=30.0,
        help="How long to stay on the call (default: 30)",
    )
    p.add_argument("--input", metavar="DEVICE", help="Capture device name or index")
    p.add_argument("--output", metavar="DEVICE", help="Playout device name or index")
    p.add_argument("--model", metavar="NAME")
    p.add_argument("--api-url", metavar="URL")
    p.add_argument("--jwt", metavar="TOKEN")
    p.add_argument("--local", action="store_true", default=None)
    return p.parse_args()


def _device(value: str | None) -> int | str | None:
    """sounddevice takes an index or a name; the flag gives us a string for both."""
    if value is None:
        return None
    return int(value) if value.isdigit() else value


async def main() -> None:
    args = _parse_args()

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

    # Everything below can fail, and the session this process created has to be
    # released either way — a creator that goes away without disconnecting leaves it
    # orphaned, and the next run cannot start until that clears.
    try:
        # One object because this is one flow: it finds both audio tracks, publishes
        # the one it captures into, opens both devices, and closes them on the way
        # out. Either half may be missing, and a model that only sends audio is a
        # normal session rather than an error.
        async with AudioDevices(
            reactor,
            input_device=_device(args.input),
            output_device=_device(args.output),
        ) as audio:
            if audio.speaker is None and audio.microphone is None:
                print(
                    f"{reactor._model_name} declares no audio tracks at all — "
                    f"there is nothing to echo.",
                    file=sys.stderr,
                )
                return

            print(
                f"Speak. Playing {'yes' if audio.speaker else 'no'}, "
                f"capturing {'yes' if audio.microphone else 'no'}, "
                f"for {args.duration:.0f}s.",
                file=sys.stderr,
            )
            await asyncio.sleep(args.duration)

            if audio.microphone is not None:
                print(f"Sent {audio.microphone.blocks_sent} capture blocks.", file=sys.stderr)
            if audio.speaker is not None and audio.speaker.under_runs:
                # Worth saying: it is the difference between "the model went quiet"
                # and "we could not keep up with it".
                print(
                    f"{audio.speaker.under_runs} under-run(s), "
                    f"{audio.speaker.dropped_ms} ms dropped.",
                    file=sys.stderr,
                )
    finally:
        await reactor.disconnect()
        reactor.close()


if __name__ == "__main__":
    asyncio.run(main())
