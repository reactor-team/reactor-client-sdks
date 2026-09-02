"""Upload a file, condition a command on it — example 02.

`upload_file` returns a `FileRef`; passed as a top-level value in a command's
`data`, `send_command` pulls it out and sends it as a separate upload
reference rather than embedding it in the JSON payload (`client.py`'s
`send_command` docstring). `reactor/echo`'s `set_overlay_image` is what this
suite has that actually consumes an upload and produces an assertable effect.
"""

from __future__ import annotations

import asyncio

from conftest import solid_rgb_frame, solid_rgb_png, wait_until

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

        # Pixel assertion disabled — REA-5931 (see README.md): a shared prod
        # worker can already be carrying a *different* session's leaked
        # overlay before this command even runs, so this session's own
        # output isn't a reliable thing to diff against. Same call
        # sdks/js/integration-tests/tests/tracks-and-upload.spec.ts already
        # made for its own set_overlay_image step. The command still goes
        # out above, keeping coverage that the SDK's own upload + send path
        # works; only the model-side visual verification is off.
    finally:
        pump_done.set()
        pump_task.cancel()
        webcam.unpublish()
