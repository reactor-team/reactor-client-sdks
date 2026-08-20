"""Tests for `download_clip`.

The interesting failure mode here is not unit-level — it's the real interaction
between `urllib` and a playlist that mixes relative segment names with the
absolute-path URIs the coordinator actually emits, where a plain string-concat
silently doubles the path instead of raising. Mocking `urlopen` would hide
exactly that, so most of this runs a real (loopback) HTTP server instead.
"""

from __future__ import annotations

import http.server
import json
import threading
import time
import urllib.error
import urllib.request
from collections.abc import Iterator
from unittest import mock

import pytest

from reactor_sdk import Clip, Reactor
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

#: Paths the fake coordinator serves only to a bearer token, like the real one.
_PROTECTED = {"/auth/clip.m3u8", "/auth/seg0.ts"}
_TOKEN = "test-token"

_AUTH_ROUTES = {
    "/auth/clip.m3u8": (b"#EXTM3U\n#EXTINF:4.0,\nseg0.ts\n", "application/vnd.apple.mpegurl"),
    "/auth/seg0.ts": (_SEG0, "video/mp2t"),
}

#: 202 until asked `_NOT_READY_TIMES` times, the way the coordinator behaves
#: while a clip's last chunk is still landing.
_NOT_READY_TIMES = 2
_not_ready_seen = 0


class _Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self) -> None:  # noqa: N802 - stdlib method name
        global _not_ready_seen

        if self.path == "/slow/clip.m3u8":
            if _not_ready_seen < _NOT_READY_TIMES:
                _not_ready_seen += 1
                self.send_response(202)
                # What the coordinator sends: the chunk length, in seconds.
                self.send_header("Retry-After", "0")
                self.end_headers()
                return
            route = _ROUTES["/hls/clip.m3u8"]
        elif self.path == "/never/clip.m3u8":
            self.send_response(202)
            self.send_header("Retry-After", "0")
            self.end_headers()
            return
        elif self.path in _PROTECTED:
            if self.headers.get("Authorization") != f"Bearer {_TOKEN}":
                self.send_response(401)
                self.end_headers()
                return
            route = _AUTH_ROUTES[self.path]
        else:
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


class TestAuthentication:
    """The coordinator serves playlists and segments behind auth — 401 without."""

    async def test_the_token_reaches_the_playlist_and_its_segments(self, server_url: str) -> None:
        clip = _clip(f"{server_url}/auth/clip.m3u8")
        assert await download_clip(clip, jwt=_TOKEN) == _SEG0

    async def test_without_a_token_the_playlist_is_unauthorized(self, server_url: str) -> None:
        clip = _clip(f"{server_url}/auth/clip.m3u8")
        with pytest.raises(urllib.error.HTTPError) as excinfo:
            await download_clip(clip)
        assert excinfo.value.code == 401

    async def test_a_segment_on_another_host_is_asked_without_the_token(self) -> None:
        """A presigned URL rejects an Authorization header rather than ignoring
        it, so the token stops at the playlist's own origin."""
        opened: list[tuple[str, dict[str, str]]] = []

        class _Resp:
            status = 200

            def read(self) -> bytes:
                return b"#EXTM3U\nhttps://elsewhere.example/seg0.ts\n"

            def __enter__(self) -> _Resp:
                return self

            def __exit__(self, *_: object) -> None:
                return None

        def fake_urlopen(request: urllib.request.Request, *a: object, **k: object) -> _Resp:
            opened.append((request.full_url, dict(request.headers)))
            return _Resp()

        with mock.patch("urllib.request.urlopen", fake_urlopen):
            await download_clip(_clip("https://coordinator.example/hls/clip.m3u8"), jwt=_TOKEN)

        playlist_headers, segment_headers = opened[0][1], opened[1][1]
        assert "Authorization" in playlist_headers
        assert "Authorization" not in segment_headers


