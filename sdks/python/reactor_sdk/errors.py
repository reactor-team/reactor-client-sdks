"""Exceptions raised when a Reactor operation fails.

Every failure used to arrive as one `ReactorError` carrying a sentence, which
left callers with two options: catch everything and treat a 401 like a dropped
socket, or match on message text and break the next time the wording changed.

The failure has always been more specific than that — the core distinguishes a
network error from a rejected request from a timeout, and the platform sends its
own codes for a command it refuses. What was missing was a way to carry it across
the FFI. Now every exception has a `code`, and the common codes have a class::

    try:
        await reactor.connect()
    except UnauthorizedError:
        token = await refresh()          # a specific, actionable failure
    except ReactorError as error:
        if error.recoverable:
            await asyncio.sleep(1)       # a class of failures, by property
            await reactor.reconnect()

`ReactorError` remains the base of all of them, so code that already catches it
keeps catching everything.

There is **one class**, not just one list of codes: the `on_error` event and a failed
call both hand you a `ReactorError` — the event payload is not a separate type that
happens to agree, it is this class. They used to disagree — a 401 during `connect()`
raised UNAUTHORIZED and not-recoverable to the caller while the event called it
CONNECTION_FAILED and recoverable, so anything listening to the event reconnected in
a loop against a token that would never work. A second definition of the same fields
is exactly how that split happened in the first place, so there is now only one.
What the event knew that the exception did not — which call failed — is the
`operation` field; what the exception did not need that the event does is
`timestamp_ms`, present on both and only ever set for the latter.

There is no `component`. Which tier of the platform failed is not something a
caller can act on, and splitting the codes by it is what produced two names for
one failure.

**Codes are open-ended.** A control request, a command or a recording the platform
rejects reports the platform's own code, which this package cannot enumerate.
Those raise `ReactorError` itself with `code` set to whatever arrived — so
match on `error.code` for anything not listed here, and never assume an
unrecognised code means the error was malformed.
"""

from __future__ import annotations

import json
from typing import Any


class ReactorError(Exception):
    """A Reactor operation failed, or an `on_error` event arrived — the same class
    either way. The base of every exception in this module.

    Attributes:
        code: A stable, matchable code — one of the classes below, or a code the
            platform sent for a request it rejected.
        message: The human-readable explanation.
        recoverable: Whether the same call could succeed later. True is about the
            moment — a timeout, a 5xx, a transport that dropped — so waiting or
            reconnecting is worth something. False is about the request itself.
        status: The HTTP status, when the failure came from one; None otherwise.
        operation: Which call failed, e.g. ``"connect"``, ``"send_command"``. None
            for a failure that is not tied to one, like a transport that dropped
            on its own.
        retry_after_ms: A backoff hint, when the platform sent one.
        timestamp_ms: When this happened. Only ever set on the `on_error` event —
            a raised exception is already happening now, so there is nothing this
            would tell the caller that catching it does not.
    """

    #: The code this class stands for. `ReactorError` itself is the fallback,
    #: so it claims none and takes whatever the payload reported.
    code: str = "INTERNAL_ERROR"

    def __init__(
        self,
        message: str,
        *,
        code: str | None = None,
        recoverable: bool = False,
        status: int | None = None,
        operation: str | None = None,
        retry_after_ms: float | None = None,
        timestamp_ms: float | None = None,
    ) -> None:
        super().__init__(message)
        self.message = message
        # An explicit code wins, so an unrecognised one survives on the base class
        # rather than being relabelled as the fallback it was routed to.
        self.code = code or type(self).code
        self.recoverable = recoverable
        self.status = status
        self.operation = operation
        self.retry_after_ms = retry_after_ms
        self.timestamp_ms = timestamp_ms

    def __str__(self) -> str:
        where = f"{self.operation}: " if self.operation else ""
        return f"{where}[{self.code}] {self.message}"


class NetworkError(ReactorError):
    """The request never got a reply — DNS, TLS, a refused socket."""

    code = "NETWORK_ERROR"


class UnauthorizedError(ReactorError):
    """401 or 403: the token is missing, expired, or not scoped for this call.

    Distinct from `AuthError`, which is raised when exchanging an API key for a
    token fails before any session exists.
    """

    code = "UNAUTHORIZED"


class NotFoundError(ReactorError):
    """404: no such model, session or upload."""

    code = "NOT_FOUND"


class ConflictError(ReactorError):
    """409: the session is in a state that does not allow this.

    The usual cause is a session left orphaned by a previous run that went away
    without disconnecting.
    """

    code = "CONFLICT"


class RateLimitedError(ReactorError):
    """429: too many requests.

    `retry_after_ms` carries the server's `Retry-After` when it sent one in the
    delta-seconds form. None means it said nothing usable — back off on your own
    terms rather than retrying immediately.
    """

    code = "RATE_LIMITED"


