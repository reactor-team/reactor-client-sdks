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
import shutil
import time
import urllib.request
from collections.abc import Callable
from typing import TYPE_CHECKING, overload
from urllib.parse import urljoin, urlsplit

if TYPE_CHECKING:  # pragma: no cover - types only
    from .client import Clip

_log = logging.getLogger(__name__)


@overload
async def download_clip(
    clip: Clip,
    path: None = None,
    *,
    jwt: str | None = None,
    on_progress: Callable[[int, int], None] | None = None,
    ready_timeout: float | None = 60.0,
    while_live: Callable[[], bool] | None = None,
) -> bytes: ...
@overload
async def download_clip(
    clip: Clip,
    path: str | os.PathLike,
    *,
    jwt: str | None = None,
    on_progress: Callable[[int, int], None] | None = None,
    ready_timeout: float | None = 60.0,
    while_live: Callable[[], bool] | None = None,
) -> None: ...
async def download_clip(
    clip: Clip,
    path: str | os.PathLike | None = None,
    *,
    jwt: str | None = None,
    on_progress: Callable[[int, int], None] | None = None,
    ready_timeout: float | None = 60.0,
    while_live: Callable[[], bool] | None = None,
) -> bytes | None:
    """Fetch every segment `clip.playlist_url` names.

    Given `path`, streams straight to it and returns `None` — nothing is held
    in memory beyond one segment at a time, which matters for
    `request_recording()`: a full-session recording is exactly the case with
    no bound on size. Without `path`, returns the assembled bytes instead,
    which does mean holding the whole clip in memory — the tradeoff of asking
    for bytes instead of a file.

    Either way it's interleaved MPEG-TS, playable as-is by most players
    (`ffplay`, VLC, mpv). There is no MP4 assembly here: remux with
    ``ffmpeg -i <path> -c copy out.mp4`` afterward if you need that container.

    Args:
        clip: A `Clip` from `request_clip()` / `request_recording()`.
        path: Stream the download here instead of returning it. Unbounded
            memory use if omitted — fine for a clip, not for a long recording.
        jwt: Token for the coordinator, which serves the playlist and its
            segments behind auth — without it they answer 401. `Reactor`'s own
            `download()` / `download_clip()` / `download_recording()` pass the
            one they already hold; this argument is for callers assembling a
            clip without a live client.
        on_progress: Called after each segment, as `on_progress(done, total)`.
            Runs on the worker thread this dispatches to, not the event loop.
        while_live: Called before each retry; `False` ends the wait. The media
            clock only advances while the session generates, so a 202 after it
            has ended is a 202 forever. `Reactor.download()` passes its own
            status.
        ready_timeout: Grace period past `clip.predicted_ready_at_ms` before a
            202 becomes a `TimeoutError`. `None` polls until the coordinator
            stops saying 202 — the JS SDK's default, and `Reactor.download()`'s,
            which can afford it because `while_live` ends the wait. Bounded here
            because a caller without a live client has no such signal, and the
            work runs in a thread that cannot be interrupted.

            Treat any number as a guess. It is anchored on the runtime's own
            prediction, but that prediction adds *media* seconds to a wall
            clock: a model generating at a tenth of real-time reaches the
            boundary chunk ten times later than predicted.

    Raises:
        urllib.error.URLError: A fetch failed — the playlist itself, or one of
            its segments. `HTTPError` (a `URLError` subclass) for a non-2xx
            response, since `playlist_url` expires and clips are held for a
            limited time (see the Recordings guide).
        TimeoutError: The playlist was still 202 when `ready_timeout` ran out.
        ValueError: The playlist named no segments at all.
    """
    return await asyncio.to_thread(
        _download_clip_sync, clip, path, jwt, on_progress, ready_timeout, while_live
    )


def _open(url: str, jwt: str | None, *, same_origin_as: str | None = None):
    """GET `url`, bearing `jwt` when it is the coordinator being asked.

    The origin check matters: a segment can resolve to a presigned URL on
    another host, and presigned requests reject an Authorization header rather
    than ignoring it.
    """
    headers = {}
    if jwt and (same_origin_as is None or urlsplit(url).netloc == urlsplit(same_origin_as).netloc):
        headers["Authorization"] = f"Bearer {jwt}"
    return urllib.request.urlopen(urllib.request.Request(url, headers=headers))


#: Bounds on the per-poll wait, matching the JS SDK: a floor so a `Retry-After: 0`
#: is a retry rather than a spin, and a cap so a large one does not park the
#: download for minutes when the chunk may land sooner.
_MIN_RETRY_DELAY = 0.2
_MAX_RETRY_DELAY = 2.0


