"""Upload a file, condition a command on it — example 02.

`upload_file` returns a `FileRef`; passed as a top-level value in a command's
`data`, `send_command` pulls it out and sends it as a separate upload
reference rather than embedding it in the JSON payload (`client.py`'s
`send_command` docstring). `reactor/echo`'s `set_overlay_image` is what this
suite has that actually consumes an upload and produces an assertable effect.
"""

from __future__ import annotations

import asyncio

from conftest import assert_dominant_color, solid_rgb_frame, solid_rgb_png, wait_until

from reactor_sdk import FileRef, Reactor

WIDTH, HEIGHT = 64, 64


async def test_upload_file_returns_a_useable_file_ref(reactor: Reactor) -> None:
    png = solid_rgb_png(8, 8, (200, 30, 90))
    ref = await reactor.upload_file(png, name="overlay.png", mime_type="image/png")

    assert isinstance(ref, FileRef)
    assert ref.upload_id
    assert ref.name == "overlay.png"
    assert ref.mime_type == "image/png"
    assert ref.size == len(png)


async def test_set_overlay_image_at_full_strength_dominates_output(reactor: Reactor) -> None:
    overlay_color = (220, 60, 15)
    png = solid_rgb_png(16, 16, overlay_color)
    ref = await reactor.upload_file(png, name="overlay.png", mime_type="image/png")

    webcam = await reactor.publish_track("webcam")
    main_video = reactor.track("main_video")

    frame = solid_rgb_frame(WIDTH, HEIGHT, (10, 10, 10))
    pump_done = asyncio.Event()

    async def pump() -> None:
        end = asyncio.get_running_loop().time() + 6.0
        while asyncio.get_running_loop().time() < end and not pump_done.is_set():
            webcam.push_frame(frame)
            await asyncio.sleep(1 / 30)

    pump_task = asyncio.ensure_future(pump())
    try:
        # Set the overlay only once frames are already flowing, mirroring a
        # caller conditioning a live session rather than one that hasn't
        # started yet.
        await asyncio.sleep(0.5)
        await reactor.send_command(
            "set_overlay_image",
            {"overlay_image": ref, "overlay_strength": 1.0},
        )

        frames: list = []
        main_video.on_frame(lambda f: frames.append(f))
        await wait_until(lambda: len(frames) >= 3, timeout=6.0)

        # overlay_strength=1.0 replaces the frame with the (resized) overlay
        # outright (echo_model.py's _overlay_image: addWeighted(frame, 0,
        # resized, 1, 0) == resized) — the webcam's own colour should not
        # show through at all.
        assert_dominant_color(frames[-1], overlay_color, tolerance=35)
    finally:
        pump_done.set()
        pump_task.cancel()
        webcam.unpublish()
