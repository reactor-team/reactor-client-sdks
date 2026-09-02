"""Request a clip, download it — example 06.

Readiness is in media time, not wall clock (`_recording.py`'s
`predicted_ready_at_ms` note, and `download()`'s own docstring): the manifest
appears once the recording passes the end of the chunk holding the window.
Confirmed empirically, the hard way: a first version of this file unpublished
`webcam` — stopping generation entirely — *before* requesting the clip, on
the theory that the requested window was already safely in the past. It
isn't. `reactor/echo`'s `run()` only advances (and only closes a chunk) while
it keeps reading input, so a "snap" clip's boundary chunk — which always ends
at *now* (see the `sdk-from-ffi` skill's own note on this) — never closes
once the model has nothing left to read, and `download()` timed out at 30s
every time. Generation has to keep running until after the clip is
requested, not just up to it.
"""

from __future__ import annotations

import asyncio

from conftest import solid_rgb_frame

from reactor_sdk import Reactor

WIDTH, HEIGHT = 64, 64
CLIP_SECONDS = 3.0


async def _pump_until(track, stop: asyncio.Event) -> None:
    frame = solid_rgb_frame(WIDTH, HEIGHT, (80, 40, 200))
    while not stop.is_set():
        track.push_frame(frame)
        await asyncio.sleep(1 / 30)


async def test_request_clip_and_download_produces_a_playable_file(reactor: Reactor) -> None:
    webcam = await reactor.publish_track("webcam")
    stop = asyncio.Event()
    pump = asyncio.ensure_future(_pump_until(webcam, stop))
    try:
        # Generate past the window this test will ask for before asking, so
        # the window itself is already fully generated — but keep pumping
        # (below) rather than unpublishing, so the *boundary chunk* still has
        # something to close.
        await asyncio.sleep(CLIP_SECONDS + 2.0)

        clip = await reactor.request_clip(CLIP_SECONDS)
        assert clip.session_id == reactor.session_id
        assert clip.playlist_url

        data = await reactor.download(clip, ready_timeout=30.0)
    finally:
        stop.set()
        pump.cancel()
        webcam.unpublish()

    assert isinstance(data, bytes)
    assert len(data) > 0
    # Fragmented MP4: the init segment (ftyp/moov) goes in first — see
    # sdks/js/integration/README.md's "playlist is fragmented MP4" note,
    # equally true here since it's the same coordinator-served format.
    assert b"ftyp" in data[:256], "downloaded clip does not start with an MP4 init segment"


async def test_request_recording_covers_the_whole_session(reactor: Reactor) -> None:
    webcam = await reactor.publish_track("webcam")
    stop = asyncio.Event()
    pump = asyncio.ensure_future(_pump_until(webcam, stop))
    try:
        await asyncio.sleep(CLIP_SECONDS)

        clip = await reactor.request_recording()
        assert clip.start_marker == 0.0
        assert clip.session_id == reactor.session_id

        data = await reactor.download(clip, ready_timeout=30.0)
    finally:
        stop.set()
        pump.cancel()
        webcam.unpublish()

    assert isinstance(data, bytes)
    assert len(data) > 0
