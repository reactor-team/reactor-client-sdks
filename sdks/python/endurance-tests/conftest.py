"""Fixtures for the Python SDK endurance/leak-detection suite.

Real `reactor_sdk.Reactor` clients — real FFI, real WebRTC — against a real
model in production (`reactor/echo` by default), same as
`sdks/python/integration-tests/`. See ../README.md for the full design
rationale and what each measured signal means; see helpers.py for the
duration/sampling/assertion plumbing `tests/*.py` actually import (not this
file — see helpers.py's own docstring for why).

Lives outside `tests/`'s `testpaths` and outside `integration-tests/` itself
(REA-6088 built this as a sibling suite, not a subdirectory) — like
`integration-tests/`, only picked up by its own dedicated `mise` task, never
by `mise run test:python`.
"""

from __future__ import annotations

import sys
from pathlib import Path

# So `tests/*.py` can do `from helpers import ...` under the root
# pyproject.toml's `--import-mode=importlib`, which — unlike the legacy import
# modes — does not add a conftest.py's own directory to sys.path for free.
# Same trick `integration-tests/conftest.py` already uses, for the same reason.
sys.path.insert(0, str(Path(__file__).parent))

from helpers import reactor, reactor_factory  # noqa: E402 (needs the sys.path insert above)

__all__ = ["reactor", "reactor_factory"]
