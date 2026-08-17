"""Tests for `download_clip`.

The interesting failure mode here is not unit-level — it's the real interaction
between `urllib` and a playlist that mixes relative segment names with the
absolute-path URIs the coordinator actually emits, where a plain string-concat
silently doubles the path instead of raising. Mocking `urlopen` would hide
exactly that, so most of this runs a real (loopback) HTTP server instead.
"""

from __future__ import annotations

import http.server
import threading
from collections.abc import Iterator

import pytest

from reactor_sdk import Clip
from reactor_sdk._recording import download_clip

# Distinct, length-different payloads so a swapped or duplicated segment shows
# up as a content mismatch, not just a size difference that could hide it.
_SEG0 = b"segment-zero-bytes"
_SEG1 = b"segment-one-bytes-but-longer"

_ROUTES = {
    # A relative name (resolves against the playlist's own URL) next to an
    # absolute-path one (the shape the coordinator emits) — the exact mix
    # urljoin has to get right.
    "/hls/clip.m3u8": (
        b"#EXTM3U\n#EXTINF:4.0,\nseg0.ts\n#EXTINF:4.0,\n/clips/chunks/seg1.ts\n",
        "application/vnd.apple.mpegurl",
    ),
    "/hls/seg0.ts": (_SEG0, "video/mp2t"),
    "/clips/chunks/seg1.ts": (_SEG1, "video/mp2t"),
    "/hls/empty.m3u8": (b"#EXTM3U\n#EXT-X-ENDLIST\n", "application/vnd.apple.mpegurl"),
}


class _Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self) -> None:  # noqa: N802 - stdlib method name
        route = _ROUTES.get(self.path)
        if route is None:
            self.send_response(404)
            self.end_headers()
            return
        body, content_type = route
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args: object) -> None:  # quiet
        pass


@pytest.fixture()
def server_url() -> Iterator[str]:
    server = http.server.HTTPServer(("127.0.0.1", 0), _Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}"
    finally:
        server.shutdown()
        thread.join(timeout=5)


def _clip(playlist_url: str) -> Clip:
    return Clip(
        session_id="s1",
        kind="clip",
        start_marker=0.0,
        end_marker=10.0,
        now_marker=10.0,
        predicted_ready_at_ms=0.0,
        playlist_url=playlist_url,
    )


class TestDownloadClip:
    """`download_clip` is `async def` — a thin `asyncio.to_thread` wrapper
    around the sync fetch below, so callers get `await download_clip(...)` in
    one step instead of wrapping it themselves on every call site."""

    async def test_concatenates_segments_in_order(self, server_url: str) -> None:
        data = await download_clip(_clip(f"{server_url}/hls/clip.m3u8"))
        assert data == _SEG0 + _SEG1

    async def test_resolves_the_absolute_path_segment_without_doubling_it(
        self, server_url: str
    ) -> None:
        """The regression this suite exists for: a plain `base + segment`
        string-concat would request `/hls/clips/chunks/seg1.ts` (404) instead
        of `/clips/chunks/seg1.ts`."""
        data = await download_clip(_clip(f"{server_url}/hls/clip.m3u8"))
        assert _SEG1 in data

    async def test_writes_to_path_when_given(self, server_url: str, tmp_path: object) -> None:
        out = tmp_path / "clip.ts"  # type: ignore[operator]
        await download_clip(_clip(f"{server_url}/hls/clip.m3u8"), out)
        assert out.read_bytes() == _SEG0 + _SEG1

    async def test_returns_none_when_a_path_is_given(
        self, server_url: str, tmp_path: object
    ) -> None:
        """The whole point of taking a path: the caller gets a file, not also
        a second full copy of it sitting in memory as a return value."""
        out = tmp_path / "clip.ts"  # type: ignore[operator]
        result = await download_clip(_clip(f"{server_url}/hls/clip.m3u8"), out)
        assert result is None

    async def test_a_path_download_streams_each_segment_rather_than_buffering(
        self, server_url: str, tmp_path: object, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """Pins the actual fix, not just its outward result: each segment's
        response is handed to `shutil.copyfileobj` once, directly — proving the
        path-given branch never assembles a `list[bytes]` plus a joined copy
        the way the no-path branch does."""
        import shutil

        calls: list[object] = []
        real_copyfileobj = shutil.copyfileobj

        def spy(fsrc: object, fdst: object, *a: object, **kw: object) -> None:
            calls.append(fsrc)
            real_copyfileobj(fsrc, fdst, *a, **kw)

        monkeypatch.setattr(shutil, "copyfileobj", spy)
        out = tmp_path / "clip.ts"  # type: ignore[operator]
        await download_clip(_clip(f"{server_url}/hls/clip.m3u8"), out)
        assert len(calls) == 2  # one call per segment, none reused

    async def test_returns_bytes_without_a_path(self, server_url: str) -> None:
        data = await download_clip(_clip(f"{server_url}/hls/clip.m3u8"))
        assert isinstance(data, bytes)
        assert len(data) == len(_SEG0) + len(_SEG1)

    async def test_progress_is_reported_per_segment_in_order(self, server_url: str) -> None:
        calls: list[tuple[int, int]] = []
        await download_clip(
            _clip(f"{server_url}/hls/clip.m3u8"),
            on_progress=lambda done, total: calls.append((done, total)),
        )
        assert calls == [(1, 2), (2, 2)]

    async def test_no_progress_callback_is_fine(self, server_url: str) -> None:
        await download_clip(_clip(f"{server_url}/hls/clip.m3u8"))  # must not raise

    async def test_an_empty_playlist_raises_value_error(self, server_url: str) -> None:
        with pytest.raises(ValueError, match="no segments"):
            await download_clip(_clip(f"{server_url}/hls/empty.m3u8"))

    async def test_a_missing_playlist_raises_the_http_error(self, server_url: str) -> None:
        import urllib.error

        with pytest.raises(urllib.error.HTTPError) as excinfo:
            await download_clip(_clip(f"{server_url}/hls/does-not-exist.m3u8"))
        assert excinfo.value.code == 404

    async def test_a_missing_segment_raises_the_http_error(self, server_url: str) -> None:
        """The playlist resolves; a segment 404s mid-download."""
        import urllib.error

        broken = f"{server_url}/hls/broken.m3u8"
        _ROUTES["/hls/broken.m3u8"] = (b"#EXTM3U\nseg0.ts\nmissing.ts\n", "application/x-mpegurl")
        try:
            with pytest.raises(urllib.error.HTTPError) as excinfo:
                await download_clip(_clip(broken))
            assert excinfo.value.code == 404
        finally:
            del _ROUTES["/hls/broken.m3u8"]

    async def test_runs_off_the_event_loop_thread(self, server_url: str) -> None:
        """The whole point of making this `async def`: the fetch must not
        block the loop, which a naive `async def` wrapping a synchronous body
        directly (no `to_thread`) would do."""
        loop_thread = threading.current_thread()
        seen_from: list[threading.Thread] = []

        def on_progress(_done: int, _total: int) -> None:
            seen_from.append(threading.current_thread())

        await download_clip(_clip(f"{server_url}/hls/clip.m3u8"), on_progress=on_progress)

        assert seen_from and all(t is not loop_thread for t in seen_from)
