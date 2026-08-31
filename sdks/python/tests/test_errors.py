"""Tests for the typed exceptions raised by failed operations.

The thing under test is a translation: an error payload crossing the FFI becomes
an exception a caller can branch on. Most of what matters is what happens to the
payloads that are *not* the happy one — an unknown code, a missing payload, a
library too old to send JSON at all — because that is the error path of the error
path, and an exception raised from inside it replaces a real failure with a
confusing one.
"""

from __future__ import annotations

import asyncio
import json
from unittest import mock

import pytest

from reactor_sdk import (
    ConflictError,
    InvalidStateError,
    MessageTooLargeError,
    RateLimitedError,
    Reactor,
    ReactorError,
    RecorderDisabledError,
    RequestTimeoutError,
    ServerError,
    UnauthorizedError,
)
from reactor_sdk.errors import ERROR_CLASSES, error_for_code, error_from_payload


def _payload(**overrides: object) -> bytes:
    body = {
        "code": "SERVER_ERROR",
        "message": "unexpected HTTP status 503 from POST /sessions",
        "recoverable": True,
    }
    body.update(overrides)
    return json.dumps(body).encode()


class TestCodeToClass:
    @pytest.mark.parametrize(
        ("code", "expected"),
        [
            ("UNAUTHORIZED", UnauthorizedError),
            ("CONFLICT", ConflictError),
            ("RATE_LIMITED", RateLimitedError),
            ("SERVER_ERROR", ServerError),
            ("REQUEST_TIMEOUT", RequestTimeoutError),
            ("INVALID_STATE", InvalidStateError),
            ("MESSAGE_TOO_LARGE", MessageTooLargeError),
            ("RECORDER_DISABLED", RecorderDisabledError),
        ],
    )
    def test_a_known_code_raises_its_own_class(self, code: str, expected: type) -> None:
        assert type(error_from_payload(_payload(code=code))) is expected

    def test_every_class_is_reachable_by_its_code(self) -> None:
        """A class nothing can produce is a class nobody can catch."""
        for cls in ERROR_CLASSES:
            assert error_for_code(cls.code) is cls

    def test_the_codes_are_distinct(self) -> None:
        codes = [cls.code for cls in ERROR_CLASSES]
        assert len(codes) == len(set(codes))

    def test_catching_the_base_still_catches_everything(self) -> None:
        """The compatibility promise: code written against the single generic
        error keeps working, and gains the specific classes without changing."""
        for cls in ERROR_CLASSES:
            assert issubclass(cls, ReactorError)


class TestUnknownCodes:
    def test_a_platform_code_survives_on_the_base_class(self) -> None:
        """A model rejecting a command sends its own code, which this package
        cannot enumerate. Falling back to the base class must not mean falling
        back to a generic code — matching on `error.code` is the whole point."""
        error = error_from_payload(_payload(code="PROMPT_REJECTED", message="unsafe"))
        assert type(error) is ReactorError
        assert error.code == "PROMPT_REJECTED"
        assert error.message == "unsafe"

    def test_a_missing_code_falls_back_without_inventing_one(self) -> None:
        raw = json.dumps({"message": "something went wrong"}).encode()
        error = error_from_payload(raw)
        assert type(error) is ReactorError
        assert error.code == "INTERNAL_ERROR"
        assert error.message == "something went wrong"


class TestMalformedPayloads:
    def test_no_payload_at_all(self) -> None:
        assert error_from_payload(None).message == "unknown error"
        assert error_from_payload(b"").message == "unknown error"

    def test_a_bare_string_is_kept_as_the_message(self) -> None:
        """What a library built before the structured payload sends. An SDK is not
        always paired with the exact libreactor_ffi it shipped with, and the wrong
        guess here would raise a JSON error in place of the real failure."""
        error = error_from_payload(b"peer transport error: ice failed")
        assert type(error) is ReactorError
        assert error.message == "peer transport error: ice failed"

    def test_valid_json_that_is_not_an_object_is_kept_as_the_message(self) -> None:
        error = error_from_payload(b'["not", "an", "object"]')
        assert error.message == '["not", "an", "object"]'

    def test_undecodable_bytes_do_not_raise(self) -> None:
        assert error_from_payload(b"\xff\xfe not utf-8").message


