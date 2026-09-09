"""Endurance-loop plumbing: duration handling, resource sampling, and the
trend/count assertions built on top of it. See ../README.md for the full
design rationale and what each measured signal means.

Deliberately a plain module, not part of conftest.py: `conftest.py` here and
`../integration-tests/conftest.py` are both literally named `conftest.py`,
and both get loaded in the same interpreter (this suite imports the other's
module directly — see `conftest.py`'s own comment). Pytest resolves the bare
module name `conftest` to whichever of the two happened to claim it first, so
a plain `from conftest import ...` anywhere here is one collection-order
change away from silently importing the wrong file. Naming this module
something else sidesteps that ambiguity entirely — this is what `tests/*.py`
import from, not `conftest`.
"""

from __future__ import annotations

import dataclasses
import importlib.util
import os
import sys
import time
from pathlib import Path

import psutil

# Reuse integration-tests/conftest.py's real-FFI plumbing (new_reactor,
# paced_connect, solid_rgb_frame, the reactor/reactor_factory fixtures)
# instead of re-deriving it. Imported by explicit file path, under a
# qualified module name — not a sys.path-based `import conftest` — for the
# same reason this module isn't itself named conftest.py; see the module
# docstring above.
_INTEGRATION_TESTS_DIR = Path(__file__).parent.parent / "integration-tests"
_spec = importlib.util.spec_from_file_location(
    "reactor_endurance_tests._integration_conftest",
    _INTEGRATION_TESTS_DIR / "conftest.py",
)
assert _spec is not None and _spec.loader is not None
_integration_conftest = importlib.util.module_from_spec(_spec)
sys.modules[_spec.name] = _integration_conftest
_spec.loader.exec_module(_integration_conftest)

new_reactor = _integration_conftest.new_reactor
paced_connect = _integration_conftest.paced_connect
solid_rgb_frame = _integration_conftest.solid_rgb_frame
# Re-exported pytest fixtures, picked up from here by conftest.py (pytest
# discovers fixtures by scanning a conftest.py's module-level names for the
# fixture marker, regardless of which module originally defined the
# function) — `reactor` depends on `reactor_factory` by *name* (pytest's
# usual fixture DI), so both are re-exported together for that lookup to
# resolve.
reactor_factory = _integration_conftest.reactor_factory
reactor = _integration_conftest.reactor

# ── duration ─────────────────────────────────────────────────────────────────
#
# Wall-clock driven, not a fixed iteration count: one knob, shared by every
# scenario in this suite, so the exact same code runs as a quick manual sanity
# check today and as a long unattended nightly run later (REA-6088 built this
# manual-only; scheduling it is a deliberate follow-up). Short default so it
# "just works" without configuration — a real leak hunt overrides it directly.
ENDURANCE_DURATION_SECONDS = float(os.environ.get("ENDURANCE_DURATION_SECONDS", "300"))


@dataclasses.dataclass
class Sample:
    cycle: int
    elapsed_s: float
    rss_bytes: int
    cpu_s: float
    live_clients: int
    orphaned_callbacks: int


class ResourceSampler:
    """Samples process-wide resource usage plus the SDK's own handle-bookkeeping
    once per cycle of an endurance loop, against a shared wall-clock deadline.

    One `psutil.Process()` for the current process: every scenario here runs
    in-process (no subprocess per SDK), so process RSS/CPU already reflects
    `libreactor_ffi`'s native allocations, not just Python's own heap.
    """

    def __init__(self, *, duration_s: float = ENDURANCE_DURATION_SECONDS) -> None:
        self._process = psutil.Process()
        self._start = time.monotonic()
        self._duration_s = duration_s
        self.samples: list[Sample] = []

    def deadline_reached(self) -> bool:
        return time.monotonic() - self._start >= self._duration_s

    def sample(self, *, cycle: int) -> Sample:
        # Imported here, not at module scope: this reaches into reactor_sdk's
        # own internals (deliberately — see README.md), and importing lazily
        # keeps this module loadable even before reactor_sdk is installed
        # (e.g. `--collect-only`).
        import reactor_sdk.client as _client_module

        cpu = self._process.cpu_times()
        s = Sample(
            cycle=cycle,
            elapsed_s=time.monotonic() - self._start,
            rss_bytes=self._process.memory_info().rss,
            cpu_s=cpu.user + cpu.system,
            live_clients=len(_client_module._LIVE_CLIENTS),
            orphaned_callbacks=len(_client_module._ORPHANED_CALLBACKS),
        )
        self.samples.append(s)
        return s

    def print_report(self) -> None:
        # Per-cycle CPU (cpu_deltas), not the raw cumulative Sample.cpu_s: the
        # latter is psutil's total process CPU time since start, so it climbs
        # every row by construction — printing it invites reading "always
        # going up" as a leak signal, when it says nothing about whether any
        # single cycle is getting more expensive. See cpu_deltas()'s own
        # docstring; the first cycle has no prior sample to diff against.
        deltas = cpu_deltas(self.samples)
        print(
            f"\n{'cycle':>6} {'elapsed_s':>10} {'rss_mb':>10} {'cpu_s/cyc':>9} "
            f"{'live':>5} {'orphaned':>9}"
        )
        for i, s in enumerate(self.samples):
            cpu_per_cycle = f"{deltas[i - 1]:>9.3f}" if i > 0 else f"{'—':>9}"
            print(
                f"{s.cycle:>6} {s.elapsed_s:>10.1f} {s.rss_bytes / 1e6:>10.2f} "
                f"{cpu_per_cycle} {s.live_clients:>5} {s.orphaned_callbacks:>9}"
            )


