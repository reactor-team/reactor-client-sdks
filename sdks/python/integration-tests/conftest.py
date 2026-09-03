"""Shared fixtures for the Python SDK integration suite.

Real `reactor_sdk.Reactor` clients — real FFI, real WebRTC — against a real model
in production (`reactor/echo` by default). Nothing here is mocked; that's the
point. See README.md.

Lives outside `tests/`'s `testpaths`, so it never runs as part of `mise run
test:python`'s mocked-`get_lib()` unit suite — only `mise run
test:python:integration-tests` (`pytest integration-tests/tests`) picks this
up, the same separation `sdks/js/integration-tests/` keeps from `sdks/js`'s
own vitest suite.
"""

from __future__ import annotations

import asyncio
import os
import struct
import sys
import time
import zlib
from collections.abc import AsyncIterator, Callable
from pathlib import Path
from typing import Any

# The root pyproject.toml's `--import-mode=importlib` (so `import reactor_sdk`
# resolves to the installed package rather than a sibling directory — see its
# own comment) means pytest does *not* add each conftest.py's directory to
# sys.path the way the legacy import modes do. Every spec here wants plain
# `from conftest import ...` for shared media fixtures below, not another
# pytest fixture layer for pure helper functions, so this directory is added
# back explicitly, once, rather than working around it five times over.
sys.path.insert(0, str(Path(__file__).parent))

import numpy as np
import pytest

from reactor_sdk import DEFAULT_API_URL, Reactor, fetch_jwt

# ── configuration ────────────────────────────────────────────────────────────
#
# Same env var names as sdks/js/integration-tests/harness/src/config.ts, so pointing
# one suite at a local runtime instead of production reads the same way as
# pointing the other.

API_URL = os.environ.get("REACTOR_API_URL", DEFAULT_API_URL)
MODEL_NAME = os.environ.get("REACTOR_MODEL_NAME", "reactor/echo")
LOCAL = os.environ.get("REACTOR_LOCAL", "").lower() in ("1", "true")
API_KEY = os.environ.get("INTEGRATION_TESTS_REACTOR_API_KEY")

if not LOCAL and not API_KEY:
    pytest.exit(
        "INTEGRATION_TESTS_REACTOR_API_KEY is required unless REACTOR_LOCAL=true "
        "— see sdks/python/integration-tests/README.md.",
        returncode=1,
    )


def new_reactor(*, model_name: str = MODEL_NAME, jwt: str | None = None) -> Reactor:
    """A `Reactor` configured from this suite's env, not yet connected.

    Unlike the JS harness, nothing mints a token up front here by default:
    the Python SDK exchanges `api_key` for a JWT itself, inside `connect()`
    (`client.py`'s `_resolve_token`) — there is no browser boundary to keep
    the key away from, so the key is simply handed to the constructor.

    Pass `jwt` to opt out of that and hand the client an already-minted token
    instead — required for session adoption (see `test_multi_connection.py`'s
    module docstring): the coordinator only accepts the token that *created*
    a session for a second connection to adopt it by id, not a fresh one
    minted per client, so a joiner needs the creator's own token, not its own
    `api_key`.
    """
    if jwt is not None:
        return Reactor(model_name=model_name, jwt=jwt, api_url=API_URL, local=LOCAL)
    return Reactor(model_name=model_name, api_key=API_KEY, api_url=API_URL, local=LOCAL)


# ── session-creation pacing ──────────────────────────────────────────────────
#
# reactor/echo's session-creation quota (sessions_per_minute) is enforced per
# API key across the whole suite, not per test — confirmed against prod: this
# suite alone, run in isolation, still tripped it (a burst of a few tests'
# worth of connects lands within the same window). Reacting after a 429
# (mise.toml's --reruns) is a safety net, not sufficient on its own when the
# suite's average pace is already close to the limit. Pacing every session
# creation through one process-wide gate keeps it under, deterministically.
# 8.0s (~7.5/min) matched the old 10/min quota; now 100/min, so 0.7s
# (~86/min) leaves real margin without being needlessly conservative —
# a RateLimitedError still gets rerun (mise.toml's --reruns) as a second
# line of defense.
_SESSION_CREATE_INTERVAL = 0.7  # seconds; ~86/min, under the 100/min quota
_session_create_lock = asyncio.Lock()
_last_session_create_at = 0.0


async def paced_connect(client: Reactor, /, **kwargs: Any) -> None:
    """`await client.connect(**kwargs)`, paced against every other call to
    this function in the process — not just other calls on `client` itself.

    `reconnect()` deliberately isn't routed through here: it reuses the
    existing session rather than creating a new one (see its own docstring),
    so it isn't what the quota this paces against is even counted against.
    """
    global _last_session_create_at
    async with _session_create_lock:
        now = asyncio.get_running_loop().time()
        wait = _last_session_create_at + _SESSION_CREATE_INTERVAL - now
        if wait > 0:
            await asyncio.sleep(wait)
        _last_session_create_at = asyncio.get_running_loop().time()
    await client.connect(**kwargs)


