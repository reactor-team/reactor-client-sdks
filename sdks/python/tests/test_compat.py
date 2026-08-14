"""Tests for the API surface that keeps code written against earlier releases working.

Each of these exists because a real consumer uses that shape. The pygame example in
`examples/pygame_app` came across from the previous SDK with no changes to a single
line that touches the SDK — only the formatter's own reordering and `Optional[X]` to
`X | None` — and everything asserted here is what made that possible. They are
regression tests in the strict sense: dropping any one of them breaks somebody.
"""

from __future__ import annotations

import asyncio
import json
import logging
import warnings
from typing import Any
from unittest import mock

import numpy as np
import pytest

from reactor_sdk import (
    DEFAULT_API_URL,
    LOCAL_API_URL,
    AuthError,
    Reactor,
    ReactorStatus,
    fetch_jwt,
)
from reactor_sdk.client import _bgra_to_rgb_array


class TestStatusEnum:
    """A `str` enum, so it is interchangeable with the strings the FFI reports."""

    def test_compares_equal_to_its_string(self) -> None:
        assert ReactorStatus.READY == "ready"
        assert ReactorStatus.DISCONNECTED == "disconnected"

    def test_value_is_the_string(self) -> None:
        assert ReactorStatus.WAITING.value == "waiting"

    def test_constructs_from_a_string(self) -> None:
        assert ReactorStatus("connecting") is ReactorStatus.CONNECTING

    def test_usable_as_a_dict_key_alongside_strings(self) -> None:
        """The example keys its colour table by enum and looks it up with whatever
        `status` returns."""
        colours = {ReactorStatus.READY: "green"}
        assert colours[ReactorStatus("ready")] == "green"

    def test_status_property_returns_the_enum(self) -> None:
        assert Reactor(model_name="m").status is ReactorStatus.DISCONNECTED


class TestConstructor:
    def test_old_keyword_form(self) -> None:
        r = Reactor(
            model_name="hy-world",
            api_key="k",
            api_url="https://example.invalid",
            local=False,
        )
        assert (r._model_name, r._api_url, r._api_key) == (
            "hy-world",
            "https://example.invalid",
            "k",
        )

    def test_old_positional_form_puts_the_model_first(self) -> None:
        """`Reactor("hy-world")` used to be valid. It still is: an api_url is always a
        URL and a model name never is, so the two cannot be confused."""
        r = Reactor("hy-world")
        assert r._model_name == "hy-world"
        assert r._api_url == DEFAULT_API_URL

    @pytest.mark.parametrize("url", ["http://localhost:8080", "https://api.reactor.inc"])
    def test_new_positional_form_puts_the_url_first(self, url: str) -> None:
        r = Reactor(url, "m")
        assert (r._api_url, r._model_name) == (url, "m")

    def test_api_url_defaults_to_production(self) -> None:
        assert Reactor(model_name="m")._api_url == DEFAULT_API_URL

    def test_model_name_is_required(self) -> None:
        with pytest.raises(TypeError, match="requires model_name"):
            Reactor()


class TestLocalMode:
    """`local=True` means a local coordinator, so it decides the URL."""

    def test_local_points_at_localhost(self) -> None:
        assert Reactor(model_name="m", local=True)._api_url == LOCAL_API_URL

    def test_local_overrides_the_production_default(self) -> None:
        """The shape that matters: callers compute `api_url or PROD` and pass it with
        `local=True`, which would otherwise aim local mode at production. The pygame
        example does exactly that."""
        r = Reactor(model_name="m", api_url=DEFAULT_API_URL, local=True)
        assert r._api_url == LOCAL_API_URL

    def test_an_explicit_local_url_is_honoured(self) -> None:
        """A coordinator on another port was a real choice, not a default."""
        r = Reactor(model_name="m", api_url="http://localhost:9090", local=True)
        assert r._api_url == "http://localhost:9090"

    def test_without_local_the_url_is_untouched(self) -> None:
        r = Reactor(model_name="m", api_url=DEFAULT_API_URL)
        assert r._api_url == DEFAULT_API_URL