class TestAttributes:
    def test_the_payload_fields_reach_the_exception(self) -> None:
        error = error_from_payload(
            _payload(code="RATE_LIMITED", status=429, operation="connect", retry_after_ms=2000)
        )
        assert error.code == "RATE_LIMITED"
        assert error.retry_after_ms == 2000
        assert error.status == 429
        assert error.operation == "connect"
        assert error.recoverable is True

    def test_absent_optional_fields_are_none(self) -> None:
        error = error_from_payload(_payload())
        assert error.status is None
        assert error.operation is None
        assert error.retry_after_ms is None

    def test_recoverable_says_whether_retrying_is_worth_anything(self) -> None:
        """The property callers branch on when they do not care which failure it
        was, only whether to try again."""
        assert error_from_payload(_payload(code="SERVER_ERROR", recoverable=True)).recoverable
        assert not error_from_payload(_payload(code="UNAUTHORIZED", recoverable=False)).recoverable

    def test_str_names_the_operation_and_the_code(self) -> None:
        error = error_from_payload(
            _payload(code="NOT_FOUND", message="no such model", operation="connect")
        )
        assert str(error) == "connect: [NOT_FOUND] no such model"

    def test_str_without_an_operation_omits_it(self) -> None:
        error = error_from_payload(_payload(code="NOT_FOUND", message="no such model"))
        assert str(error) == "[NOT_FOUND] no such model"

    def test_the_message_is_still_the_first_argument(self) -> None:
        """`ReactorError("boom")` is how this was constructed before there was
        anything else to say, and it is still valid."""
        error = ReactorError("boom")
        assert error.args == ("boom",)
        assert error.message == "boom"
        assert error.code == "INTERNAL_ERROR"


class TestRaisedFromAnOperation:
    """End to end from the completion callback, which is where this has to work."""

    def _reactor(self) -> Reactor:
        reactor = Reactor("m")
        reactor._handle = 1234
        return reactor

    async def test_a_failed_command_raises_the_specific_class(self) -> None:
        fake_lib = mock.Mock()
        fake_lib.reactor_send_command = lambda h, n, a, u, completion, ud: completion(
            0, None, _payload(code="UNAUTHORIZED", operation="send_command"), None
        )

        with mock.patch("reactor_sdk.client.get_lib", return_value=fake_lib):
            with pytest.raises(UnauthorizedError) as excinfo:
                await self._reactor().send_command("hello", {})

        assert excinfo.value.operation == "send_command"

    async def test_a_failed_command_is_still_a_reactor_ffi_error(self) -> None:
        """Code that predates the specific classes catches this one."""
        fake_lib = mock.Mock()
        fake_lib.reactor_send_command = lambda h, n, a, u, completion, ud: completion(
            0, None, _payload(code="REQUEST_TIMEOUT"), None
        )

        with mock.patch("reactor_sdk.client.get_lib", return_value=fake_lib):
            with pytest.raises(ReactorError):
                await self._reactor().send_command("hello", {})

    async def test_an_old_library_sending_a_bare_string_still_raises(self) -> None:
        fake_lib = mock.Mock()
        fake_lib.reactor_send_command = lambda h, n, a, u, completion, ud: completion(
            0, None, b"command 'hello' failed", None
        )

        with mock.patch("reactor_sdk.client.get_lib", return_value=fake_lib):
            with pytest.raises(ReactorError, match="command 'hello' failed"):
                await self._reactor().send_command("hello", {})


