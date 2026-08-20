#!/usr/bin/env python3
"""06 · Record a clip — capture what just happened, and download it.

`request_clip` asks the runtime for the last N seconds and answers with an HLS
manifest; `reactor.download` fetches the segments into one file. The bytes are
MPEG-TS, hence `.ts` — remux with `ffmpeg -i clip.ts -c copy clip.mp4` if needed.

Readiness is in *media* time, not wall clock: the manifest appears once the
recording passes the end of the chunk holding the window. `clip.now_marker` is
that media clock, and it advances with frames produced — so this asks for a clip,
checks how much media exists, and waits if there is not enough yet.

`request_recording()` is the same call for the whole session.

    uv run python examples/06_record_clip.py [seconds] [out.ts]

Docs: https://docs.reactor.inc/concepts/recordings
      https://docs.reactor.inc/sdk-reference/python/types#clip
"""

from __future__ import annotations

import asyncio
import os
import sys
from pathlib import Path

import display

from reactor_sdk import DEFAULT_API_URL, Reactor

API_KEY = os.environ.get("REACTOR_API_KEY")
MODEL = os.environ.get("REACTOR_MODEL", "helios")
SHOW = os.environ.get("REACTOR_SHOW") == "1"

PROMPT = "a forest at dawn, sunbeams through the canopy"
OUTPUT_TRACK = "main_video"
# Helios records in 4s chunks, and the coordinator needs the chunk *after* the
# window to have closed — so a clip is servable once media time passes 8s.
MIN_MEDIA = 8.0


async def main() -> None:
    clip_seconds = float(sys.argv[1]) if len(sys.argv) > 1 else 5.0
    out = sys.argv[2] if len(sys.argv) > 2 else "clip.ts"
    if not API_KEY and os.environ.get("REACTOR_LOCAL") != "1":
        sys.exit("set REACTOR_API_KEY — https://www.reactor.inc/account/api-keys")

    reactor = Reactor(
        model_name=MODEL,
        api_key=API_KEY,
        api_url=os.environ.get("REACTOR_API_URL", DEFAULT_API_URL),
        local=os.environ.get("REACTOR_LOCAL") == "1",
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
        await reactor.send_command("set_prompt", {"prompt": PROMPT})
        await reactor.send_command("start", {})

        output = reactor.track(OUTPUT_TRACK)
        frames = 0
        window = display.window(f"{MODEL} · {OUTPUT_TRACK}", enabled=SHOW)

        @output.on_raw_frame
        def count(data: bytes, width: int, height: int, *_: object) -> None:
            nonlocal frames
            frames += 1
            window.submit(data, width, height)

        # The recorder has nothing to cut until a frame has been fed to it, and
        # asking before that fails with "no media generated yet".
        while frames == 0:
            await window.hold(0.5)
        await window.hold(1)

        # A clip request is cheap and answers with the media clock, so it is also
        # how you find out whether there is enough recorded to cut from.
        while True:
            clip = await reactor.request_clip(clip_seconds)
            if clip.now_marker >= MIN_MEDIA:
                break
            print(f"media recorded: {clip.now_marker:.1f}s of {MIN_MEDIA:.0f}s")
            await window.hold(5)

        print(f"frames: {frames}")
        print(f"clip: {clip.kind} {clip.playlist_url}")
        print(f"window: {clip.start_marker:.1f} → {clip.end_marker:.1f}")
        if clip.end_marker - clip.start_marker < clip_seconds * 0.5:
            print("warning: the session had less video than the window asked for")

        # Carries the token and waits out the 202s, on a budget anchored at the
        # runtime's own predicted-ready. `reactor.download_clip(seconds, path)`
        # does the request and this in one call.
        await reactor.download(clip, out)
        print(f"saved: {out} ({Path(out).stat().st_size // 1024} KB)")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("interrupted")
