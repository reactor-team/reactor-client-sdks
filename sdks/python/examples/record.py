#!/usr/bin/env python3
"""
Recording example — request a clip or a full-session recording.

Connects to a model, waits until ready, then calls request_clip() or
request_recording().  Prints the resulting playlist URL and, if --download
is given, fetches all HLS segments and writes them as a concatenated
byte stream to the output file.

Uses request_clip()/request_recording() + download_clip() separately by
default: this example wants the Clip's metadata (session_id, the markers,
predicted_ready_at_ms) to print regardless of --download. Pass --simple to
see the other side instead — reactor.download_clip()/download_recording()
doing the request and the download in one call, with no Clip in sight.

Usage:
    # Clip of the last 10 seconds
    python -m examples.record --clip 10

    # Full-session recording
    python -m examples.record --recording

    # Download the clip segments to a file
    python -m examples.record --clip 10 --download clip.ts

    # The one-call form: request + download together, no Clip printed
    python -m examples.record --clip 10 --download clip.ts --simple

Environment variables (overridden by flags):
    REACTOR_API_URL, REACTOR_MODEL, REACTOR_JWT, REACTOR_LOCAL
"""

from __future__ import annotations

import argparse
import asyncio
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from reactor_sdk import Clip, Reactor, ReactorError, download_clip

from .reactor_client import make_reactor


def _parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="Request a Reactor clip or recording")
    group = p.add_mutually_exclusive_group(required=True)
    group.add_argument(
        "--clip", metavar="SECONDS", type=float, help="Request a clip of the last N seconds"
    )
    group.add_argument("--recording", action="store_true", help="Request a full-session recording")
    p.add_argument(
        "--download", metavar="FILE", help="Download HLS segments and write to FILE (e.g. clip.ts)"
    )
    p.add_argument(
        "--simple",
        action="store_true",
        help=(
            "Use reactor.download_clip()/download_recording() — request and download in one "
            "call, no Clip metadata printed. Implies --download is where the file goes; without "
            "it, prints how many bytes came back instead."
        ),
    )
    p.add_argument("--model", metavar="NAME", help="Model name (overrides REACTOR_MODEL)")
    p.add_argument("--api-url", metavar="URL", help="Coordinator URL (overrides REACTOR_API_URL)")
    p.add_argument("--jwt", metavar="TOKEN", help="JWT token (overrides REACTOR_JWT)")
    p.add_argument(
        "--local",
        action="store_true",
        default=None,
        help="Use local mode (overrides REACTOR_LOCAL)",
    )
    return p.parse_args()


async def _download(clip: Clip, out_path: str) -> None:
    """Fetch every segment `clip.playlist_url` names and write them to out_path."""
    print(f"Downloading playlist: {clip.playlist_url}", file=sys.stderr)

    def on_progress(done: int, total: int) -> None:
        print(f"  [{done}/{total}]", file=sys.stderr)

    await download_clip(clip, out_path, on_progress=on_progress)

    size_kb = Path(out_path).stat().st_size // 1024
    print(f"Saved {size_kb} KB to {out_path}", file=sys.stderr)


async def _simple(reactor: Reactor, args: argparse.Namespace) -> None:
    """The other half of this example: request + download in one call, no
    Clip in sight — reactor.download_clip() / reactor.download_recording()."""

    def on_progress(done: int, total: int) -> None:
        print(f"  [{done}/{total}]", file=sys.stderr)

    try:
        if args.recording:
            print("Requesting + downloading recording…", file=sys.stderr)
            result = await reactor.download_recording(args.download, on_progress=on_progress)
        else:
            print(f"Requesting + downloading clip ({args.clip}s)…", file=sys.stderr)
            result = await reactor.download_clip(args.clip, args.download, on_progress=on_progress)
    except ReactorError as exc:
        print(f"Clip request failed: {exc}", file=sys.stderr)
        await reactor.disconnect()
        reactor.close()
        sys.exit(1)

    if args.download:
        size_kb = Path(args.download).stat().st_size // 1024
        print(f"Saved {size_kb} KB to {args.download}", file=sys.stderr)
    else:
        assert result is not None  # no path given: bytes, not None, came back
        print(f"Got {len(result) // 1024} KB back, not written anywhere (pass --download for that)")


async def main() -> None:
    args = _parse_args()

    reactor = make_reactor(
        api_url=args.api_url,
        model_name=args.model,
        jwt=args.jwt,
        local=args.local if args.local else None,
    )

    ready = asyncio.Event()
    reactor.on("status_changed", lambda s: ready.set() if s == "ready" else None)
    reactor.on("error", lambda e: print(f"[error] {e}", file=sys.stderr))

    print("Connecting…", file=sys.stderr)
    await reactor.connect()

    print("Waiting for ready…", file=sys.stderr)
    await asyncio.wait_for(ready.wait(), timeout=60)
    print("Ready.", file=sys.stderr)

    if args.simple:
        await _simple(reactor, args)
        await reactor.disconnect()
        reactor.close()
        return

    clip: Clip
    try:
        if args.recording:
            print("Requesting recording…", file=sys.stderr)
            clip = await reactor.request_recording()
        else:
            print(f"Requesting clip ({args.clip}s)…", file=sys.stderr)
            clip = await reactor.request_clip(args.clip)
    except ReactorError as exc:
        print(f"Clip request failed: {exc}", file=sys.stderr)
        await reactor.disconnect()
        reactor.close()
        sys.exit(1)

    print(f"session_id:         {clip.session_id}")
    print(f"kind:               {clip.kind}")
    print(f"playlist_url:       {clip.playlist_url}")
    print(f"start_marker:       {clip.start_marker}")
    print(f"end_marker:         {clip.end_marker}")
    print(f"predicted_ready_ms: {clip.predicted_ready_at_ms:.0f}")

    if args.download:
        await _download(clip, args.download)

    await reactor.disconnect()
    reactor.close()


if __name__ == "__main__":
    asyncio.run(main())
