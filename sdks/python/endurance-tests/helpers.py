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

import asyncio
import dataclasses
import importlib.util
import os
import sys
import time
from pathlib import Path
from typing import Any

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


async def pump_until_frame_received(
    track: Any, frame: Any, received: list[Any], *, timeout: float = 2.0, fps: float = 30.0
) -> bool:
    """Push `frame` into `track` at ~`fps` until something lands in `received`
    (appended to by an `on_frame`/`on_raw_frame` callback registered on the
    *receiving* track — e.g. `main_video`) or `timeout` elapses.

    Exists so the endurance loops also exercise the receive path — registering
    and tearing down a frame handler — not just publish/push_frame, which never
    touches `Track._adapters`/`Reactor._handlers` at all. Best-effort and never
    raises: this suite is about churn/leak detection over many cycles, not
    frame-delivery correctness (`integration-tests/` already covers that with a
    real timeout-and-fail `wait_until`) — one cycle where nothing happened to
    arrive in time just means that cycle's receive path went untouched, not a
    failure worth stopping an hours-long run over.
    """
    deadline = time.monotonic() + timeout
    interval = 1.0 / fps
    while not received and time.monotonic() < deadline:
        track.push_frame(frame)
        await asyncio.sleep(interval)
    return bool(received)


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
    # OS-level, not SDK-level — a leaked native thread (the Rust runtime not
    # joining a worker) or a leaked socket/fd (a WebRTC connection not fully
    # torn down) is a classic class of bug in networking code that RSS alone
    # can miss for a while: a few thousand small, live allocations (thread
    # stacks, socket buffers) don't always show up as a dramatic RSS jump
    # before something else (an FD or thread ceiling) fails first.
    num_threads: int
    num_fds: int


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
            num_threads=self._process.num_threads(),
            # Unix-only (no Windows equivalent in psutil — num_handles() there
            # counts a different, non-comparable thing) — fine here, this SDK's
            # CI only runs this suite on macOS/Linux.
            num_fds=self._process.num_fds(),
        )
        self.samples.append(s)
        return s

    # The report table's columns, in plain language — read this before reading
    # a printed table, not just README.md's own copy of the same explanation:
    #
    #   cycle           which iteration of the loop this row is (0, 1, 2, ...).
    #   elapsed_s       seconds since this test started.
    #   ram_mb          physical RAM this whole process is using right now
    #                   (not just the SDK — Python, libreactor_ffi, everything
    #                   in this one process). Should plateau, not keep climbing.
    #   cpu_s_per_cycle CPU time *this one cycle* burned (not a running total —
    #                   see cpu_deltas()'s own docstring for why that matters).
    #                   Should stay roughly flat cycle to cycle.
    #   live_clients    how many Reactor clients still have an open native
    #                   connection right now. 0 in test_lifecycle_churn.py
    #                   (each cycle closes its own client); 1 in
    #                   test_session_churn.py (one client, the whole run).
    #                   Should never exceed that.
    #   orphaned_cbs    frame/event callbacks the SDK couldn't confirm were
    #                   safe to free when a client closed. Should always be 0.
    #   num_threads     OS-level threads this process currently has (mostly
    #                   the native Rust runtime's). Some warm-up wobble is
    #                   normal; should not keep climbing.
    #   num_fds         open file descriptors (sockets, mainly — every WebRTC
    #                   connection needs some). Should not keep climbing.
    def print_report(self) -> None:
        # Per-cycle CPU (cpu_deltas), not the raw cumulative Sample.cpu_s: the
        # latter is psutil's total process CPU time since start, so it climbs
        # every row by construction — printing it invites reading "always
        # going up" as a leak signal, when it says nothing about whether any
        # single cycle is getting more expensive. See cpu_deltas()'s own
        # docstring; the first cycle has no prior sample to diff against.
        deltas = cpu_deltas(self.samples)
        print(
            f"\n{'cycle':>6} {'elapsed_s':>10} {'ram_mb':>8} {'cpu_s_per_cycle':>15} "
            f"{'live_clients':>12} {'orphaned_cbs':>12} {'num_threads':>11} {'num_fds':>7}"
        )
        for i, s in enumerate(self.samples):
            cpu_per_cycle = f"{deltas[i - 1]:>15.3f}" if i > 0 else f"{'—':>15}"
            print(
                f"{s.cycle:>6} {s.elapsed_s:>10.1f} {s.rss_bytes / 1e6:>8.2f} "
                f"{cpu_per_cycle} {s.live_clients:>12} {s.orphaned_callbacks:>12} "
                f"{s.num_threads:>11} {s.num_fds:>7}"
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

    Always prints one line verdict, pass or fail — not just on failure — so a
    clean run still says *why* each signal looked fine, not just silence.
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
    is_leak = delta > min_absolute_delta and ratio > max_growth_ratio

    trend = f"{first_mean:,.3f} → {last_mean:,.3f} ({ratio:+.0%})"
    if is_leak:
        reason = (
            f"over the {max_growth_ratio:.0%} growth threshold — looks like a real leak, not noise"
        )
    elif delta <= min_absolute_delta:
        reason = (
            f"the {delta:,.3f} change is under the {min_absolute_delta:,.3g} floor, "
            f"so it's noise regardless of the {ratio:+.0%} ratio"
        )
    else:
        reason = f"under the {max_growth_ratio:.0%} growth threshold"
    print(f"[{name}] {'LEAK?' if is_leak else 'ok'}: {trend} — {reason}")

    if is_leak:
        raise AssertionError(f"{name} grew {ratio:.0%} across the run ({trend}) — {reason}")


def assert_always_zero(samples: list[Sample], *, field: str) -> None:
    """Fail if `field` was ever nonzero, on *any* cycle — for a count that
    should return to exactly 0 every time (e.g. live client handles right
    after `close()`), a trend isn't the right test: a leak on cycle 3 that
    happens to get cleaned up by cycle 40 is still a real bug.

    Always prints one line verdict, pass or fail — see
    `assert_no_sustained_growth`'s own docstring for why.
    """
    bad = [(s.cycle, getattr(s, field)) for s in samples if getattr(s, field) != 0]
    if bad:
        cycle, value = bad[0]
        reason = (
            f"nonzero on {len(bad)}/{len(samples)} cycles (first at cycle {cycle}: "
            f"{value}) — a handle leaked mid-run"
        )
        print(f"[{field}] LEAK?: {reason}")
        raise AssertionError(f"{field} was {reason}")
    print(f"[{field}] ok: stayed at exactly 0 across all {len(samples)} cycles")


def assert_never_grows(samples: list[Sample], *, field: str) -> None:
    """Fail if `field`'s peak ever exceeds its starting value — for a count
    that's expected to stay flat across the whole run (not necessarily 0).

    Always prints one line verdict, pass or fail — see
    `assert_no_sustained_growth`'s own docstring for why.
    """
    baseline = getattr(samples[0], field)
    peak = max(getattr(s, field) for s in samples)
    if peak > baseline:
        reason = f"grew from {baseline} to {peak} during the run"
        print(f"[{field}] LEAK?: {reason}")
        raise AssertionError(f"{field} {reason}")
    print(f"[{field}] ok: never exceeded its starting value ({baseline}; peak seen was {peak})")
