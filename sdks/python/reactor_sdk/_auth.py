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


class AuthError(RuntimeError):
    """Raised when an API key cannot be exchanged for a JWT."""


def fetch_jwt(
    api_key: str,
    api_url: str = DEFAULT_API_URL,
    *,
    models: list[str] | None = None,
    max_sessions: int | None = None,
    max_session_duration_seconds: int | None = None,
    expires_after: int | None = None,
) -> str:
    """Exchange `api_key` for a JWT, and return the token.

    ``api_url`` defaults to Reactor's production coordinator, the same default
    `Reactor()` itself uses — pass it explicitly only against a different one.

    Pass ``models`` to mint a **session-scoped** token: it can only create and operate
    sessions on those models, so a leak is worth a handful of sessions rather than
    everything the key can reach. Omitting it mints a token with the full set of
    permissions the key's roles allow — fine server-to-server, wrong to hand to a
    client you do not control.

    ``max_sessions`` caps how many sessions a scoped token may ever create, and is
    ignored for unscoped ones. ``max_session_duration_seconds`` force-terminates every
    session the token creates after that long (1-86400, i.e. up to 24h), independent of
    the token's own expiry, and is likewise ignored for unscoped ones; the server
    rejects an out-of-range value at mint rather than clamping it. ``expires_after`` is
    a lifetime in seconds, which the server clamps to its own ceiling.

    Raises:
        AuthError: if the exchange fails for any reason, including a response that
            does not carry a token.
    """
    url = f"{api_url.rstrip('/')}{_TOKENS_PATH}"

    body: dict[str, Any] = {}
    if models is not None:
        detail: dict[str, Any] = {
            "type": "session",
            "resources": {"models": {"match": list(models)}},
        }
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

    request = urllib.request.Request(
        url,
        data=payload,
        method="POST",
        headers={
            "Reactor-API-Key": api_key,
            "Content-Type": "application/json",
        },
    )

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

    _log.debug("exchanged the API key for a %s token", "scoped" if models else "unscoped")
    return token