@pytest.fixture
async def reactor_factory() -> AsyncIterator[Callable[..., Reactor]]:
    """Creates `Reactor` clients and disconnects every one of them afterward.

    Mirrors `window.__harness.destroyAll()` in the JS suite's `afterEach` — a
    test that fails partway through must not leave a session running against
    the live model until it hits its own idle timeout.

    Torn down in *reverse* creation order, not concurrently: the JS suite's
    multi-connection test hit a real bug here — destroying a session's
    connections in parallel raced the creator's disconnect (which ends the
    session server-side) against a non-creator connection still leaving,
    producing spurious errors on whichever lost. Last created, first torn
    down keeps a session's non-creator connections gone before its creator.
    """
    created: list[Reactor] = []

    def factory(*, model_name: str = MODEL_NAME, jwt: str | None = None) -> Reactor:
        r = new_reactor(model_name=model_name, jwt=jwt)
        created.append(r)
        return r

    try:
        yield factory
    finally:
        for r in reversed(created):
            try:
                await r.disconnect()
            except Exception:
                pass
            r.close()


@pytest.fixture
async def reactor(reactor_factory: Callable[..., Reactor]) -> AsyncIterator[Reactor]:
    """One connected client — the common case every spec that isn't testing
    connection setup itself starts from."""
    r = reactor_factory()
    await paced_connect(r)
    yield r


async def mint_jwt(*, model_name: str = MODEL_NAME) -> str:
    """Mint one token from `API_KEY`, for callers that need to hand the
    *same* token to more than one `Reactor` — session adoption, specifically
    (see `new_reactor`'s docstring for why a joiner can't just mint its own).
    Synchronous under the hood (`fetch_jwt` is one blocking HTTP POST), run
    off the loop the same way `client.py`'s own `_resolve_token` does.
    """
    return await asyncio.to_thread(fetch_jwt, API_KEY, API_URL, models=[model_name])


async def wait_until(
    predicate: Callable[[], bool], *, timeout: float = 10.0, interval: float = 0.1
) -> None:
    """Poll `predicate` until it's true or `timeout` elapses.

    Used instead of `asyncio.Event`/`asyncio.Queue` for anything fed from
    `on_frame`/`on_raw_frame` callbacks: those run on the media delivery
    thread, not the event loop (`client.py`'s `_fire_on_track`, deliberately
    not marshalled through `_fire_on_loop`), and asyncio's own primitives are
    documented as unsafe to touch from off-loop threads. A plain list or
    counter a callback appends/increments to is fine to poll here — CPython's
    GIL makes `list.append` atomic — just not fine to signal via `.set()`.
    """
    deadline = time.monotonic() + timeout
    while not predicate():
        if time.monotonic() >= deadline:
            raise TimeoutError(f"condition not met within {timeout}s")
        await asyncio.sleep(interval)


# ── media fixtures ───────────────────────────────────────────────────────────
#
# Deterministic, synthetic frames — not a webcam/mic — so pixel assertions
# against reactor/echo's effects are exact rather than dependent on whatever a
# fake device happens to generate. Same reasoning as the JS suite's synthetic
# canvas/audio-tone fixtures (see sdks/js/integration-tests/harness/).


def solid_rgb_frame(width: int, height: int, color: tuple[int, int, int]) -> np.ndarray:
    """An RGB frame of `color`, shape (height, width, 3) — exactly what
    `Track.push_frame` accepts and `Track.on_frame` delivers, no BGRA
    conversion required on either side of the assertion."""
    frame = np.empty((height, width, 3), dtype=np.uint8)
    frame[:, :] = color
    return frame


def sine_wave_samples(
    num_samples: int, *, sample_rate: int = 48_000, num_channels: int = 1, frequency_hz: float = 440.0
) -> np.ndarray:
    """`num_samples` of a `frequency_hz` tone, shape (num_samples, num_channels) —
    exactly what an audio `Track.push_frame` accepts and `Track.on_frame` delivers.

    A4, comfortably audible and easy to reason about — reactor/echo passes audio
    through unchanged, so nothing here rides on the exact frequency.
    """
    t = np.arange(num_samples) / sample_rate
    tone = (np.sin(2 * np.pi * frequency_hz * t) * 8000).astype(np.int16)  # headroom under int16 max
    return np.tile(tone[:, None], (1, num_channels))


def assert_dominant_color(
    frame: np.ndarray, expected: tuple[int, int, int], *, tolerance: int = 30
) -> None:
    """Assert `frame`'s mean colour is within `tolerance` per channel of `expected`.

    Not exact equality: `main_video` has gone through a real WebRTC video
    encode/decode round trip by the time it reaches `on_frame`, and a lossy
    codec does not reproduce a solid fill exactly, especially at its edges.
    The mean over the whole frame is what a solid-colour input actually
    guarantees survives that.
    """
    mean = frame.reshape(-1, 3).mean(axis=0)
    diff = np.abs(mean - np.array(expected, dtype=np.float64))
    assert (diff <= tolerance).all(), (
        f"frame mean colour {tuple(mean.round(1))} is not within {tolerance} of expected {expected}"
    )


def solid_rgb_png(width: int, height: int, color: tuple[int, int, int]) -> bytes:
    """A minimal, valid solid-colour PNG.

    Hand-rolled rather than pulled from a fixtures directory or an imaging
    library: the SDK itself has zero runtime dependencies (`pyproject.toml`),
    and this suite's only other dependency beyond the stdlib is numpy — no
    reason to add Pillow just to build a one-colour test image. Uncompressed
    filter-0 scanlines, deflate via `zlib` (stdlib), three chunks.
    """

    def chunk(tag: bytes, data: bytes) -> bytes:
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    row = bytes(color) * width
    raw = b"".join(b"\x00" + row for _ in range(height))  # leading 0 = "none" filter
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)  # 8-bit depth, RGB, no interlace
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(raw))
        + chunk(b"IEND", b"")
    )