class TestOnErrorEvent:
    """End to end from the `on_error` callback the FFI would actually call —
    `TestRaisedFromAnOperation` above covers the completion-callback path, this is
    the other one `Reactor._on_error` itself has to get right.
    """

    async def test_fires_the_specific_class_with_a_timestamp(self) -> None:
        fake_lib = mock.Mock()
        fake_lib.reactor_create_with_adm = lambda *a: 1234

        reactor = Reactor("m")
        received: list[ReactorError] = []
        reactor.on("error", received.append)

        with mock.patch("reactor_sdk.client.get_lib", return_value=fake_lib):
            reactor._create_handle()
            try:
                e_cb = reactor._callbacks_struct.on_error
                e_cb(
                    _payload(
                        code="UNAUTHORIZED", status=401, operation="connect", timestamp_ms=123.0
                    ),
                    None,
                )
                await asyncio.sleep(0)  # let call_soon_threadsafe's callback run
            finally:
                from reactor_sdk.client import _LIVE_CLIENTS

                reactor._handle = None
                _LIVE_CLIENTS.discard(reactor)

        assert len(received) == 1
        error = received[0]
        assert type(error) is UnauthorizedError
        assert isinstance(error, ReactorError)
        assert (error.code, error.status, error.operation) == ("UNAUTHORIZED", 401, "connect")
        assert error.timestamp_ms == 123.0

    async def test_an_unrecognised_code_survives_on_the_base_class(self) -> None:
        """A model's own rejection code, arriving as an event instead of a raise —
        the same open-endedness `error_from_payload` guarantees for the exception
        path must hold here too."""
        fake_lib = mock.Mock()
        fake_lib.reactor_create_with_adm = lambda *a: 1234

        reactor = Reactor("m")
        received: list[ReactorError] = []
        reactor.on("error", received.append)

        with mock.patch("reactor_sdk.client.get_lib", return_value=fake_lib):
            reactor._create_handle()
            try:
                e_cb = reactor._callbacks_struct.on_error
                e_cb(_payload(code="PROMPT_REJECTED", message="unsafe"), None)
                await asyncio.sleep(0)
            finally:
                from reactor_sdk.client import _LIVE_CLIENTS

                reactor._handle = None
                _LIVE_CLIENTS.discard(reactor)

        assert len(received) == 1
        assert type(received[0]) is ReactorError
        assert received[0].code == "PROMPT_REJECTED"


class TestOneList:
    """The event and the failed call report the same code for the same failure —
    because both are the same `ReactorError`, not two types that happen to agree."""

    def test_the_event_payload_and_the_exception_are_the_same_class(self) -> None:
        """`Reactor._on_error` builds its payload with `error_for_code(code)(...)`,
        the exact call `error_from_payload` makes — this pins that construction so
        a future edit to one path cannot quietly stop matching the other."""
        payload = _payload(code="UNAUTHORIZED", status=401, operation="connect")
        raised = error_from_payload(payload)

        event = error_for_code(raised.code)(
            raised.message,
            code=raised.code,
            recoverable=raised.recoverable,
            status=raised.status,
            operation=raised.operation,
            retry_after_ms=raised.retry_after_ms,
            timestamp_ms=0.0,
        )

        assert type(event) is type(raised) is UnauthorizedError
        assert isinstance(event, ReactorError)
        assert (event.code, event.status, event.operation) == ("UNAUTHORIZED", 401, "connect")
        assert event.timestamp_ms == 0.0
        assert raised.timestamp_ms is None  # never set on the raised path

    def test_no_class_claims_a_code_the_event_uses_for_something_else(self) -> None:
        """The failure mode a single list exists to prevent: one name meaning two
        things depending on which channel it arrived through."""
        assert error_for_code("DISCONNECTED").code == "DISCONNECTED"
        assert error_for_code("INVALID_STATE").code == "INVALID_STATE"

    def test_component_is_gone(self) -> None:
        """It named a tier of ours, changed nothing a caller would do, and having
        it split the vocabulary is what produced two codes for one failure."""
        error = error_from_payload(_payload(component="gpu"))
        assert not hasattr(error, "component")


class TestBackoffHint:
    """`Retry-After` is read off the HTTP response in the core; these pin that it
    survives the trip out to a caller who can act on it."""

    async def test_a_throttled_call_carries_the_servers_wait(self) -> None:
        fake_lib = mock.Mock()
        fake_lib.reactor_send_command = lambda h, n, a, u, completion, ud: completion(
            0,
            None,
            _payload(
                code="RATE_LIMITED",
                status=429,
                retry_after_ms=5000,
                operation="send_command",
            ),
            None,
        )

        reactor = Reactor("m")
        reactor._handle = 1234
        with mock.patch("reactor_sdk.client.get_lib", return_value=fake_lib):
            with pytest.raises(RateLimitedError) as excinfo:
                await reactor.send_command("hello", {})

        assert excinfo.value.retry_after_ms == 5000
        assert excinfo.value.recoverable

    def test_no_hint_is_none_rather_than_zero(self) -> None:
        """Zero would read as "retry immediately", which is the opposite of what a
        server that said nothing meant."""
        assert error_from_payload(_payload(code="RATE_LIMITED")).retry_after_ms is None
