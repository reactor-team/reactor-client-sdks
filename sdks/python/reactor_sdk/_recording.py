"""Download a clip's HLS segments into a single file, or into memory.

Reactor does not host clips: `Clip.playlist_url` is a short-lived HLS media
playlist naming the `.ts` segments that make it up, and it is on the caller to
fetch and assemble them. Deliberately built on :mod:`urllib.request`, matching
`_auth.py` — the SDK has no runtime dependencies, and a handful of GETs made
once per clip is not worth changing that.

`download_clip` is `async def`, but the fetching itself is not: it runs the
synchronous body in a thread with `asyncio.to_thread`, the same tradeoff
`_auth.py`'s `fetch_jwt` makes, just made once here instead of pushed onto
every caller. `on_progress`, if given, is therefore called from that thread,
not the event loop — fine for the counter or log line it exists for, but not
a place to touch anything that isn't thread-safe.
"""

from __future__ import annotations

import asyncio
import logging
import os
import urllib.request
from collections.abc import Callable
from typing import TYPE_CHECKING
from urllib.parse import urljoin

if TYPE_CHECKING:  # pragma: no cover - types only
    from .client import Clip

_log = logging.getLogger(__name__)


async def download_clip(
    clip: Clip,
    path: str | os.PathLike | None = None,
    *,
    on_progress: Callable[[int, int], None] | None = None,
) -> bytes:
    """Fetch every segment `clip.playlist_url` names and concatenate them.

    Returns the assembled bytes — interleaved MPEG-TS, playable as-is by most
    players (`ffplay`, VLC, mpv) — and writes them to `path` too, if given.
    There is no MP4 assembly here: remux with ``ffmpeg -i <path> -c copy
    out.mp4`` afterward if you need that specific container.

    Args:
        clip: A `Clip` from `request_clip()` / `request_recording()`.
        path: Where to also write the assembled bytes. Left unwritten if `None`.
        on_progress: Called after each segment, as `on_progress(done, total)`.
            Runs on the worker thread this dispatches to, not the event loop.

    Raises:
        urllib.error.URLError: A fetch failed — the playlist itself, or one of
            its segments. `HTTPError` (a `URLError` subclass) for a non-2xx
            response, since `playlist_url` expires and clips are held for a
            limited time (see the Recordings guide).
        ValueError: The playlist named no segments at all.
    """
    return await asyncio.to_thread(_download_clip_sync, clip, path, on_progress)


def _download_clip_sync(
    clip: Clip,
    path: str | os.PathLike | None,
    on_progress: Callable[[int, int], None] | None,
) -> bytes:
    _log.debug("fetching playlist: %s", clip.playlist_url)
    with urllib.request.urlopen(clip.playlist_url) as resp:
        playlist = resp.read().decode()

    segments = [
        line.strip() for line in playlist.splitlines() if line.strip() and not line.startswith("#")
    ]
    if not segments:
        raise ValueError(f"playlist at {clip.playlist_url!r} names no segments")

    total = len(segments)
    chunks: list[bytes] = []
    for done, segment in enumerate(segments, start=1):
        # urljoin resolves both relative segment names and the absolute-path
        # URIs (e.g. "/clips/chunks/...") the coordinator emits — a plain
        # string-concat mishandles the latter into a doubled path.
        url = urljoin(clip.playlist_url, segment)
        with urllib.request.urlopen(url) as resp:
            chunks.append(resp.read())
        if on_progress is not None:
            on_progress(done, total)

    data = b"".join(chunks)
    if path is not None:
        with open(path, "wb") as out:
            out.write(data)
    return data
