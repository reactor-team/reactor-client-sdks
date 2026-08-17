"""
Minimal Reactor client helper used by the examples.

Usage::

    from examples.reactor_client import make_reactor

    async def main():
        r = make_reactor()
        await r.connect()
        ...
"""

from __future__ import annotations

import os

from reactor_sdk import Reactor


def make_reactor(
    api_url: str | None = None,
    model_name: str | None = None,
    jwt: str | None = None,
    local: bool | None = None,
) -> Reactor:
    """
    Build a Reactor from environment variables with optional overrides.

    Environment variables:
        REACTOR_API_URL   — coordinator base URL (default: https://api.reactor.inc)
        REACTOR_MODEL     — model name (required unless ``model_name`` is given)
        REACTOR_JWT       — JWT token (optional; skipped for local mode)
        REACTOR_LOCAL     — set to "1" to use local mode
    """
    resolved_url = api_url or os.environ.get("REACTOR_API_URL", "https://api.reactor.inc")
    resolved_model = model_name or os.environ.get("REACTOR_MODEL", "")
    resolved_jwt = jwt or os.environ.get("REACTOR_JWT") or None
    resolved_local = local if local is not None else os.environ.get("REACTOR_LOCAL") == "1"

    if not resolved_model:
        raise ValueError("model_name is required (set REACTOR_MODEL or pass model_name=)")

    return Reactor(
        resolved_model,
        api_url=resolved_url,
        jwt=resolved_jwt,
        local=resolved_local,
    )
