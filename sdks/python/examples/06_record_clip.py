#!/usr/bin/env python3
"""06 · Record a clip — capture what just happened, and download it.

`request_clip` asks the runtime for the last N seconds and answers with an HLS
manifest; `reactor.download` fetches the segments into one file. The bytes are
MPEG-TS, hence `.ts` — remux with `ffmpeg -i clip.ts -c copy clip.mp4` if needed.

Readiness is in *media* time, not wall clock: the manifest appears once the
recording passes the end of the chunk holding the window. `clip.now_marker` is
that media clock — it advances with frames recorded, so a model generating at a
fraction of real-time reaches the boundary proportionally later. This waits
without a deadline, because the wait ends by itself when the session does.

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

    # Where a session end announces itself, among the runtime's other traffic —
    # worth having when a clip never becomes ready.
    @reactor.on_message
    def message(msg: dict) -> None:
        print(f"message: {msg}")

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

        # The window ends at the media clock's "now", so the chunk holding its end
        # is always the one still open. Waiting before asking only moves the end.
        clip = await reactor.request_clip(clip_seconds)
        print(f"frames: {frames}")
        print(f"clip: {clip.kind} {clip.playlist_url}")
        print(f"window: {clip.start_marker:.1f} → {clip.end_marker:.1f}")
        print(f"recorded so far: {clip.now_marker:.1f}s of media")
        if clip.end_marker - clip.start_marker < clip_seconds * 0.5:
            print("warning: the session had less video than the window asked for")

        # Carries the token and waits out the 202s with no deadline, giving up on
        # its own if the session ends — the only way a clip can never arrive.
        # `reactor.download_clip(seconds, path)` does the request and this in one.
        print("waiting for the recorder to pass the end of the window…")
        download = asyncio.create_task(reactor.download(clip, out))
        while not download.done():
            await window.hold(0.5)  # the wait can outlast the clip it waits for
        await download
        print(f"saved: {out} ({Path(out).stat().st_size // 1024} KB)")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("interrupted")
