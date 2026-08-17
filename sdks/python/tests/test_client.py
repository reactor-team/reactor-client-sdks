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
    def test_str_names_the_code(self) -> None:
        err = ReactorError(
            code="DISCONNECTED",
            message="the session went away",
            timestamp_ms=1.0,
            recoverable=True,
        )
        assert str(err) == "[DISCONNECTED] the session went away"

    def test_str_names_the_operation_when_there_was_one(self) -> None:
        """What the event's old code carried — CONNECTION_FAILED said `connect`
        failed — now that the code says what went wrong instead."""
        err = ReactorError(
            code="UNAUTHORIZED",
            message="401",
            timestamp_ms=1.0,
            recoverable=False,
            operation="connect",
        )
        assert str(err) == "connect: [UNAUTHORIZED] 401"

    def test_everything_but_the_failure_itself_is_optional(self) -> None:
        err = ReactorError(code="X", message="", timestamp_ms=0.0, recoverable=False)
        assert (err.retry_after_ms, err.status, err.operation) == (None, None, None)


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

    async def test_a_path_with_overrides_reads_the_file_and_uses_bytes(self, tmp_path) -> None:
        """`reactor_upload_file` derives name/mime_type from the path itself with no
        way to override either — a path with an override must not silently ignore
        it, so it goes through the bytes-based call instead, same as any other
        override."""
        f = tmp_path / "photo.jpg"
        f.write_bytes(b"jpeg-bytes")
        captured: dict[str, object] = {}

        def fake_upload_bytes(handle, data, length, name, mime_type, completion, userdata):
            captured["data"] = bytes(ctypes.cast(data, ctypes.POINTER(ctypes.c_uint8 * length))[0])
            captured["name"] = name
            captured["mime_type"] = mime_type
            completion(
                1, b'{"upload_id": "up_x", "name": "x", "mime_type": "y", "size": 1}', None, None
            )

        fake_lib = mock.Mock()
        fake_lib.reactor_upload_bytes = fake_upload_bytes
        fake_lib.reactor_upload_file = mock.Mock(
            side_effect=AssertionError("should not be called when an override is given")
        )
        reactor = self._reactor()

        with mock.patch("reactor_sdk.client.get_lib", return_value=fake_lib):
            await reactor.upload_file(str(f), name="custom.jpg", mime_type="image/x-custom")

        assert captured["data"] == b"jpeg-bytes"
        assert captured["name"] == b"custom.jpg"
        assert captured["mime_type"] == b"image/x-custom"

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
        from reactor_sdk.client import ReactorError, _settle_from_foreign_thread

        loop = asyncio.get_running_loop()
        future: asyncio.Future = loop.create_future()

        _settle_from_foreign_thread(loop, future, None, ReactorError("boom"))

        with pytest.raises(ReactorError, match="boom"):
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

    def test_a_fabricated_handle_is_never_handed_to_the_native_destroy(self) -> None:
        """The scenario the session guard in `conftest.py` exists for.

        Half this suite assigns `_handle` directly to make a client look
        connected, and that integer is indistinguishable from a live pointer by
        the time `__del__` runs. Without the guard this is a segmentation fault,
        not a failure — so it is worth one deterministic place that crashes if the
        guard is ever removed, rather than a random later test taking the whole
        run down with it.
        """
        import gc

        reactor = Reactor("https://api.reactor.inc", "m")
        reactor._handle = 1234
        del reactor
        gc.collect()

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