class TestFrameConversion:
    def test_bgra_becomes_rgb_without_alpha(self) -> None:
        # One pixel: B=10, G=20, R=30, A=255.
        rgb = _bgra_to_rgb_array(bytes([10, 20, 30, 255]), 1, 1)
        assert rgb.shape == (1, 1, 3)
        assert rgb[0, 0].tolist() == [30, 20, 10]

    def test_shape_is_height_width_three(self) -> None:
        rgb = _bgra_to_rgb_array(bytes([0, 0, 0, 255]) * 6, width=3, height=2)
        assert rgb.shape == (2, 3, 3)
        assert rgb.dtype == np.uint8

    def test_rows_are_in_order(self) -> None:
        """Two rows of one pixel, distinguishable by red."""
        bgra = bytes([0, 0, 1, 255]) + bytes([0, 0, 2, 255])
        rgb = _bgra_to_rgb_array(bgra, width=1, height=2)
        assert [rgb[0, 0, 0], rgb[1, 0, 0]] == [1, 2]


class TestDecorators:
    def test_on_frame_delivers_an_rgb_array(self) -> None:
        reactor = Reactor(model_name="m")
        seen: list[Any] = []

        @reactor.on_frame
        def handler(frame: Any) -> None:
            seen.append(frame)

        reactor._fire("frame", bytes([10, 20, 30, 255]) * 4, 2, 2, 7, 8, b"tag")

        assert len(seen) == 1
        assert seen[0].shape == (2, 2, 3)
        assert seen[0][0, 0].tolist() == [30, 20, 10]

    def test_on_frame_can_also_take_the_metadata(self) -> None:
        """A handler that declares the extra parameters gets the trailer with the image,
        so reading metadata needs no second handler."""
        reactor = Reactor(model_name="m")
        seen: list[tuple] = []

        @reactor.on_frame
        def handler(frame: Any, frame_id: int, timestamp_us: int, user_data: bytes) -> None:
            seen.append((frame.shape, frame_id, timestamp_us, user_data))

        reactor._fire("frame", bytes([1, 2, 3, 255]) * 4, 2, 2, 7, 1234, b"tag")

        assert seen == [((2, 2, 3), 7, 1234, b"tag")]

    def test_on_frame_gives_a_handler_only_what_it_asks_for(self) -> None:
        """The prefix rule: N parameters means the first N of
        (frame, frame_id, timestamp_us, user_data)."""
        reactor = Reactor(model_name="m")
        two: list[tuple] = []
        three: list[tuple] = []

        @reactor.on_frame
        def with_id(frame: Any, frame_id: int) -> None:
            two.append((frame.shape, frame_id))

        @reactor.on_frame
        def with_time(frame: Any, frame_id: int, timestamp_us: int) -> None:
            three.append((frame_id, timestamp_us))

        reactor._fire("frame", bytes([1, 2, 3, 255]), 1, 1, 5, 99, b"x")

        assert two == [((1, 1, 3), 5)]
        assert three == [(5, 99)]

    def test_on_frame_gives_star_args_everything(self) -> None:
        reactor = Reactor(model_name="m")
        seen: list[tuple] = []

        @reactor.on_frame
        def handler(*args: Any) -> None:
            seen.append(args)

        reactor._fire("frame", bytes([1, 2, 3, 255]), 1, 1, 3, 4, b"z")

        assert len(seen[0]) == 4
        assert seen[0][1:] == (3, 4, b"z")

    def test_an_untagged_frame_reaches_a_metadata_handler_with_empty_values(self) -> None:
        """A frame with no trailer is not withheld: the handler is told there was none."""
        reactor = Reactor(model_name="m")
        seen: list[tuple] = []

        @reactor.on_frame
        def handler(_frame: Any, frame_id: int, timestamp_us: int, user_data: bytes) -> None:
            seen.append((frame_id, timestamp_us, user_data))

        reactor._fire("frame", bytes([0, 0, 0, 255]), 1, 1, 0, 0, b"")

        assert seen == [(0, 0, b"")]

    def test_a_bound_method_is_measured_without_self(self) -> None:
        """inspect drops the bound instance, so `def h(self, frame)` counts as one."""
        reactor = Reactor(model_name="m")
        seen: list[Any] = []

        class Renderer:
            def handle(self, frame: Any) -> None:
                seen.append(frame.shape)

        reactor.on_frame(Renderer().handle)
        reactor._fire("frame", bytes([0, 0, 0, 255]), 1, 1, 0, 0, b"")

        assert seen == [(1, 1, 3)]

    def test_an_unreadable_signature_falls_back_to_the_old_contract(self) -> None:
        """A callable inspect cannot read gets one argument, which is the shape this
        decorator has always had — the compatible direction rather than a guess."""
        from reactor_sdk.client import _positional_arity

        assert _positional_arity(print, 4) >= 1

    def test_on_frame_returns_the_function(self) -> None:
        """So the decorated name stays callable."""
        reactor = Reactor(model_name="m")

        @reactor.on_frame
        def handler(_frame: Any) -> str:
            return "still me"

        assert handler(None) == "still me"

    def test_bare_on_status_sees_every_change(self) -> None:
        reactor = Reactor(model_name="m")
        seen: list[ReactorStatus] = []

        @reactor.on_status
        def handler(status: ReactorStatus) -> None:
            seen.append(status)

        reactor._fire("status_changed", "connecting")
        reactor._fire("status_changed", "ready")

        assert seen == [ReactorStatus.CONNECTING, ReactorStatus.READY]
        assert all(isinstance(s, ReactorStatus) for s in seen)

    def test_parameterised_on_status_fires_only_for_that_status(self) -> None:
        reactor = Reactor(model_name="m")
        calls: list[str] = []

        # The handler receives the status even when filtered, which is the shape the
        # previous SDK used and therefore what existing code is written to.
        @reactor.on_status(ReactorStatus.READY)
        def ready(status: ReactorStatus) -> None:
            calls.append(status.value)

        @reactor.on_status(ReactorStatus.DISCONNECTED)
        def gone(status: ReactorStatus) -> None:
            calls.append(status.value)

        reactor._fire("status_changed", "connecting")
        assert calls == []

        reactor._fire("status_changed", "ready")
        reactor._fire("status_changed", "disconnected")
        assert calls == ["ready", "disconnected"]

    def test_parameterised_on_status_accepts_a_plain_string(self) -> None:
        reactor = Reactor(model_name="m")
        calls: list[str] = []

        @reactor.on_status("ready")
        def ready(status: ReactorStatus) -> None:
            calls.append(status.value)

        reactor._fire("status_changed", "ready")
        assert calls == ["ready"]

    @pytest.mark.parametrize(
        ("decorator", "event", "payload"),
        [
            ("on_error", "error", "boom"),
            ("on_message", "message", {"a": 1}),
        ],
    )
    def test_simple_decorators(self, decorator: str, event: str, payload: Any) -> None:
        reactor = Reactor(model_name="m")
        seen: list[Any] = []
        getattr(reactor, decorator)(seen.append)

        reactor._fire(event, payload)

        assert seen == [payload]

    def test_on_track_receives_name_and_mid(self) -> None:
        reactor = Reactor(model_name="m")
        seen: list[tuple] = []
        reactor.on_track(lambda name, mid: seen.append((name, mid)))

        reactor._fire("track_received", "video", "0")

        assert seen == [("video", "0")]

    async def test_an_async_handler_actually_runs(self) -> None:
        """`async def` handlers used to only build a coroutine and never run it —
        calling a handler plainly never awaits the result. The previous SDK's event
        emitter checked for a coroutine and scheduled it; this is that behaviour
        restored, for every decorator that goes through `_fire`."""
        reactor = Reactor(model_name="m")
        reactor._loop = asyncio.get_running_loop()
        seen: list[ReactorStatus] = []

        @reactor.on_status
        async def handler(status: ReactorStatus) -> None:
            seen.append(status)

        reactor._fire("status_changed", "ready")
        # `run_coroutine_threadsafe` takes more than one trip through the loop: one to
        # schedule the task, another to run its first step. Polling rather than a fixed
        # number of `sleep(0)`s keeps this independent of that implementation detail.
        for _ in range(10):
            if seen:
                break
            await asyncio.sleep(0)

        assert seen == [ReactorStatus.READY]

    async def test_an_async_handler_that_raises_does_not_stop_the_others(self) -> None:
        """The coroutine runs on the loop, not inside `_fire`'s try/except: it must
        not prevent the next handler in line from firing."""
        reactor = Reactor(model_name="m")
        reactor._loop = asyncio.get_running_loop()
        seen: list[str] = []

        @reactor.on_status
        async def boom(status: str) -> None:
            raise RuntimeError("nope")

        @reactor.on_status
        def fine(status: str) -> None:
            seen.append(status)

        reactor._fire("status_changed", "ready")
        await asyncio.sleep(0)

        assert seen == ["ready"]

    async def test_an_async_handler_that_raises_is_logged(
        self, caplog: pytest.LogCaptureFixture
    ) -> None:
        """`run_coroutine_threadsafe` chains the coroutine's outcome onto a
        `concurrent.futures.Future` this method would otherwise discard — and that
        chaining is itself what retrieves the inner task's exception, so asyncio's own
        "exception was never retrieved" never fires either. Left alone, a raising
        async handler fails completely silently; a done-callback on that future is
        what still logs it, matching a raising sync handler."""
        reactor = Reactor(model_name="m")
        reactor._loop = asyncio.get_running_loop()

        @reactor.on_status
        async def boom(status: str) -> None:
            raise RuntimeError("nope")

        with caplog.at_level(logging.ERROR, logger="reactor_sdk.client"):
            reactor._fire("status_changed", "ready")
            for _ in range(10):
                if caplog.records:
                    break
                await asyncio.sleep(0)

        assert any(
            "status_changed" in r.message and r.exc_info and str(r.exc_info[1]) == "nope"
            for r in caplog.records
        )

    def test_an_async_handler_with_no_loop_is_closed_without_warning(self) -> None:
        """Fired before `connect()` sets `_loop` — e.g. a test calling `_fire`
        directly — there is nowhere to schedule the coroutine. It must be closed
        rather than left to trigger "coroutine was never awaited"."""
        reactor = Reactor(model_name="m")

        @reactor.on_status
        async def handler(status: str) -> None:
            pass

        with warnings.catch_warnings():
            warnings.simplefilter("error")
            reactor._fire("status_changed", "ready")


