"""Tests for the parts of the client that need no native library.

`reactor._ffi` loads `libreactor_ffi` lazily — `get_lib()` is what triggers
`ctypes.CDLL`, not the import — so everything a `Reactor` does before `connect()`
is testable without a build. That is worth keeping true, and the first test pins
it: if importing the package started loading the dylib, `pip install reactor-sdk`
would fail on a machine that has no built library rather than at the point of use.

Tests that do need the library live in `test_ffi_bindings.py` and skip without it.
"""

from __future__ import annotations

import pytest

from reactor import Clip, FileRef, Reactor, ReactorError


class TestLazyLoading:
    def test_constructing_a_reactor_does_not_load_the_library(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """Asserted by making a load fail loudly rather than by inspecting the
        cache, so the test does not depend on whether some other module already
        loaded the library.
        """
        import reactor._ffi as ffi

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
            pytest.param(lambda r: r.send_command("hello", {}), id="send_command"),
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

    def test_a_raising_handler_does_not_stop_the_others(self) -> None:
        """Current behaviour: `_fire` swallows handler exceptions.

        Pinned so the swallowing is a decision rather than an accident — it is why
        a buggy handler is invisible today. If `_fire` starts logging, this test
        should be updated, not deleted.
        """
        reactor = Reactor("https://api.reactor.inc", "m")
        seen: list[str] = []

        def boom(_: str) -> None:
            raise ValueError("handler is broken")

        reactor.on("status_changed", boom)
        reactor.on("status_changed", lambda s: seen.append(s))

        reactor._fire("status_changed", "ready")

        assert seen == ["ready"]

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