# ── trend assertions ─────────────────────────────────────────────────────────


def cpu_deltas(samples: list[Sample]) -> list[float]:
    """Per-interval CPU consumption, derived from `Sample.cpu_s`.

    `cpu_s` itself is `psutil`'s cumulative process CPU time since the
    process started — monotonically non-decreasing by construction, so a
    trend check on the raw values would always report growth regardless of
    whether anything is actually getting more expensive (the same mistake as
    using `resource.getrusage().ru_maxrss` for memory — see `ResourceSampler`'s
    own docstring). Diffing consecutive samples reframes it as CPU spent
    *per interval*, which is what can actually flag a cycle getting slower
    over the course of a run.
    """
    return [b.cpu_s - a.cpu_s for a, b in zip(samples, samples[1:])]


def assert_no_sustained_growth(
    values: list[float],
    *,
    name: str,
    max_growth_ratio: float,
    min_absolute_delta: float = 0.0,
    warmup_fraction: float = 0.2,
) -> None:
    """Fail if `values`'s mean over the run's last third exceeds its mean over
    the first third (after dropping `warmup_fraction` to let one-time costs —
    import caches, connection setup, allocator warm-up — settle) by more than
    `max_growth_ratio`, *and* by more than `min_absolute_delta` in absolute
    terms.

    The absolute floor exists so a tiny, near-zero baseline can't turn an
    insignificant wobble into a ratio that looks huge. `values` must already
    be a *non-cumulative* measurement (raw RSS is fine as-is; CPU needs
    `cpu_deltas()` first — see its docstring). Not meant for exact-count
    fields (`live_clients`, `orphaned_callbacks`) — those are deterministic
    counts, not noisy measurements; use `assert_always_zero`/`assert_never_grows`
    for them instead.
    """
    n = len(values)
    if n < 6:
        raise AssertionError(
            f"only {n} {name} sample(s) collected in {ENDURANCE_DURATION_SECONDS}s — "
            "raise ENDURANCE_DURATION_SECONDS to get enough data for a trend"
        )
    warmed_up = values[int(n * warmup_fraction) :]
    third = max(1, len(warmed_up) // 3)
    first, last = warmed_up[:third], warmed_up[-third:]
    first_mean = sum(first) / len(first)
    last_mean = sum(last) / len(last)
    delta = last_mean - first_mean
    ratio = (delta / first_mean) if first_mean else (1.0 if delta > 0 else 0.0)
    if delta > min_absolute_delta and ratio > max_growth_ratio:
        raise AssertionError(
            f"{name} grew {ratio:.0%} across the run (from {first_mean:,.3f} to "
            f"{last_mean:,.3f}) — looks like a sustained leak, not noise"
        )


def assert_always_zero(samples: list[Sample], *, field: str) -> None:
    """Fail if `field` was ever nonzero, on *any* cycle — for a count that
    should return to exactly 0 every time (e.g. live client handles right
    after `close()`), a trend isn't the right test: a leak on cycle 3 that
    happens to get cleaned up by cycle 40 is still a real bug."""
    bad = [(s.cycle, getattr(s, field)) for s in samples if getattr(s, field) != 0]
    if bad:
        cycle, value = bad[0]
        raise AssertionError(
            f"{field} was nonzero on {len(bad)}/{len(samples)} cycles "
            f"(first at cycle {cycle}: {value}) — a handle leaked mid-run"
        )


def assert_never_grows(samples: list[Sample], *, field: str) -> None:
    """Fail if `field`'s peak ever exceeds its starting value — for a count
    that's expected to stay flat across the whole run (not necessarily 0)."""
    baseline = getattr(samples[0], field)
    peak = max(getattr(s, field) for s in samples)
    if peak > baseline:
        raise AssertionError(f"{field} grew from {baseline} to {peak} during the run")