class TestGetterMethods:
    def test_get_status_matches_the_property(self) -> None:
        reactor = Reactor(model_name="m")
        assert reactor.get_status() == reactor.status == ReactorStatus.DISCONNECTED

    def test_get_session_id_matches_the_property(self) -> None:
        reactor = Reactor(model_name="m")
        assert reactor.get_session_id() is None
        assert reactor.session_id is None


class TestFetchJwt:
    """The API key exchange. The FFI only takes a token, so this is the SDK's job."""

    def _response(self, document: dict[str, Any]) -> Any:
        response = mock.MagicMock()
        response.read.return_value = json.dumps(document).encode()
        response.__enter__ = mock.Mock(return_value=response)
        response.__exit__ = mock.Mock(return_value=False)
        return response

    def test_posts_to_the_tokens_endpoint_with_the_key_as_a_header(self) -> None:
        with mock.patch("reactor_sdk._auth.urllib.request.urlopen") as urlopen:
            urlopen.return_value = self._response({"jwt": "tok"})
            assert fetch_jwt("secret", "https://api.reactor.inc/") == "tok"

        request = urlopen.call_args[0][0]
        assert request.full_url == "https://api.reactor.inc/tokens"
        assert request.get_method() == "POST"
        assert request.get_header("Reactor-api-key") == "secret"

    def test_unscoped_requests_send_a_null_body(self) -> None:
        """Not `{}` — the coordinator distinguishes them."""
        with mock.patch("reactor_sdk._auth.urllib.request.urlopen") as urlopen:
            urlopen.return_value = self._response({"jwt": "tok"})
            fetch_jwt("secret", "https://api.reactor.inc")

        assert json.loads(urlopen.call_args[0][0].data) is None

    def test_scoping_to_models_limits_the_token(self) -> None:
        with mock.patch("reactor_sdk._auth.urllib.request.urlopen") as urlopen:
            urlopen.return_value = self._response({"jwt": "tok"})
            fetch_jwt("secret", "https://api.reactor.inc", models=["hy-world"])

        body = json.loads(urlopen.call_args[0][0].data)
        assert body["authorization_details"] == [
            {"type": "session", "resources": {"models": {"match": ["hy-world"]}}}
        ]

    def test_max_sessions_is_a_constraint_on_a_scoped_token(self) -> None:
        with mock.patch("reactor_sdk._auth.urllib.request.urlopen") as urlopen:
            urlopen.return_value = self._response({"jwt": "tok"})
            fetch_jwt("s", "https://x.invalid", models=["m"], max_sessions=3)

        detail = json.loads(urlopen.call_args[0][0].data)["authorization_details"][0]
        assert detail["constraints"] == {"max_sessions": 3}

    def test_a_response_without_a_token_is_an_error(self) -> None:
        with mock.patch("reactor_sdk._auth.urllib.request.urlopen") as urlopen:
            urlopen.return_value = self._response({"nope": True})
            with pytest.raises(AuthError, match="returned no token"):
                fetch_jwt("secret", "https://x.invalid")

    def test_an_unreachable_coordinator_is_an_error(self) -> None:
        import urllib.error

        with mock.patch("reactor_sdk._auth.urllib.request.urlopen") as urlopen:
            urlopen.side_effect = urllib.error.URLError("no route")
            with pytest.raises(AuthError, match="could not reach"):
                fetch_jwt("secret", "https://x.invalid")


