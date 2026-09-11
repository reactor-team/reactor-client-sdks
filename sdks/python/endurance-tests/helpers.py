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
import statistics
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


async def connect_with_retries(
    client: Any, *, max_attempts: int = 5, retry_delay_s: float = 5.0
) -> None:
    """`await paced_connect(client)`, retrying up to `max_attempts` times with
    a fixed delay between attempts, before letting the last failure
    propagate.

    Written after a real CI run lost ~20 minutes of accumulated
    trend to one coordinator-side blip — the token-exchange endpoint
    answering a single, isolated 503 ("Token authorization is unavailable"),
    confirmed via Grafana as a one-off rather than a real outage. An
    endurance run is long and unattended specifically so a human doesn't
    have to babysit it; failing the whole run over a handful of seconds of
    transient unavailability defeats that. Retrying on a *non*-transient
    failure (a genuinely invalid key) just costs a few extra fast attempts
    before still failing with the same error — an acceptable trade for not
    losing hours of data to something that clears itself up in seconds.

    Catches both `ReactorError` (the native-layer/protocol errors) and
    `AuthError` (the pure-Python token-exchange path in `reactor_sdk._auth`,
    which is what actually raised for the 503 above) — imported here, not at
    module scope, for the same "loadable before reactor_sdk is installed"
    reason the rest of this module defers its `reactor_sdk` imports.
    """
    from reactor_sdk._auth import AuthError
    from reactor_sdk.errors import ReactorError

    for attempt in range(1, max_attempts + 1):
        try:
            await paced_connect(client)
            return
        except (ReactorError, AuthError):
            if attempt >= max_attempts:
                raise
            await asyncio.sleep(retry_delay_s)


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
    # % of one CPU core busy since the *previous* sample (psutil's own
    # interval-based cpu_percent(), not an instantaneous reading) — a
    # different question from cpu_s (derived into cpu_deltas() below): that
    # one is "how much actual CPU work did this cycle do", this is "how busy
    # was the CPU relative to how long the cycle took". A cycle that's mostly
    # waiting on the network can do the same CPU work in more wall-clock time
    # and show a *lower* percentage even with identical cpu_s — the two can
    # disagree, and that disagreement itself is informative. Can exceed 100%
    # if more than one native thread is genuinely busy at once; that's
    # expected, not a bug, given this SDK's multi-threaded Rust runtime.
    cpu_percent: float
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
        # psutil's own documented priming call: the *first* cpu_percent()
        # reading has no prior call to measure an interval against, so it's
        # meaningless (usually 0.0) — call it once now, throw the result
        # away, so the first real sample() below already measures a genuine
        # interval instead of a start-up artifact.
        self._process.cpu_percent(interval=None)

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
            # Interval since the *previous* call, not since process start —
            # psutil tracks that internally per-Process object, which is why
            # this is one long-lived self._process rather than a fresh one
            # per sample.
            cpu_percent=self._process.cpu_percent(interval=None),
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
    #   cpu_percent     % of one CPU core busy since the previous row (like
    #                   Activity Monitor/htop's own number) — a different
    #                   question from cpu_s_per_cycle: that one is "how much
    #                   CPU work happened", this is "how busy was the CPU
    #                   relative to how long the cycle took". Can read over
    #                   100% if more than one native thread is genuinely busy
    #                   at once — expected, not a bug. Should stay roughly
    #                   flat, same as cpu_s_per_cycle.
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
            f"{'cpu_percent':>11} {'live_clients':>12} {'orphaned_cbs':>12} "
            f"{'num_threads':>11} {'num_fds':>7}"
        )
        for i, s in enumerate(self.samples):
            cpu_per_cycle = f"{deltas[i - 1]:>15.3f}" if i > 0 else f"{'—':>15}"
            print(
                f"{s.cycle:>6} {s.elapsed_s:>10.1f} {s.rss_bytes / 1e6:>8.2f} "
                f"{cpu_per_cycle} {s.cpu_percent:>10.1f}% {s.live_clients:>12} "
                f"{s.orphaned_callbacks:>12} {s.num_threads:>11} {s.num_fds:>7}"
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
    use_median: bool = False,
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

    `use_median=True` swaps the mean for a median within each window —
    `num_threads` specifically has a documented one-cycle artifact (a cycle
    that catches the previous cycle's native thread teardown still in
    flight, briefly double-counting), and on a short run the "third" window
    can be small enough (as few as 2-3 samples) that a single such cycle
    landing in the last window skews its *mean* enough to misread as a
    sustained trend. A median shrugs off one outlier as long as it isn't the
    majority of the window; a real, sustained leak still moves the median
    just as surely as the mean.

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
    average = statistics.median if use_median else (lambda xs: sum(xs) / len(xs))
    first_mean = average(first)
    last_mean = average(last)
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
