// Pure trend/count assertions for the endurance-tests suite: given the raw
// samples a run already collected, decide pass/fail. No FFI, no
// `reactor::sdk`, no live service — split out of helpers.hpp (which does
// need all of that, to actually drive a run) so these, the one place this
// suite's pass/fail *semantics* live, can be unit-tested directly. See
// ../tests/trends_test.cpp.
//
// Mirrors sdks/python/endurance-tests/trends.py — see its own docstrings for
// the fuller rationale behind each check; only what differs for this binding
// is repeated here.
#pragma once

#include <functional>
#include <string>
#include <vector>

#include "report.hpp"

namespace endurance {

/// `ENDURANCE_DURATION_SECONDS` env var, or 300 (five minutes) if unset —
/// read fresh on every call, not cached in a static: a static initialized
/// before a test harness sets the env var would freeze on the default.
double endurance_duration_seconds();

/// Per-interval CPU consumption, derived from `Sample::cpu_s`.
///
/// `cpu_s` is cumulative process CPU time since the process started —
/// monotonically non-decreasing by construction, so a trend check on the raw
/// values would always report growth regardless of whether anything is
/// actually getting more expensive. Diffing consecutive samples reframes it
/// as CPU spent *per interval*, which is what can actually flag a cycle
/// getting slower over the course of a run.
std::vector<double> cpu_deltas(const std::vector<Sample>& samples);

/// Fail if `values` is *still climbing in the back half of the run*: its
/// last-third mean exceeds its middle-third mean (after dropping
/// `warmup_fraction` to let one-time costs — allocator warm-up, connection
/// setup — settle) by more than `max_growth_ratio`, *and* by more than
/// `min_absolute_delta` in absolute terms.
///
/// Deliberately last-vs-*middle*, not last-vs-first: a real CI run showed a
/// metric flat, then a one-time ramp to a new plateau roughly in the middle
/// of the window, then flat again — a native buffer/pool growing once to
/// its steady-state size, not an unbounded leak. Comparing against the
/// *first* third means exactly where that one ramp happens to land is what
/// decides pass/fail (whether the ramp's start lands inside the first-third
/// window at all), which is timing, not signal — the same total jump
/// measured as a comfortable pass or a razor-thin fail purely depending on
/// when in the run it occurred, as happened across two otherwise-similar CI
/// runs. Comparing the last third to the *middle* third instead asks the
/// more direct question — "did it keep growing after that", not "is the end
/// higher than the start" — so a one-time step that has already plateaued
/// by the back third reads as flat (no fail), while something still
/// genuinely climbing in the tail still trips it.
///
/// The trade-off: a real leak that's still only in its early,
/// slow-accelerating phase near the end of a short run could read as
/// "already flat" and pass here where a first-vs-last comparison might have
/// caught it. Preferred anyway — a slow leak has another cycle of this
/// suite (or a longer `ENDURANCE_DURATION_SECONDS` run) to get caught once
/// it's actually still climbing in *that* run's tail; a one-time step
/// misread as a leak fails a real PR or blocks a release on nothing.
///
/// The returned `MetricResult`'s `start`/`end`/`change` (and the trend line
/// printed) still show the true first-third-to-last-third movement — the
/// right "how much did this move overall" number for a human reading the
/// report — only the pass/fail decision itself is based on the
/// middle-vs-last comparison. `mid` carries the middle-third mean itself.
///
/// The absolute floor exists so a tiny, near-zero baseline can't turn an
/// insignificant wobble into a ratio that looks huge. `values` must already
/// be a *non-cumulative* measurement (raw RSS is fine as-is; CPU needs
/// `cpu_deltas()` first). Not meant for exact-count fields — use
/// `assert_always_zero`/`assert_never_grows` for those instead.
///
/// `use_median=true` swaps the mean for a median within each window —
/// `num_threads` (and, in this binding, `num_fds` too — see
/// test_lifecycle_churn.cpp's own comment) has a documented one-cycle
/// artifact (a cycle that catches the previous cycle's native
/// thread/socket teardown still in flight, briefly double-counting), and on
/// a short run the "third" window can be small enough that a single such
/// cycle landing in the last window skews its *mean* enough to misread as a
/// sustained trend. A median shrugs off one outlier as long as it isn't the
/// majority of the window; a real, sustained leak still moves the median
/// just as surely as the mean.
///
/// Always prints one line verdict, pass or fail — not just on failure — so
/// a clean run still says *why* each signal looked fine.
///
/// Throws `std::runtime_error` when `values.size() < 6` (not enough samples
/// for a first/middle/last split at all) — that's a test misconfiguration
/// (`ENDURANCE_DURATION_SECONDS` too short), not a metric to report on.
MetricResult assert_no_sustained_growth(const std::vector<double>& values, const std::string& name,
                                        double max_growth_ratio, double min_absolute_delta = 0.0,
                                        double warmup_fraction = 0.2, bool use_median = false,
                                        const std::string& unit = "");

/// Fail if `field` (read via `get`) was ever nonzero, on *any* cycle — for a
/// count that should return to exactly 0 every time, a trend isn't the
/// right test: a leak on cycle 3 that happens to get cleaned up by cycle 40
/// is still a real bug.
///
/// Reports the *peak* nonzero value seen, not the final sample: a count
/// that already settled back to 0 by the last cycle would otherwise render
/// as "went from 0 to 0 (+0)" in the Markdown/text reports (which don't
/// include `detail`), hiding the leaked value from the primary failure
/// artifact entirely.
MetricResult assert_always_zero(const std::vector<Sample>& samples,
                                const std::function<long(const Sample&)>& get,
                                const std::string& name, const std::string& unit = "count");

/// Fail if `get(sample)`'s peak across `samples` ever exceeds its value on
/// the first sample — for a count that's expected to stay flat across the
/// whole run (not necessarily 0).
MetricResult assert_never_grows(const std::vector<Sample>& samples,
                                const std::function<long(const Sample&)>& get,
                                const std::string& name, const std::string& unit = "count");

/// The RSS/CPU/thread/fd checks every scenario in this suite runs at the end
/// of its loop, with the same thresholds every scenario has already
/// converged on independently. Extracted so a new scenario is "write the
/// loop body, call this," not another copy of this same block.
///
/// No `live_clients_baseline_zero`/`fds_exact` parameters, unlike Python's
/// identical function: this binding has no `live_clients` equivalent to
/// check at all (see ../README.md's "known, deliberate scope gap"), and
/// `num_fds` has never proven exact enough in a real run of this binding to
/// use `assert_never_grows` for it — both scenarios that exist today use the
/// same median-backed trend check as `num_threads` (see
/// test_lifecycle_churn.cpp's own comment on why).
///
/// A scenario with its own extra invariant (publish-churn's
/// `track.published()`, pause-resume-churn's `track.paused()`) still checks
/// that itself, in its own loop — specific to what that scenario exercises,
/// not generic resource accounting, so it does not belong here.
std::vector<MetricResult> standard_resource_metrics(const std::vector<Sample>& samples);

}  // namespace endurance
