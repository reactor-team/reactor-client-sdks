"""Pure trend/count assertions for the endurance-tests suite: given the raw
numbers a run already collected, decide pass/fail. No FFI, no `reactor_sdk`,
no live service.

Split out of helpers.py (which does need all of that, to actually drive a
run) so these — the one place this suite's pass/fail *semantics* live — can
be unit-tested directly. helpers.py imports ../integration-tests/conftest.py
at module scope for its real-FFI plumbing (new_reactor, paced_connect, ...),
which makes `import helpers` require a built `reactor_sdk`; these functions
never needed that in the first place. See ../tests/test_trends.py.
"""

from __future__ import annotations

import dataclasses
import os
import statistics

from report import MetricResult

# Same knob as helpers.py's own copy of this line (that one re-exports this
# one) — see its docstring for why duration is wall-clock, not iteration
# count.
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
    unit: str = "",
) -> MetricResult:
    """Fail if `values` is *still climbing in the back half of the run*: its
    last-third mean exceeds its middle-third mean (after dropping
    `warmup_fraction` to let one-time costs — import caches, connection
    setup, allocator warm-up — settle) by more than `max_growth_ratio`, *and*
    by more than `min_absolute_delta` in absolute terms.

    Deliberately last-vs-*middle*, not last-vs-first: a real CI run showed
    RSS flat, then a one-time ramp to a new plateau roughly in the middle of
    the window, then flat again for the rest of the run — a native
    buffer/pool growing once to its steady-state size, not an unbounded
    leak. Comparing against the *first* third means exactly where that one
    ramp happens to land is what decides pass/fail (whether the ramp's start
    lands inside the first-third window at all), which is timing, not
    signal — the same total jump measured as a comfortable pass or a
    razor-thin fail purely depending on when in the run it occurred, as
    happened across two otherwise-similar CI runs. Comparing the last third
    to the *middle* third instead asks the more direct question — "did it
    keep growing after that", not "is the end higher than the start" — so a
    one-time step that has already plateaued by the back third reads as flat
    (no fail), while something still genuinely climbing in the tail still
    trips it.

    The trade-off: a real leak that's still only in its early, slow-
    accelerating phase near the end of a short run could read as "already
    flat" and pass here where a first-vs-last comparison might have caught
    it. Preferred anyway — a slow leak has another cycle of this suite (or a
    longer ENDURANCE_DURATION_SECONDS run) to get caught once it's actually
    still climbing in *that* run's tail; a one-time step misread as a leak
    fails a real PR or blocks a release on nothing.

    `start`/`end`/`change` on the returned `MetricResult` (and the trend
    string in the printed verdict) still show the true first-third-to-last-
    third movement — that's still the right "how much did this move overall"
    number for a human reading the report — only the pass/fail decision
    itself is based on the middle-vs-last comparison.

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

    Returns a `MetricResult` (status "ok"/"fail") rather than raising: the
    caller collects one from every check so a full report table can be built
    even when one of them fails, instead of stopping at the first failure.
    `n < 6` (not enough samples for a first/middle/last split at all) still
    raises directly — that's a test misconfiguration
    (ENDURANCE_DURATION_SECONDS too short), not a metric to report on.
    """
    n = len(values)
    if n < 6:
        raise AssertionError(
            f"only {n} {name} sample(s) collected in {ENDURANCE_DURATION_SECONDS}s — "
            "raise ENDURANCE_DURATION_SECONDS to get enough data for a trend"
        )
    warmed_up = values[int(n * warmup_fraction) :]
    third = max(1, len(warmed_up) // 3)
    first, middle, last = (
        warmed_up[:third],
        warmed_up[third : 2 * third],
        warmed_up[-third:],
    )
    average = statistics.median if use_median else (lambda xs: sum(xs) / len(xs))
    first_mean = average(first)
    middle_mean = average(middle)
    last_mean = average(last)

    # What decides pass/fail — see the docstring for why this is middle-vs-
    # last, not first-vs-last.
    tail_delta = last_mean - middle_mean
    tail_ratio = (tail_delta / middle_mean) if middle_mean else (1.0 if tail_delta > 0 else 0.0)
    is_leak = tail_delta > min_absolute_delta and tail_ratio > max_growth_ratio

    # What's reported to a human — the overall first-to-last movement, a
    # different (and both useful) number from what decided the verdict above.
    overall_delta = last_mean - first_mean
    overall_ratio = (
        (overall_delta / first_mean) if first_mean else (1.0 if overall_delta > 0 else 0.0)
    )

    trend = (
        f"{first_mean:,.3f} → {middle_mean:,.3f} → {last_mean:,.3f} "
        f"(overall {overall_ratio:+.0%}, tail {tail_ratio:+.0%})"
    )
    if is_leak:
        reason = (
            f"still climbing in the tail (last third {tail_ratio:+.0%} over the middle "
            f"third) — over the {max_growth_ratio:.0%} threshold, looks like a real leak"
        )
    elif tail_delta <= min_absolute_delta:
        reason = (
            f"the {tail_delta:,.3f} tail change is under the {min_absolute_delta:,.3g} floor, "
            f"so it's noise (or an already-settled one-time step) regardless of the "
            f"{tail_ratio:+.0%} tail ratio"
        )
    else:
        reason = f"tail growth is under the {max_growth_ratio:.0%} threshold — not still climbing"
    print(f"[{name}] {'LEAK?' if is_leak else 'ok'}: {trend} — {reason}")

    return MetricResult(
        name=name,
        start=first_mean,
        end=last_mean,
        change=overall_delta,
        unit=unit,
        status="fail" if is_leak else "ok",
        detail=reason,
        threshold=f"still climbing: last third > {max_growth_ratio:.0%} over middle third "
        f"(min {min_absolute_delta:,.3g})",
        mid=middle_mean,
    )


def assert_always_zero(samples: list[Sample], *, field: str, unit: str = "count") -> MetricResult:
    """Fail if `field` was ever nonzero, on *any* cycle — for a count that
    should return to exactly 0 every time (e.g. live client handles right
    after `close()`), a trend isn't the right test: a leak on cycle 3 that
    happens to get cleaned up by cycle 40 is still a real bug.

    Always prints one line verdict, pass or fail — see
    `assert_no_sustained_growth`'s own docstring for why it returns a
    `MetricResult` instead of raising directly.
    """
    bad = [(s.cycle, getattr(s, field)) for s in samples if getattr(s, field) != 0]
    last_value = getattr(samples[-1], field)
    if bad:
        cycle, value = bad[0]
        peak_value = max(v for _, v in bad)
        reason = (
            f"nonzero on {len(bad)}/{len(samples)} cycles (first at cycle {cycle}: "
            f"{value}, peak {peak_value}) — a handle leaked mid-run"
        )
        print(f"[{field}] LEAK?: {reason}")
        return MetricResult(
            name=field,
            start=0,
            # Report the peak, not the final sample: if `field` already
            # settled back to 0 by the last cycle, `last_value` would render
            # as "went from 0 to 0 (+0)" in the Markdown/text reports (which
            # don't include `detail`) and hide the mid-run leak entirely.
            end=peak_value,
            change=peak_value,
            unit=unit,
            status="fail",
            detail=reason,
            threshold="always 0",
            first_bad_cycle=cycle,
        )
    reason = f"stayed at exactly 0 across all {len(samples)} cycles"
    print(f"[{field}] ok: {reason}")
    return MetricResult(
        name=field,
        start=0,
        end=last_value,
        change=last_value,
        unit=unit,
        status="ok",
        detail=reason,
        threshold="always 0",
    )


def assert_never_grows(samples: list[Sample], *, field: str, unit: str = "count") -> MetricResult:
    """Fail if `field`'s peak ever exceeds its starting value — for a count
    that's expected to stay flat across the whole run (not necessarily 0).

    Always prints one line verdict, pass or fail — see
    `assert_no_sustained_growth`'s own docstring for why it returns a
    `MetricResult` instead of raising directly.
    """
    baseline = getattr(samples[0], field)
    peak = max(getattr(s, field) for s in samples)
    if peak > baseline:
        reason = f"grew from {baseline} to {peak} during the run"
        print(f"[{field}] LEAK?: {reason}")
        return MetricResult(
            name=field,
            start=baseline,
            end=peak,
            change=peak - baseline,
            unit=unit,
            status="fail",
            detail=reason,
            threshold=f"never above {baseline}",
        )
    reason = f"never exceeded its starting value ({baseline}; peak seen was {peak})"
    print(f"[{field}] ok: {reason}")
    return MetricResult(
        name=field,
        start=baseline,
        end=peak,
        change=peak - baseline,
        unit=unit,
        status="ok",
        detail=reason,
        threshold=f"never above {baseline}",
    )


def standard_resource_metrics(
    samples: list[Sample],
    *,
    live_clients_baseline_zero: bool = False,
    fds_exact: bool = False,
) -> list[MetricResult]:
    """The RSS/CPU/thread/fd/handle checks every scenario in this suite runs
    at the end of its loop — live_clients, orphaned_callbacks, rss,
    cpu_s_per_cycle, cpu_percent, num_threads, num_fds — with the same
    thresholds every scenario has already converged on independently.
    Extracted so a new scenario is "write the loop body, call this," not
    another copy of this same ~40-line block: see ../README.md's "Adding a
    new scenario" and the sdk-from-ffi skill's "Endurance and leak tests"
    section for the shape this is meant to support.

    `live_clients_baseline_zero`: True for a scenario that starts with no
    connected client (a fresh `Reactor` per cycle, e.g. lifecycle-churn) —
    `live_clients` must be **exactly** 0 after every cycle
    (`assert_always_zero`). False (the default) for a scenario built around
    one long-lived session connected once by the `reactor` fixture — its
    baseline is 1, and `live_clients` must never exceed that
    (`assert_never_grows`).

    `fds_exact`: True when fd teardown is synchronous with this scenario's
    own cycle boundary (only lifecycle-churn's disconnect()/close() per
    cycle proved this in practice) — `num_fds` must never exceed its
    starting value (`assert_never_grows`). False (the default) for anything
    that can legitimately open a few more during warm-up and then plateau
    (a connection pool, a worker thread) — `num_fds` gets the same
    trend-based check as RSS/CPU instead.

    A scenario with its own extra invariants (session-churn's
    `pending_completions`/`Track._adapters`, publish-churn's `track.published`,
    pause-resume-churn's `reactor.paused_tracks`) still checks those itself,
    in its own loop — they are specific to what that scenario exercises, not
    generic resource accounting, so they do not belong here.
    """
    metrics: list[MetricResult] = []
    if live_clients_baseline_zero:
        metrics.append(assert_always_zero(samples, field="live_clients"))
    else:
        metrics.append(assert_never_grows(samples, field="live_clients"))
    metrics.append(assert_always_zero(samples, field="orphaned_callbacks"))
    if fds_exact:
        metrics.append(assert_never_grows(samples, field="num_fds"))
    metrics.append(
        assert_no_sustained_growth(
            [s.rss_bytes / 1e6 for s in samples],
            name="rss",
            unit="MB",
            max_growth_ratio=0.15,
            min_absolute_delta=5.0,
        )
    )
    metrics.append(
        assert_no_sustained_growth(
            cpu_deltas(samples),
            name="cpu_s_per_cycle",
            unit="s",
            max_growth_ratio=0.5,
            min_absolute_delta=0.05,
        )
    )
    metrics.append(
        assert_no_sustained_growth(
            [s.cpu_percent for s in samples],
            name="cpu_percent",
            unit="%",
            max_growth_ratio=0.5,
            min_absolute_delta=5.0,
        )
    )
    metrics.append(
        assert_no_sustained_growth(
            [float(s.num_threads) for s in samples],
            name="num_threads",
            unit="count",
            max_growth_ratio=0.15,
            min_absolute_delta=4,
            use_median=True,
        )
    )
    if not fds_exact:
        metrics.append(
            assert_no_sustained_growth(
                [float(s.num_fds) for s in samples],
                name="num_fds",
                unit="count",
                max_growth_ratio=0.15,
                min_absolute_delta=3,
            )
        )
    return metrics
