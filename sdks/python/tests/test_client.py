"""Tests for the parts of the client that need no native library.

`reactor._ffi` loads `libreactor_ffi` lazily — `get_lib()` is what triggers
`ctypes.CDLL`, not the import — so everything a `Reactor` does before `connect()`
is testable without a build. That is worth keeping true, and the first test pins
it: if importing the package started loading the dylib, `pip install reactor-sdk`
would fail on a machine that has no built library rather than at the point of use.

Tests that do need the library live in `test_ffi_bindings.py` and skip without it.
"""

from __future__ import annotations

import asyncio
import ctypes
import json
import threading
from unittest import mock

import pytest

from reactor_sdk import Clip, FileRef, Reactor, ReactorError


class TestLazyLoading:
    def test_constructing_a_reactor_does_not_load_the_library(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """Asserted by making a load fail loudly rather than by inspecting the
        cache, so the test does not depend on whether some other module already
        loaded the library.
        """
        import reactor_sdk._ffi as ffi

        def explode() -> None:
            raise AssertionError(
                "constructing a Reactor loaded the native library; it must stay "
                "lazy so `import reactor` works without a build"
            )

        monkeypatch.setattr(ffi, "_lib", None)
        monkeypatch.setattr(ffi, "_load", explode)

        Reactor("https://api.reactor.inc", "some-model")


class TestStateWithoutAHandle:
    """A client that never connected answers from Python, without calling in."""

    def test_status_is_disconnected(self) -> None:
        assert Reactor("https://api.reactor.inc", "m").status == "disconnected"

    def test_session_id_is_none(self) -> None:
        assert Reactor("https://api.reactor.inc", "m").session_id is None

    @pytest.mark.parametrize(
        "call",
        [
            pytest.param(lambda r: r.unpublish_track("video"), id="unpublish_track"),
            pytest.param(
                lambda r: r.push_video_frame("video", b"\x00" * 4, 1, 1),
                id="push_video_frame",
            ),
            pytest.param(
                lambda r: r.push_audio_frame("audio", b"\x00" * 4, 2),
                id="push_audio_frame",
            ),
        ],
    )
    def test_operations_requiring_a_handle_raise(self, call) -> None:
        reactor = Reactor("https://api.reactor.inc", "m")
        with pytest.raises(RuntimeError, match="handle not created"):
            call(reactor)

    async def test_send_command_requires_a_handle(self) -> None:
        reactor = Reactor("https://api.reactor.inc", "m")
        with pytest.raises(RuntimeError, match="handle not created"):
            await reactor.send_command("hello", {})


class TestHandlerRegistry:
    def test_fire_delivers_to_every_registered_handler_in_order(self) -> None:
        reactor = Reactor("https://api.reactor.inc", "m")
        seen: list[str] = []
        reactor.on("status_changed", lambda s: seen.append(f"a:{s}"))
        reactor.on("status_changed", lambda s: seen.append(f"b:{s}"))

        reactor._fire("status_changed", "ready")

        assert seen == ["a:ready", "b:ready"]

    def test_fire_with_no_handlers_is_a_noop(self) -> None:
        Reactor("https://api.reactor.inc", "m")._fire("status_changed", "ready")

    def test_off_removes_only_the_given_handler(self) -> None:
        reactor = Reactor("https://api.reactor.inc", "m")
        seen: list[str] = []

        def keep(status: str) -> None:
            seen.append(status)

        def drop(status: str) -> None:
            seen.append("should-not-run")

        reactor.on("status_changed", keep)
        reactor.on("status_changed", drop)
        reactor.off("status_changed", drop)

        reactor._fire("status_changed", "ready")

        assert seen == ["ready"]

    def test_off_of_an_unregistered_handler_is_a_noop(self) -> None:
        Reactor("https://api.reactor.inc", "m").off("status_changed", lambda _: None)

    def test_a_raising_handler_does_not_stop_the_others(
        self, caplog: pytest.LogCaptureFixture
    ) -> None:
        """One broken handler must not take the event down with it — and must not
        vanish either. The exception used to be swallowed outright, which is what made
        a buggy handler invisible."""
        reactor = Reactor("https://api.reactor.inc", "m")
        seen: list[str] = []

        def boom(_: str) -> None:
            raise ValueError("handler is broken")

        reactor.on("status_changed", boom)
        reactor.on("status_changed", lambda s: seen.append(s))

        with caplog.at_level("ERROR", logger="reactor_sdk.client"):
            reactor._fire("status_changed", "ready")

        assert seen == ["ready"]
        assert "handler is broken" in caplog.text

    def test_handlers_are_per_event_name(self) -> None:
        reactor = Reactor("https://api.reactor.inc", "m")
        seen: list[str] = []
        reactor.on("message", lambda _: seen.append("message"))

        reactor._fire("status_changed", "ready")

        assert seen == []


class TestErrorPayload:
    def test_str_names_the_component_and_code(self) -> None:
        err = ReactorError(
            code="SESSION_LOST",
            message="the session went away",
            timestamp_ms=1.0,
            recoverable=True,
            component="signaling",
        )
        assert str(err) == "[signaling:SESSION_LOST] the session went away"

    def test_retry_after_is_optional(self) -> None:
        err = ReactorError(
            code="X", message="", timestamp_ms=0.0, recoverable=False, component="api"
        )
        assert err.retry_after_ms is None


class TestJsonContracts:
    """`Clip(**result)` and `FileRef(**result)` splat FFI JSON straight into a
    dataclass, so a field the Rust side renames becomes a `TypeError` at runtime
    rather than a caught error. These pin the field names both layers must agree on.
    """

    def test_clip_fields_match_the_ffi_payload(self) -> None:
        payload = {
            "session_id": "sess-1",
            "kind": "clip",
            "start_marker": 0.0,
            "end_marker": 10.0,
            "now_marker": 10.0,
            "predicted_ready_at_ms": 1234.0,
            "playlist_url": "https://example.invalid/p.m3u8",
        }
        clip = Clip(**payload)
        assert clip.session_id == "sess-1"
        assert clip.playlist_url.endswith(".m3u8")

    def test_clip_rejects_an_unexpected_field(self) -> None:
        with pytest.raises(TypeError):
            Clip(unexpected="field")  # type: ignore[call-arg]

    def test_file_ref_fields_match_the_ffi_payload(self) -> None:
        ref = FileRef(upload_id="up-1", name="a.png", mime_type="image/png", size=12)
        assert (ref.upload_id, ref.size) == ("up-1", 12)


class TestSendCommandUploads:
    """`FileRef` values in `data` must reach the FFI as `uploads_json`, not get
    serialised inline — the gap this closes: `json.dumps` cannot serialise a
    `FileRef` at all, so a command with a file parameter was unreachable before.
    """

    @pytest.fixture(autouse=True)
    def _no_real_destroy(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """`_handle` below is a fake int, not a real native pointer — `__del__`
        must not hand it to the real `reactor_destroy` at GC time."""
        monkeypatch.setattr(Reactor, "_destroy_handle", lambda self: None)

    def _reactor(self) -> Reactor:
        reactor = Reactor("https://api.reactor.inc", "m")
        reactor._handle = 1234
        return reactor

    async def test_a_fileref_value_is_pulled_out_into_uploads_json(self) -> None:
        captured: dict[str, object] = {}

        def fake_send_command(handle, name, args_json, uploads_json, completion, userdata):
            captured["name"] = name
            captured["args_json"] = args_json
            captured["uploads_json"] = uploads_json
            completion(1, b'{"type": "set_image", "data": {}}', None, None)

        fake_lib = mock.Mock()
        fake_lib.reactor_send_command = fake_send_command
        reactor = self._reactor()

        ref = FileRef(upload_id="up_1", name="a.jpg", mime_type="image/jpeg", size=3)
        with mock.patch("reactor_sdk.client.get_lib", return_value=fake_lib):
            reply = await reactor.send_command("set_image", {"image": ref, "caption": "hi"})

        assert reply == {"type": "set_image", "data": {}}
        assert captured["name"] == b"set_image"
        assert json.loads(captured["args_json"]) == {"caption": "hi"}
        assert json.loads(captured["uploads_json"]) == {
            "image": {
                "upload_id": "up_1",
                "name": "a.jpg",
                "mime_type": "image/jpeg",
                "size": 3,
            }
        }

    async def test_no_fileref_sends_no_uploads_json(self) -> None:
        captured: dict[str, object] = {}

        def fake_send_command(handle, name, args_json, uploads_json, completion, userdata):
            captured["uploads_json"] = uploads_json
            completion(1, b"{}", None, None)

        fake_lib = mock.Mock()
        fake_lib.reactor_send_command = fake_send_command
        reactor = self._reactor()

        with mock.patch("reactor_sdk.client.get_lib", return_value=fake_lib):
            await reactor.send_command("set_prompt", {"prompt": "a forest"})

        assert captured["uploads_json"] is None

    async def test_multiple_filerefs_all_reach_uploads_json(self) -> None:
        captured: dict[str, object] = {}

        def fake_send_command(handle, name, args_json, uploads_json, completion, userdata):
            captured["args_json"] = args_json
            captured["uploads_json"] = uploads_json
            completion(1, b"{}", None, None)

        fake_lib = mock.Mock()
        fake_lib.reactor_send_command = fake_send_command
        reactor = self._reactor()

        ref_a = FileRef(upload_id="up_a", name="a.jpg", mime_type="image/jpeg", size=1)
        ref_b = FileRef(upload_id="up_b", name="b.jpg", mime_type="image/jpeg", size=2)
        with mock.patch("reactor_sdk.client.get_lib", return_value=fake_lib):
            await reactor.send_command("set_images", {"front": ref_a, "back": ref_b})

        assert json.loads(captured["args_json"]) == {}
        assert set(json.loads(captured["uploads_json"])) == {"front", "back"}


class TestUploadFileDispatch:
    """`upload_file` accepts a path, raw bytes, or a file-like object — a path
    goes to the existing path-based FFI call; anything else is read into memory
    and goes to `reactor_upload_bytes`, added because the Rust core underneath
    was already byte-based (`Reactor::upload_file(name, mime_type, bytes)`) —
    only the old FFI wrapper forced a filesystem path.
    """

    @pytest.fixture(autouse=True)
    def _no_real_destroy(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """`_handle` below is a fake int, not a real native pointer — `__del__`
        must not hand it to the real `reactor_destroy` at GC time."""
        monkeypatch.setattr(Reactor, "_destroy_handle", lambda self: None)

    def _reactor(self) -> Reactor:
        reactor = Reactor("https://api.reactor.inc", "m")
        reactor._handle = 1234
        return reactor

    async def test_a_path_string_uses_the_path_based_ffi_call(self, tmp_path) -> None:
        f = tmp_path / "photo.jpg"
        f.write_bytes(b"jpeg-bytes")
        captured: dict[str, object] = {}

        def fake_upload_file(handle, path, completion, userdata):
            captured["path"] = path
            completion(
                1,
                b'{"upload_id": "up_1", "name": "photo.jpg", '
                b'"mime_type": "image/jpeg", "size": 10}',
                None,
                None,
            )

        fake_lib = mock.Mock()
        fake_lib.reactor_upload_file = fake_upload_file
        fake_lib.reactor_upload_bytes = mock.Mock(
            side_effect=AssertionError("should not be called for a path")
        )
        reactor = self._reactor()

        with mock.patch("reactor_sdk.client.get_lib", return_value=fake_lib):
            ref = await reactor.upload_file(str(f))

        assert captured["path"] == str(f).encode()
        assert ref.upload_id == "up_1"

    async def test_raw_bytes_use_the_bytes_based_ffi_call(self) -> None:
        captured: dict[str, object] = {}

        def fake_upload_bytes(handle, data, length, name, mime_type, completion, userdata):
            captured["data"] = bytes(ctypes.cast(data, ctypes.POINTER(ctypes.c_uint8 * length))[0])
            captured["name"] = name
            captured["mime_type"] = mime_type
            completion(
                1,
                b'{"upload_id": "up_2", "name": "upload", '
                b'"mime_type": "application/octet-stream", "size": 5}',
                None,
                None,
            )

        fake_lib = mock.Mock()
        fake_lib.reactor_upload_bytes = fake_upload_bytes
        reactor = self._reactor()

        with mock.patch("reactor_sdk.client.get_lib", return_value=fake_lib):
            ref = await reactor.upload_file(b"hello")

        assert captured["data"] == b"hello"
        assert captured["name"] == b"upload"
        assert ref.upload_id == "up_2"

    async def test_a_file_like_object_infers_its_name_and_mime_type(self) -> None:
        import io

        captured: dict[str, object] = {}

        def fake_upload_bytes(handle, data, length, name, mime_type, completion, userdata):
            captured["data"] = bytes(ctypes.cast(data, ctypes.POINTER(ctypes.c_uint8 * length))[0])
            captured["name"] = name
            captured["mime_type"] = mime_type
            completion(
                1, b'{"upload_id": "up_3", "name": "x", "mime_type": "y", "size": 1}', None, None
            )

        fake_lib = mock.Mock()
        fake_lib.reactor_upload_bytes = fake_upload_bytes
        reactor = self._reactor()

        buf = io.BytesIO(b"png-bytes")
        buf.name = "diagram.png"
        with mock.patch("reactor_sdk.client.get_lib", return_value=fake_lib):
            await reactor.upload_file(buf)

        assert captured["data"] == b"png-bytes"
        assert captured["name"] == b"diagram.png"
        assert captured["mime_type"] == b"image/png"

    async def test_name_and_mime_type_overrides_are_honoured(self) -> None:
        captured: dict[str, object] = {}

        def fake_upload_bytes(handle, data, length, name, mime_type, completion, userdata):
            captured["name"] = name
            captured["mime_type"] = mime_type
            completion(
                1, b'{"upload_id": "up_4", "name": "x", "mime_type": "y", "size": 1}', None, None
            )

        fake_lib = mock.Mock()
        fake_lib.reactor_upload_bytes = fake_upload_bytes
        reactor = self._reactor()

        with mock.patch("reactor_sdk.client.get_lib", return_value=fake_lib):
            await reactor.upload_file(b"data", name="custom.bin", mime_type="application/x-custom")

        assert captured["name"] == b"custom.bin"
        assert captured["mime_type"] == b"application/x-custom"


class TestLoopDispatch:
    """Control events reach handlers on the loop thread, not on the caller's.

    This is what makes `asyncio.Event.set()` in an `on_status` handler correct — the
    pattern every example in this repo uses, and which was previously mutating loop
    state from a native thread.
    """

    @pytest.mark.asyncio
    async def test_control_events_run_on_the_loop_thread(self) -> None:
        reactor = Reactor("https://api.reactor.inc", "m")
        reactor._loop = asyncio.get_running_loop()
        ran_on: list[int] = []
        reactor.on("status_changed", lambda _s: ran_on.append(threading.get_ident()))

        done = asyncio.Event()
        reactor.on("status_changed", lambda _s: done.set())

        # Fire from a worker thread, as the FFI's control thread would.
        worker = threading.Thread(target=reactor._fire_on_loop, args=("status_changed", "ready"))
        worker.start()
        worker.join()

        await asyncio.wait_for(done.wait(), timeout=2)
        assert ran_on == [threading.get_ident()], (
            "handler ran on the firing thread instead of the loop thread"
        )

    @pytest.mark.asyncio
    async def test_an_asyncio_event_can_be_set_from_a_handler(self) -> None:
        """The exact shape used by examples/main.py and five siblings."""
        reactor = Reactor("https://api.reactor.inc", "m")
        reactor._loop = asyncio.get_running_loop()
        ready = asyncio.Event()

        def on_status(status: str) -> None:
            if status == "ready":
                ready.set()

        reactor.on("status_changed", on_status)
        threading.Thread(target=reactor._fire_on_loop, args=("status_changed", "ready")).start()

        await asyncio.wait_for(ready.wait(), timeout=2)

    def test_without_a_loop_it_falls_back_to_inline(self) -> None:
        reactor = Reactor("https://api.reactor.inc", "m")
        seen: list[str] = []
        reactor.on("status_changed", seen.append)

        reactor._fire_on_loop("status_changed", "ready")

        assert seen == ["ready"], "no loop available, so it should have run inline"

    def test_a_closed_loop_falls_back_to_inline(self) -> None:
        loop = asyncio.new_event_loop()
        loop.close()

        reactor = Reactor("https://api.reactor.inc", "m")
        reactor._loop = loop
        seen: list[str] = []
        reactor.on("status_changed", seen.append)

        reactor._fire_on_loop("status_changed", "ready")

        assert seen == ["ready"]

    def test_no_handlers_means_nothing_is_scheduled(self) -> None:
        reactor = Reactor("https://api.reactor.inc", "m")
        reactor._loop = None
        reactor._fire_on_loop("status_changed", "ready")


class TestSettleFromForeignThread:
    """The completion path must never raise into the trampoline: ctypes would print
    the traceback and return, leaving the awaiting coroutine hung forever."""

    @pytest.mark.asyncio
    async def test_resolves_a_pending_future(self) -> None:
        from reactor_sdk.client import _settle_from_foreign_thread

        loop = asyncio.get_running_loop()
        future: asyncio.Future = loop.create_future()

        _settle_from_foreign_thread(loop, future, {"ok": True}, None)

        assert await asyncio.wait_for(future, timeout=2) == {"ok": True}

    @pytest.mark.asyncio
    async def test_delivers_an_error(self) -> None:
        from reactor_sdk.client import ReactorFFIError, _settle_from_foreign_thread

        loop = asyncio.get_running_loop()
        future: asyncio.Future = loop.create_future()

        _settle_from_foreign_thread(loop, future, None, ReactorFFIError("boom"))

        with pytest.raises(ReactorFFIError, match="boom"):
            await asyncio.wait_for(future, timeout=2)

    @pytest.mark.asyncio
    async def test_a_cancelled_future_is_left_alone(self) -> None:
        """A timed-out `wait_for` cancels the future before the completion lands.
        Setting a result on it would raise InvalidStateError inside the trampoline."""
        from reactor_sdk.client import _settle_from_foreign_thread

        loop = asyncio.get_running_loop()
        future: asyncio.Future = loop.create_future()
        future.cancel()

        _settle_from_foreign_thread(loop, future, {"late": True}, None)
        await asyncio.sleep(0)  # let the scheduled callback run

        assert future.cancelled()

    def test_a_closed_loop_is_tolerated(self) -> None:
        from reactor_sdk.client import _settle_from_foreign_thread

        loop = asyncio.new_event_loop()
        future: asyncio.Future = loop.create_future()
        loop.close()

        # Must not raise; there is nobody left to wake.
        _settle_from_foreign_thread(loop, future, {"late": True}, None)


class TestExitHook:
    def test_clients_without_a_handle_are_not_registered(self) -> None:
        from reactor_sdk.client import _LIVE_CLIENTS

        reactor = Reactor("https://api.reactor.inc", "m")
        assert reactor not in _LIVE_CLIENTS

    def test_the_exit_hook_tolerates_a_failing_close(self) -> None:
        """It runs during shutdown, where raising would be noise at best."""
        from reactor_sdk.client import _LIVE_CLIENTS, _close_live_clients

        class Exploding(Reactor):
            def close(self) -> None:
                raise RuntimeError("nope")

        exploding = Exploding("https://api.reactor.inc", "m")
        _LIVE_CLIENTS.add(exploding)
        try:
            _close_live_clients()
        finally:
            _LIVE_CLIENTS.discard(exploding)

    def test_del_on_a_client_without_a_handle_is_quiet(self) -> None:
        Reactor("https://api.reactor.inc", "m").__del__()
