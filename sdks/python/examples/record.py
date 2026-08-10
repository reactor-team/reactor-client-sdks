#!/usr/bin/env python3
"""
Recording example — request a clip or a full-session recording.

Connects to a model, waits until ready, then calls request_clip() or
request_recording().  Prints the resulting playlist URL and, if --download
is given, fetches all HLS segments and writes them as a concatenated
byte stream to the output file.

Usage:
    # Clip of the last 10 seconds
    python examples/record.py --clip 10

    # Full-session recording
    python examples/record.py --recording

    # Download the clip segments to a file
    python examples/record.py --clip 10 --download clip.ts

Environment variables (overridden by flags):
    REACTOR_API_URL, REACTOR_MODEL, REACTOR_JWT, REACTOR_LOCAL
"""

from __future__ import annotations

import argparse
import asyncio
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent.parent))

from reactor import Clip, ReactorFFIError

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


def _download_segments(playlist_url: str, out_path: str) -> None:
    """Fetch an HLS playlist and concatenate all .ts segments into out_path."""
    print(f"Downloading playlist: {playlist_url}", file=sys.stderr)
    base_url = playlist_url.rsplit("/", 1)[0] + "/"

    with urllib.request.urlopen(playlist_url) as resp:
        playlist = resp.read().decode()

    segments = [
        line.strip() for line in playlist.splitlines() if line.strip() and not line.startswith("#")
    ]
    if not segments:
        print("No segments found in playlist", file=sys.stderr)
        return

    print(f"Fetching {len(segments)} segment(s) → {out_path}", file=sys.stderr)
    with open(out_path, "wb") as out:
        for i, seg in enumerate(segments, 1):
            url = seg if seg.startswith("http") else base_url + seg
            print(f"  [{i}/{len(segments)}] {url}", file=sys.stderr)
            with urllib.request.urlopen(url) as r:
                out.write(r.read())

    size_kb = Path(out_path).stat().st_size // 1024
    print(f"Saved {size_kb} KB to {out_path}", file=sys.stderr)


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

    clip: Clip
    try:
        if args.recording:
            print("Requesting recording…", file=sys.stderr)
            clip = await reactor.request_recording()
        else:
            print(f"Requesting clip ({args.clip}s)…", file=sys.stderr)
            clip = await reactor.request_clip(args.clip)
    except ReactorFFIError as exc:
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
        _download_segments(clip.playlist_url, args.download)

    await reactor.disconnect()
    reactor.close()


if __name__ == "__main__":
    asyncio.run(main())
