"""Shared guards for the test suite.

The one below exists because getting it wrong does not fail a test — it kills the
interpreter, and takes every result after it with it.
"""

from __future__ import annotations

import pytest

from reactor_sdk import Reactor, client


@pytest.fixture(autouse=True, scope="session")
def _never_destroy_a_fabricated_handle() -> object:
    """Stop `__del__` handing a test's fake handle to the real `reactor_destroy`.

    Tests that need a client to look connected assign `_handle` directly, since a
    real one costs a session. That integer then looks exactly like a live pointer
    to `__del__`, and dereferencing it is a segmentation fault.

    Patching `_destroy_handle` per test does not cover it. Nothing says when the
    client is collected: a `pytest.raises` keeps the traceback, the traceback keeps
    the frame, and the frame keeps the client — so it is a later generational GC,
    long after that test's patch was undone, that reaches `__del__`. It fails as a
    crash in an unrelated test, or not at all, depending on timing that differs
    between a laptop and CI.

    So the guard is session-wide, and precise about what it skips: a client whose
    handle did not come from `_create_handle` never registered in `_LIVE_CLIENTS`,
    which is exactly the fabricated ones. Every real client still takes the real
    path, so this hides nothing that `_destroy_handle` does.
    """
    real_destroy = Reactor._destroy_handle

    def destroy_unless_fabricated(self: Reactor) -> None:
        if self._handle is not None and self not in client._LIVE_CLIENTS:
            self._handle = None
            return
        real_destroy(self)

    Reactor._destroy_handle = destroy_unless_fabricated  # type: ignore[method-assign]
    try:
        yield
    finally:
        Reactor._destroy_handle = real_destroy  # type: ignore[method-assign]