class TestAudioDevices:
    """The SDK must never open a microphone or a speaker.

    Asserted at the one place that could: the call that creates the native
    handle. Nothing further down is observable from a unit test — whether a
    capture device was opened is a property of libwebrtc — so the mode this
    passes is the whole contract.
    """

    def _create(self, monkeypatch: pytest.MonkeyPatch) -> dict:
        captured: dict = {}
        fake_lib = mock.Mock()

        def create_with_adm(api_url, model, jwt, local, callbacks, adm_mode):
            captured["adm_mode"] = adm_mode
            return 1234

        fake_lib.reactor_create_with_adm = create_with_adm
        fake_lib.reactor_create = lambda *a: pytest.fail(
            "reactor_create takes the ADM from REACTOR_WEBRTC_ADM; the mode must "
            "be named so the environment cannot open a capture device"
        )
        monkeypatch.setattr("reactor_sdk.client.get_lib", lambda: fake_lib)

        reactor = Reactor("https://api.reactor.inc", "m")
        try:
            reactor._create_handle()
        finally:
            # _create_handle registers the client as live, which is what makes the
            # conftest guard step aside — so the fabricated handle has to go before
            # __del__ can hand it to the real reactor_destroy.
            from reactor_sdk.client import _LIVE_CLIENTS

            reactor._handle = None
            _LIVE_CLIENTS.discard(reactor)
        return captured

    async def test_the_handle_is_always_created_synthetic(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        from reactor_sdk.client import _SYNTHETIC_ADM

        assert self._create(monkeypatch)["adm_mode"].value == _SYNTHETIC_ADM

    async def test_the_environment_cannot_change_it(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """REACTOR_WEBRTC_ADM is read by the library, not by this SDK. Removing
        the adm_mode argument without naming the mode here would have left the
        variable as an undocumented way to put the live microphone on the wire.
        """
        monkeypatch.setenv("REACTOR_WEBRTC_ADM", "platform")
        from reactor_sdk.client import _SYNTHETIC_ADM

        assert self._create(monkeypatch)["adm_mode"].value == _SYNTHETIC_ADM

    def test_the_constructor_no_longer_takes_a_mode(self) -> None:
        """A hard error, deliberately. Accepting `adm_mode` and ignoring it would
        leave a caller who asked for platform capture with a silently mute app;
        a TypeError sends them to read what changed.
        """
        with pytest.raises(TypeError, match="adm_mode"):
            Reactor("https://api.reactor.inc", "m", adm_mode=1)  # type: ignore[call-arg]


class TestConnectionId:
    """`connect(connection_id=...)` has to reach the FFI as a pointer to a live
    `uint32`, not the Python `int` — `reactor_connect`'s `argtypes` marshal it as
    `POINTER(c_uint32)`, and passing the wrong shape is undefined behaviour on
    the Rust side, invisible from here. `Track`/`send_command` already prove the
    completion-callback wiring; this is only the one new argument's shape.
    """

    def _connect_reactor(self, monkeypatch: pytest.MonkeyPatch) -> tuple[Reactor, dict]:
        captured: dict = {}
        fake_lib = mock.Mock()
        fake_lib.reactor_create_with_adm = lambda *a: 1234

        def connect(handle, session_id, connection_id, completion, userdata):
            captured["connection_id"] = connection_id
            completion(1, b"{}", None, None)

        fake_lib.reactor_connect = connect
        monkeypatch.setattr("reactor_sdk.client.get_lib", lambda: fake_lib)
        return Reactor("https://api.reactor.inc", "m", jwt="fake"), captured

    async def test_omitting_it_passes_a_null_pointer(self, monkeypatch: pytest.MonkeyPatch) -> None:
        reactor, captured = self._connect_reactor(monkeypatch)
        await reactor.connect()
        assert captured["connection_id"] is None

    async def test_passing_it_reaches_the_ffi_as_the_same_value(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        reactor, captured = self._connect_reactor(monkeypatch)
        await reactor.connect(connection_id=42)
        pointer = ctypes.cast(captured["connection_id"], ctypes.POINTER(ctypes.c_uint32))
        assert pointer.contents.value == 42

    async def test_session_id_and_connection_id_are_independent(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """Adopting a session without adopting a specific connection within it —
        the common multi-connection shape — must not require both."""
        reactor, captured = self._connect_reactor(monkeypatch)
        await reactor.connect(session_id="s1")
        assert captured["connection_id"] is None


class TestConnectionIdRange:
    """`ctypes.c_uint32` does not raise for a value outside its range — it
    wraps modulo 2**32 (confirmed directly: `c_uint32(-1).value == 4294967295`,
    `c_uint32(2**32 + 42).value == 42`) — so `connect()` has to reject an
    out-of-range value itself, before it ever reaches ctypes, or a caller's
    typo silently adopts a real but different connection instead of failing.
    """

    @pytest.mark.parametrize("bad", [-1, 2**32, 2**32 + 42, -(2**32)])
    async def test_an_out_of_range_value_is_rejected_before_any_side_effect(
        self, monkeypatch: pytest.MonkeyPatch, bad: int
    ) -> None:
        """Checked before the handle is even created, not just before the FFI
        call — a rejected connect() should leave the client exactly as it
        found it, not holding a handle nothing will ever use."""
        fake_lib = mock.Mock()
        fake_lib.reactor_create_with_adm = lambda *a: pytest.fail(
            "a handle must not be created for an out-of-range connection_id"
        )
        fake_lib.reactor_connect = lambda *a: pytest.fail(
            "reactor_connect must not be reached for an out-of-range connection_id"
        )
        monkeypatch.setattr("reactor_sdk.client.get_lib", lambda: fake_lib)
        reactor = Reactor("https://api.reactor.inc", "m", jwt="fake")

        with pytest.raises(ValueError, match="connection_id"):
            await reactor.connect(connection_id=bad)

        assert reactor._handle is None

    @pytest.mark.parametrize("boundary", [0, 2**32 - 1])
    async def test_the_boundary_values_are_accepted(
        self, monkeypatch: pytest.MonkeyPatch, boundary: int
    ) -> None:
        captured: dict = {}
        fake_lib = mock.Mock()
        fake_lib.reactor_create_with_adm = lambda *a: 1234

        def connect(handle, session_id, connection_id, completion, userdata):
            captured["connection_id"] = connection_id
            completion(1, b"{}", None, None)

        fake_lib.reactor_connect = connect
        monkeypatch.setattr("reactor_sdk.client.get_lib", lambda: fake_lib)
        reactor = Reactor("https://api.reactor.inc", "m", jwt="fake")

        await reactor.connect(connection_id=boundary)

        pointer = ctypes.cast(captured["connection_id"], ctypes.POINTER(ctypes.c_uint32))
        assert pointer.contents.value == boundary