class BadRequestError(ReactorError):
    """A 4xx other than the ones above: the request itself was wrong."""

    code = "BAD_REQUEST"


class ServerError(ReactorError):
    """5xx: the coordinator failed, and the same request may work later."""

    code = "SERVER_ERROR"


class VersionMismatchError(ReactorError):
    """This client and the platform disagree on the protocol.

    Upgrade `reactor-sdk`; a client too old for the platform cannot be worked
    around from here.
    """

    code = "VERSION_MISMATCH"


class DecodeError(ReactorError):
    """A reply arrived and could not be understood."""

    code = "DECODE_FAILED"


class InvalidStateError(ReactorError):
    """The operation is not allowed from the state the client is in.

    Most often an operation that needs a live session, called before `connect()`
    or after the status left `ready`.
    """

    code = "INVALID_STATE"


class SessionTerminalError(ReactorError):
    """The session reached a state it cannot leave. Start a new one."""

    code = "SESSION_TERMINAL"


class MessageTooLargeError(ReactorError):
    """The payload exceeds what the data channel accepts.

    Send large content with `upload_file` and pass the `FileRef`, rather than
    inline in a command.
    """

    code = "MESSAGE_TOO_LARGE"


class TransportError(ReactorError):
    """The media transport failed."""

    code = "TRANSPORT_ERROR"


class DisconnectedError(ReactorError):
    """The connection went away.

    Either it dropped while this request was in flight, or it was lost after
    being established. `reconnect()` is the way back.
    """

    code = "DISCONNECTED"


class RequestTimeoutError(ReactorError):
    """The operation was sent and nothing came back in time."""

    code = "REQUEST_TIMEOUT"


class AbortedError(ReactorError):
    """The operation was abandoned before it finished."""

    code = "ABORTED"


class RecorderDisabledError(ReactorError):
    """A `request_clip`/`request_recording` call failed because the model's recorder is
    disabled or has crashed.

    Unlike every other code here, `RECORDER_DISABLED` isn't platform-sent —
    `reactor-core` produces it by matching known reason strings on an otherwise
    free-text `ClipFailed` message, as a stopgap until that message carries a
    structured reason. Treat it as best-effort: a clip failure for the same
    underlying reason may still arrive as the base `ReactorError` (`code:
    "INTERNAL_ERROR"`) if the reason text ever changes upstream.

    `recoverable` is `False`: the recorder being disabled or crashed isn't
    something retrying the same call can fix — it needs an operator to
    re-enable or restart the recorder on the model side first.
    """

    code = "RECORDER_DISABLED"


#: Every code this package has a class for. Anything else — a platform code for a
#: rejected request — falls back to `ReactorError` with `code` set to it.
ERROR_CLASSES: tuple[type[ReactorError], ...] = (
    NetworkError,
    UnauthorizedError,
    NotFoundError,
    ConflictError,
    RateLimitedError,
    BadRequestError,
    ServerError,
    VersionMismatchError,
    DecodeError,
    InvalidStateError,
    SessionTerminalError,
    MessageTooLargeError,
    TransportError,
    DisconnectedError,
    RequestTimeoutError,
    AbortedError,
    RecorderDisabledError,
)

_BY_CODE: dict[str, type[ReactorError]] = {cls.code: cls for cls in ERROR_CLASSES}


def error_for_code(code: str | None) -> type[ReactorError]:
    """The exception class for `code`, or the base class for anything unknown."""
    return _BY_CODE.get(code or "", ReactorError)


def error_from_payload(raw: bytes | None) -> ReactorError:
    """Build the exception for an FFI error payload.

    `raw` is the JSON object the completion callback reports. Two things it may
    not be, and both have to work: absent, when a completion failed without
    saying why, and a bare string, which is what a library built before the
    structured payload sends — an SDK is not always paired with the exact
    `libreactor_ffi` it shipped with, and the failure mode of guessing wrong here
    is an exception raised from inside the error path.
    """
    if not raw:
        return ReactorError("unknown error")

    text = raw.decode(errors="replace")
    payload = _as_object(text)
    if payload is None:
        return ReactorError(text)

    code = payload.get("code") or None
    message = payload.get("message") or text
    return error_for_code(code)(
        message,
        code=code,
        recoverable=bool(payload.get("recoverable", False)),
        status=payload.get("status"),
        operation=payload.get("operation"),
        retry_after_ms=payload.get("retry_after_ms"),
    )


def _as_object(text: str) -> dict[str, Any] | None:
    """Parse `text` as a JSON object, or None if it is anything else."""
    try:
        payload = json.loads(text)
    except ValueError:
        return None
    return payload if isinstance(payload, dict) else None
