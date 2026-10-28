"""Exchange a Reactor API key for a JWT.

The native library takes a JWT and nothing else — it has no notion of an API key.
Callers who hold a key rather than a token need the exchange done for them, which is
a single HTTP POST, so it lives here rather than in Rust.

Deliberately built on :mod:`urllib.request` instead of a real HTTP client. The SDK
has no runtime dependencies, and one POST made once per connect is not worth
changing that. It is synchronous, so callers run it in a thread.
"""

from __future__ import annotations

import json
import logging
import urllib.error
import urllib.request
from collections.abc import Mapping
from typing import Any

_log = logging.getLogger(__name__)

#: Reactor's production coordinator. Defined here, not in client.py, so fetch_jwt()
#: can default to it too — client.py re-exports it for the public `DEFAULT_API_URL`.
DEFAULT_API_URL = "https://api.reactor.inc"

#: Where the coordinator mints tokens.
_TOKENS_PATH = "/tokens"

#: Long enough for a slow coordinator, short enough that a wedged one does not hang a
#: connect indefinitely.
_TIMEOUT_S = 30.0


#: Header names (lowercase) the SDK or the transport owns, which `extra_headers` may not
#: set. Kept in step with `RESERVED_HEADERS` in crates/reactor-core/src/http.rs, which
#: is the source of truth.
_RESERVED_HEADERS = frozenset(
    {
        "authorization",
        "content-type",
        "content-length",
        "reactor-api-key",
        "reactor-api-version",
        "reactor-api-accept-version",
        "reactor-webrtc-version",
        "host",
        "connection",
        "transfer-encoding",
        "upgrade",
        "accept-encoding",
        "te",
        "trailer",
        "expect",
        "keep-alive",
    }
)

#: Characters an HTTP header name may contain (RFC 9110 `token`).
_TOKEN_CHARS = frozenset(
    "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!#$%&'*+-.^_`|~"
)


def _validate_extra_headers(headers: Mapping[str, str]) -> None:
    """Mirror `validate_extra_headers` in crates/reactor-core/src/http.rs."""
    seen: set[str] = set()
    for name, value in headers.items():
        if not isinstance(name, str) or not isinstance(value, str):
            raise AuthError(f"extra header {name!r} must map str to str")
        if not name or not all(c in _TOKEN_CHARS for c in name):
            raise AuthError(f"invalid extra header name {name!r}")
        if name.lower() in _RESERVED_HEADERS:
            raise AuthError(f"extra header {name!r} is set by the SDK and cannot be overridden")
        # Visible ASCII only: urllib Latin-1 encodes anything wider while the native
        # client sends UTF-8, so one value would name different bytes per request.
        if any(not (c == "\t" or 0x20 <= ord(c) < 0x7F) for c in value):
            raise AuthError(f"invalid value for extra header {name!r}: only visible ASCII")
        if name.lower() in seen:
            raise AuthError(f"extra header {name!r} is given more than once")
        seen.add(name.lower())


class AuthError(RuntimeError):
    """Raised when an API key cannot be exchanged for a JWT."""


def fetch_jwt(
    api_key: str,
    api_url: str = DEFAULT_API_URL,
    *,
    models: list[str] | None = None,
    sessions: list[str] | None = None,
    max_sessions: int | None = None,
    max_session_duration_seconds: int | None = None,
    expires_after: int | None = None,
    extra_headers: Mapping[str, str] | None = None,
) -> str:
    """Exchange `api_key` for a JWT, and return the token.

    ``api_url`` defaults to Reactor's production coordinator, the same default
    `Reactor()` itself uses — pass it explicitly only against a different one.

    Pass ``models`` to mint a **session-scoped** token: it can only create and operate
    sessions on those models, so a leak is worth a handful of sessions rather than
    everything the key can reach. Omitting it mints a token with the full set of
    permissions the key's roles allow — fine server-to-server, wrong to hand to a
    client you do not control.

    Pass ``sessions`` to bind the token to sessions that already exist, by id. Each
    must belong to the key's user and account and still be open, or the coordinator
    rejects the mint. Without ``models`` and ``max_sessions``, such a token can act
    only on those sessions and cannot create new ones.

    ``max_sessions`` caps how many sessions a scoped token may ever hold, counting any
    in ``sessions``, and is ignored for unscoped ones. ``max_session_duration_seconds``
    force-terminates every session the token creates after that long (1-86400, i.e. up
    to 24h), independent of the token's own expiry, and is likewise ignored for
    unscoped ones; the server rejects an out-of-range value at mint rather than
    clamping it. ``expires_after`` is a lifetime in seconds, which the server clamps to
    its own ceiling.

    ``extra_headers`` are sent on the token request after the SDK's own headers, never
    in the body. A name the SDK sets itself (``Authorization``, ``Content-Type``,
    ``Reactor-API-Key`` and the like), a name that is not a valid HTTP token, or a value
    with control characters is rejected before any request is made.

    Raises:
        AuthError: if the exchange fails for any reason, including a response that
            does not carry a token, or if ``extra_headers`` is invalid.
    """
    if extra_headers:
        _validate_extra_headers(extra_headers)

    url = f"{api_url.rstrip('/')}{_TOKENS_PATH}"

    body: dict[str, Any] = {}
    if models is not None or sessions is not None:
        # The coordinator requires a model scope here. A bind alone gets the all-models
        # scope, which grants nothing extra: with no `max_sessions`, the token cannot
        # create sessions.
        resources: dict[str, Any] = {
            "models": {"match": list(models)} if models is not None else {"all": True}
        }
        if sessions is not None:
            resources["sessions"] = {"bind": list(sessions)}
        detail: dict[str, Any] = {"type": "session", "resources": resources}
        constraints: dict[str, Any] = {}
        if max_sessions is not None:
            constraints["max_sessions"] = max_sessions
        if max_session_duration_seconds is not None:
            constraints["max_session_duration_seconds"] = max_session_duration_seconds
        if constraints:
            detail["constraints"] = constraints
        body["authorization_details"] = [detail]
    if expires_after is not None:
        body["expires_after"] = expires_after

    # An empty body must be sent as `null`, not `{}` — matching what the coordinator
    # expects for an unscoped request.
    payload = json.dumps(body if body else None).encode()

    headers = {
        "Reactor-API-Key": api_key,
        "Content-Type": "application/json",
    }
    own = {name.lower() for name in headers}
    headers.update(
        {name: value for name, value in (extra_headers or {}).items() if name.lower() not in own}
    )
    request = urllib.request.Request(url, data=payload, method="POST", headers=headers)

    try:
        with urllib.request.urlopen(request, timeout=_TIMEOUT_S) as response:
            document = json.loads(response.read())
    except urllib.error.HTTPError as exc:
        # The body usually says which of key, model or permissions was the problem, so
        # it is worth more than the status line alone.
        detail = exc.read().decode(errors="replace").strip()
        raise AuthError(
            f"could not exchange the API key for a token: {exc.code} {exc.reason}"
            + (f" — {detail}" if detail else "")
        ) from exc
    except urllib.error.URLError as exc:
        raise AuthError(f"could not reach {url}: {exc.reason}") from exc
    except json.JSONDecodeError as exc:
        raise AuthError(f"{url} did not return JSON") from exc

    token = document.get("jwt")
    if not isinstance(token, str) or not token:
        raise AuthError(f"{url} returned no token")

    _log.debug(
        "exchanged the API key for a %s token", "scoped" if models or sessions else "unscoped"
    )
    return token