def _retry_delay(retry_after: str | None) -> float:
    """What to wait after a 202, from `Retry-After`, clamped.

    An HTTP-date is legal and unreadable here — turning one into a duration needs
    a trusted clock — so it falls back like a missing header.
    """
    try:
        delay = float(retry_after) if retry_after else _MAX_RETRY_DELAY
    except ValueError:
        delay = _MAX_RETRY_DELAY
    return min(max(delay, _MIN_RETRY_DELAY), _MAX_RETRY_DELAY)


def _playlist_segments(
    playlist_url: str,
    jwt: str | None,
    ready_timeout: float | None,
    predicted_ready_at_ms: float = 0.0,
    while_live: Callable[[], bool] | None = None,
) -> list[str]:
    """The segment names in the playlist, waiting out the 202s first.

    202 means the clip's boundary chunk has not been uploaded yet, which is not
    an error and not instant: recordings are cut into fixed-length chunks, and
    the one holding the end of the window has to close first. The response
    carries `Retry-After` — the chunk length — so that is what we wait.
    """
    # Anchored at the runtime's prediction while that is still ahead, the way the
    # JS SDK anchors its slack: the budget is grace past when the clip was
    # expected, not from whenever the download happened to start.
    if ready_timeout is None:
        deadline = None
    else:
        ahead = predicted_ready_at_ms / 1000 - time.time() if predicted_ready_at_ms else 0.0
        deadline = time.monotonic() + max(ahead, 0.0) + ready_timeout
    polls = 0
    while True:
        _log.debug("fetching playlist: %s", playlist_url)
        with _open(playlist_url, jwt) as resp:
            if resp.status != 202:
                playlist = resp.read().decode()
                break
            retry_after = resp.headers.get("Retry-After")
        polls += 1
        # The chunk closes because the model keeps producing. Once the session is
        # gone, no amount of waiting closes it.
        if while_live is not None and not while_live():
            raise TimeoutError(
                f"playlist at {playlist_url!r} was not ready when the session ended, "
                f"after {polls} polls. The chunk holding the end of the window had not "
                f"closed, and media time only advances while the session generates — so "
                f"this clip cannot become ready. Ask for the clip earlier, or keep the "
                f"session connected until the download finishes."
            )
        remaining = None if deadline is None else deadline - time.monotonic()
        if remaining is not None and remaining <= 0:
            raise TimeoutError(
                f"playlist at {playlist_url!r} was still not ready after "
                f"{ready_timeout:.0f}s and {polls} polls. Readiness is in media time, "
                f"not wall clock: the manifest appears once the recording passes the end "
                f"of the chunk holding the window. A model generating slower than "
                f"real-time takes proportionally longer, and a session that stops "
                f"generating never gets there — keep it running, or pass a longer "
                f"ready_timeout (None to wait indefinitely)."
            )
        delay = _retry_delay(retry_after)
        time.sleep(delay if remaining is None else min(delay, remaining))

    segments = [
        line.strip() for line in playlist.splitlines() if line.strip() and not line.startswith("#")
    ]
    if not segments:
        raise ValueError(f"playlist at {playlist_url!r} names no segments")
    return segments


def _segment_url(playlist_url: str, segment: str) -> str:
    # urljoin resolves both relative segment names and the absolute-path
    # URIs (e.g. "/clips/chunks/...") the coordinator emits — a plain
    # string-concat mishandles the latter into a doubled path.
    return urljoin(playlist_url, segment)


def _download_clip_sync(
    clip: Clip,
    path: str | os.PathLike | None,
    jwt: str | None,
    on_progress: Callable[[int, int], None] | None,
    ready_timeout: float | None,
    while_live: Callable[[], bool] | None = None,
) -> bytes | None:
    segments = _playlist_segments(
        clip.playlist_url, jwt, ready_timeout, clip.predicted_ready_at_ms, while_live
    )
    total = len(segments)

    if path is not None:
        with open(path, "wb") as out:
            for done, segment in enumerate(segments, start=1):
                url = _segment_url(clip.playlist_url, segment)
                with _open(url, jwt, same_origin_as=clip.playlist_url) as resp:
                    # Streamed straight into the file rather than read() then
                    # written — a full-session recording is exactly the case
                    # with no bound on size, and there is no need to ever hold
                    # more than one segment of it at a time.
                    shutil.copyfileobj(resp, out)
                if on_progress is not None:
                    on_progress(done, total)
        return None

    chunks: list[bytes] = []
    for done, segment in enumerate(segments, start=1):
        url = _segment_url(clip.playlist_url, segment)
        with _open(url, jwt, same_origin_as=clip.playlist_url) as resp:
            chunks.append(resp.read())
        if on_progress is not None:
            on_progress(done, total)
    return b"".join(chunks)