class TestTokenResolution:
    """`connect` turns an api_key into a jwt, and scopes it by whether it is creating
    the session."""

    async def test_creating_a_session_mints_a_model_scoped_token(self) -> None:
        reactor = Reactor(model_name="hy-world", api_key="k")
        with mock.patch("reactor_sdk.client.fetch_jwt", return_value="tok") as fetch:
            assert await reactor._resolve_token(session_id=None) is True

        assert reactor._jwt == "tok"
        assert fetch.call_args.kwargs["models"] == ["hy-world"]

    async def test_adopting_a_session_mints_an_unscoped_token(self) -> None:
        """A model-scoped token cannot operate a session it did not create."""
        reactor = Reactor(model_name="hy-world", api_key="k")
        with mock.patch("reactor_sdk.client.fetch_jwt", return_value="tok") as fetch:
            await reactor._resolve_token(session_id="sess-1")

        assert fetch.call_args.kwargs["models"] is None

    async def test_an_explicit_jwt_is_left_alone(self) -> None:
        """A token the caller supplied is theirs, not ours to replace."""
        reactor = Reactor(model_name="m", jwt="given", api_key="k")
        with mock.patch("reactor_sdk.client.fetch_jwt") as fetch:
            assert await reactor._resolve_token(session_id=None) is False

        assert reactor._jwt == "given"
        fetch.assert_not_called()

    async def test_a_token_of_the_right_scope_is_reused(self) -> None:
        reactor = Reactor(model_name="m", api_key="k")
        with mock.patch("reactor_sdk.client.fetch_jwt", return_value="tok") as fetch:
            assert await reactor._resolve_token(session_id=None) is True
            assert await reactor._resolve_token(session_id=None) is False

        assert fetch.call_count == 1

    async def test_a_changed_scope_mints_again(self) -> None:
        """The scope depends on the call, not the client: creating a session wants a
        model-scoped token and adopting one wants an unscoped token, so a cached token
        from the first is wrong for the second."""
        reactor = Reactor(model_name="m", api_key="k")
        with mock.patch(
            "reactor_sdk.client.fetch_jwt", side_effect=["scoped", "unscoped"]
        ) as fetch:
            await reactor._resolve_token(session_id=None)
            assert await reactor._resolve_token(session_id="sess-1") is True

        assert reactor._jwt == "unscoped"
        assert [c.kwargs["models"] for c in fetch.call_args_list] == [["m"], None]

    async def test_going_back_to_creating_mints_again(self) -> None:
        reactor = Reactor(model_name="m", api_key="k")
        with mock.patch("reactor_sdk.client.fetch_jwt", side_effect=["a", "b", "c"]) as fetch:
            await reactor._resolve_token(session_id=None)
            await reactor._resolve_token(session_id="sess-1")
            assert await reactor._resolve_token(session_id=None) is True

        assert fetch.call_count == 3

    async def test_local_mode_does_not_authenticate(self) -> None:
        reactor = Reactor(model_name="m", api_key="k", local=True)
        with mock.patch("reactor_sdk.client.fetch_jwt") as fetch:
            assert await reactor._resolve_token(session_id=None) is False

        assert reactor._jwt is None
        fetch.assert_not_called()

    async def test_without_a_key_there_is_nothing_to_do(self) -> None:
        reactor = Reactor(model_name="m")
        with mock.patch("reactor_sdk.client.fetch_jwt") as fetch:
            await reactor._resolve_token(session_id=None)

        fetch.assert_not_called()