class TestNotReadyYet:
    """202 means the clip's last chunk has not landed — not an error."""

    async def test_it_waits_out_the_202s(self, server_url: str) -> None:
        global _not_ready_seen
        _not_ready_seen = 0
        clip = _clip(f"{server_url}/slow/clip.m3u8")
        with pytest.raises(urllib.error.HTTPError):
            # /slow/seg0.ts does not exist — reaching a segment fetch at all is
            # the assertion: the 202s were waited out rather than raised on.
            await download_clip(clip)
        assert _not_ready_seen == _NOT_READY_TIMES

    async def test_it_gives_up_after_ready_timeout(self, server_url: str) -> None:
        clip = _clip(f"{server_url}/never/clip.m3u8")
        with pytest.raises(TimeoutError) as excinfo:
            await download_clip(clip, ready_timeout=0.3)
        # The message has to say what to do about it, and the rule that explains
        # the wait is the media-time one.
        assert "media time" in str(excinfo.value)
        assert "ready_timeout" in str(excinfo.value)

    async def test_none_waits_indefinitely(self, server_url: str) -> None:
        """The platform's own semantics: it becomes ready when it becomes ready."""
        global _not_ready_seen
        _not_ready_seen = 0
        clip = _clip(f"{server_url}/slow/clip.m3u8")
        with pytest.raises(urllib.error.HTTPError):
            # Reaching a segment fetch at all is the assertion — the 202s were
            # waited out with no deadline in play.
            await download_clip(clip, ready_timeout=None)
        assert _not_ready_seen == _NOT_READY_TIMES

    async def test_it_waits_what_retry_after_asks_for(self, server_url: str) -> None:
        """`Retry-After` is the chunk length; polling faster than that is noise."""
        slept: list[float] = []
        clip = _clip(f"{server_url}/never/clip.m3u8")

        real_sleep = time.sleep

        def spy(seconds: float) -> None:
            slept.append(seconds)
            real_sleep(0)

        with mock.patch("reactor_sdk._recording.time.sleep", spy):
            with pytest.raises(TimeoutError):
                await download_clip(clip, ready_timeout=0.3)
        # `Retry-After: 0` floors at 0.1s rather than spinning, and the last waits
        # are shorter only because the deadline caps them.
        assert slept[0] == 0.1
        assert max(slept) == 0.1


class TestReactorDownloadConvenience:
    """`Reactor.download_clip()` / `download_recording()` are `request_*()` +
    the module-level `download_clip()` in one call — tested through both
    halves for real (a real HTTP fetch of the resulting playlist), not just
    that the delegation happens."""

    def _reactor(self, monkeypatch: pytest.MonkeyPatch, server_url: str, kind: str) -> Reactor:
        payload = json.dumps(
            {
                "session_id": "s1",
                "kind": kind,
                "start_marker": 0.0,
                "end_marker": 10.0,
                "now_marker": 10.0,
                "predicted_ready_at_ms": 0.0,
                "playlist_url": f"{server_url}/hls/clip.m3u8",
            }
        ).encode()

        fake_lib = mock.Mock()
        fake_lib.reactor_request_clip = lambda h, duration, completion, ud: completion(
            1, payload, None, None
        )
        fake_lib.reactor_request_recording = lambda h, completion, ud: completion(
            1, payload, None, None
        )
        monkeypatch.setattr("reactor_sdk.client.get_lib", lambda: fake_lib)
        reactor = Reactor("m", jwt="fake")
        reactor._handle = 1234
        return reactor

    async def test_download_clip_requests_then_downloads(
        self, monkeypatch: pytest.MonkeyPatch, server_url: str
    ) -> None:
        reactor = self._reactor(monkeypatch, server_url, kind="clip")
        data = await reactor.download_clip(10)
        assert data == _SEG0 + _SEG1

    async def test_download_clip_streams_to_a_path(
        self, monkeypatch: pytest.MonkeyPatch, server_url: str, tmp_path: object
    ) -> None:
        reactor = self._reactor(monkeypatch, server_url, kind="clip")
        out = tmp_path / "clip.ts"  # type: ignore[operator]
        result = await reactor.download_clip(10, out)
        assert result is None
        assert out.read_bytes() == _SEG0 + _SEG1

    async def test_download_recording_requests_then_downloads(
        self, monkeypatch: pytest.MonkeyPatch, server_url: str
    ) -> None:
        reactor = self._reactor(monkeypatch, server_url, kind="recording")
        data = await reactor.download_recording()
        assert data == _SEG0 + _SEG1

    async def test_download_recording_streams_to_a_path(
        self, monkeypatch: pytest.MonkeyPatch, server_url: str, tmp_path: object
    ) -> None:
        reactor = self._reactor(monkeypatch, server_url, kind="recording")
        out = tmp_path / "recording.ts"  # type: ignore[operator]
        result = await reactor.download_recording(out)
        assert result is None
        assert out.read_bytes() == _SEG0 + _SEG1
