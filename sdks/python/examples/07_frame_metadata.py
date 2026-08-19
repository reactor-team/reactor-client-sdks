#!/usr/bin/env python3
"""07 · Frame metadata — what arrives alongside the pixels.

Every decoded frame can carry a trailer: a frame id, the sender's wall-clock
timestamp, and arbitrary bytes. A frame without one arrives as zeros and empty
bytes. The timestamps are the sender's clock, so they measure the model's pacing
rather than the network's.

`user_data` is empty unless the model mirrors it back, and no published model does
today; example 04 shows the sending side.

Native-only: a browser gets a MediaStreamTrack with no per-frame hook.

    uv run python examples/07_frame_metadata.py

Docs: https://docs.reactor.inc/concepts/frame-metadata
      https://docs.reactor.inc/sdk-reference/python/track
"""

from __future__ import annotations

import asyncio
import os
import sys

import display

from reactor_sdk import DEFAULT_API_URL, Reactor

API_KEY = os.environ.get("REACTOR_API_KEY")
MODEL = os.environ.get("REACTOR_MODEL", "helios")
SECONDS = float(os.environ.get("REACTOR_SECONDS", "10"))
SHOW = os.environ.get("REACTOR_SHOW") == "1"

PROMPT = "a forest at dawn, sunbeams through the canopy"
OUTPUT_TRACK = "main_video"


async def main() -> None:
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

    with_trailer = without_trailer = tagged = 0
    previous: int | None = None
    gaps: list[int] = []

    async with reactor:
        await reactor.connect()
        print(f"session: {reactor.session_id}")
        await reactor.send_command("set_prompt", {"prompt": PROMPT})
        await reactor.send_command("start", {})

        output = reactor.track(OUTPUT_TRACK)
        window = display.window(f"{MODEL} · {OUTPUT_TRACK}", enabled=SHOW)

        @output.on_raw_frame
        def inspect(
            data: bytes,
            width: int,
            height: int,
            frame_id: int,
            timestamp_us: int,
            user_data: bytes,
        ) -> None:
            nonlocal with_trailer, without_trailer, tagged, previous
            window.submit(data, width, height)

            # No trailer at all: ids and timestamp zero, no bytes.
            if not frame_id and not timestamp_us and not user_data:
                without_trailer += 1
                return

            with_trailer += 1
            tagged += bool(user_data)
            if previous is not None and timestamp_us > previous:
                gaps.append(timestamp_us - previous)
            previous = timestamp_us

            if with_trailer <= 3:
                print(f"frame {frame_id}: {width}x{height} at {timestamp_us} us, {user_data!r}")

        await window.hold(SECONDS)

    print(f"frames with a trailer: {with_trailer}")
    print(f"frames without one: {without_trailer}")
    print(f"frames carrying user_data: {tagged}")
    if gaps:
        median = sorted(gaps)[len(gaps) // 2]
        print(f"sender cadence: {median / 1000:.1f} ms median, {1_000_000 / median:.1f} fps")


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        print("interrupted")
