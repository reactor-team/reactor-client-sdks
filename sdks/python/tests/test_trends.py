"""Tests for the endurance-tests suite's pass/fail semantics
(../endurance-tests/trends.py).

Pure functions, no FFI/reactor_sdk involved — split out of helpers.py
specifically so they could be unit-tested directly (helpers.py imports
../integration-tests/conftest.py at module scope, which requires a built
reactor_sdk; trends.py doesn't). See trends.py's own docstring.

Imported by explicit sys.path insert, not a package import — same reason as
test_endurance_report.py's own copy of this pattern.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent.parent / "endurance-tests"))

from trends import (  # noqa: E402
    Sample,
    assert_always_zero,
    assert_never_grows,
    assert_no_sustained_growth,
    cpu_deltas,
)


def _sample(cycle: int, **overrides: object) -> Sample:
    defaults: dict[str, object] = {
        "cycle": cycle,
        "elapsed_s": float(cycle),
        "rss_bytes": 100_000_000,
        "cpu_s": float(cycle) * 0.1,
        "cpu_percent": 5.0,
        "live_clients": 1,
        "orphaned_callbacks": 0,
        "num_threads": 20,
        "num_fds": 15,
    }
    defaults.update(overrides)
    return Sample(**defaults)  # type: ignore[arg-type]


class TestCpuDeltas:
    def test_diffs_consecutive_cumulative_values(self) -> None:
        samples = [_sample(i, cpu_s=cpu_s) for i, cpu_s in enumerate([0.0, 0.5, 1.2, 1.4])]
        assert cpu_deltas(samples) == pytest.approx([0.5, 0.7, 0.2])

    def test_empty_and_single_sample_yield_no_deltas(self) -> None:
        assert cpu_deltas([]) == []
        assert cpu_deltas([_sample(0)]) == []


class TestAssertNoSustainedGrowth:
    def test_too_few_samples_raises_not_returns(self) -> None:
        # n < 6 is a test misconfiguration (ENDURANCE_DURATION_SECONDS too
        # short), not a metric to report on — see the function's own
        # docstring for why this one case still raises directly.
        with pytest.raises(AssertionError, match="raise ENDURANCE_DURATION_SECONDS"):
            assert_no_sustained_growth([1.0, 2.0, 3.0], name="x", max_growth_ratio=0.15)

    def test_flat_values_pass(self) -> None:
        result = assert_no_sustained_growth(
            [100.0] * 30, name="rss", unit="MB", max_growth_ratio=0.15, min_absolute_delta=5.0
        )
        assert result.status == "ok"

    def test_still_climbing_in_the_tail_fails(self) -> None:
        # Genuinely still trending up in the last third relative to the
        # middle third, well past both the ratio and absolute floors.
        values = [100.0 + i * 5.0 for i in range(30)]
        result = assert_no_sustained_growth(
            values, name="rss", unit="MB", max_growth_ratio=0.15, min_absolute_delta=5.0
        )
        assert result.status == "fail"
        assert result.detail.startswith("still climbing")

    def test_one_time_ramp_that_has_already_plateaued_passes(self) -> None:
        # The exact shape that motivated middle-vs-last over first-vs-last
        # (see the function's own docstring): flat, then a one-time ramp to
        # a new plateau early in the run, then flat for the rest. The
        # *overall* first-to-last movement is large, but the tail (last
        # third vs middle third) is flat — a native buffer/pool growing once
        # to steady-state, not an unbounded leak, so this must pass.
        n = 30
        values = []
        for i in range(n):
            pct = i / (n - 1)
            if pct < 0.25:
                values.append(100.0)
            elif pct < 0.45:
                values.append(100.0 + (pct - 0.25) / 0.20 * 40.0)
            else:
                values.append(140.0)
        result = assert_no_sustained_growth(
            values, name="rss", unit="MB", max_growth_ratio=0.15, min_absolute_delta=5.0
        )
        assert result.status == "ok"
        # The reported start/end still show the true overall movement, even
        # though that's not what decided pass/fail.
        assert result.change > 20.0

    def test_min_absolute_delta_floor_suppresses_a_small_ratio_looking_big(self) -> None:
        # A tiny near-zero baseline can turn an insignificant wobble into a
        # ratio that looks huge — the absolute floor exists for exactly this.
        values = [0.01] * 15 + [0.02] * 15
        result = assert_no_sustained_growth(
            values, name="cpu_s_per_cycle", unit="s", max_growth_ratio=0.15, min_absolute_delta=0.05
        )
        assert result.status == "ok"

    def test_use_median_ignores_a_single_outlier_cycle(self) -> None:
        # A one-cycle double-counted-thread artifact (see the function's own
        # docstring on `use_median`) landing in the last window shouldn't by
        # itself read as a sustained trend.
        values = [25.0] * 12 + [25.0, 25.0, 31.0, 25.0, 25.0, 25.0]
        result = assert_no_sustained_growth(
            values,
            name="num_threads",
            unit="count",
            max_growth_ratio=0.15,
            min_absolute_delta=4,
            use_median=True,
        )
        assert result.status == "ok"


class TestAssertAlwaysZero:
    def test_stays_zero_passes(self) -> None:
        samples = [_sample(i, orphaned_callbacks=0) for i in range(10)]
        result = assert_always_zero(samples, field="orphaned_callbacks")
        assert result.status == "ok"

    def test_nonzero_mid_run_fails_even_if_it_settles_back_to_zero(self) -> None:
        # Regression: a leak on cycle 3 that happens to get cleaned up by
        # the final cycle is still a real bug — must not read as "ok" just
        # because the last sample is 0.
        values = [0, 0, 0, 2, 0, 0]
        samples = [_sample(i, orphaned_callbacks=v) for i, v in enumerate(values)]
        result = assert_always_zero(samples, field="orphaned_callbacks")
        assert result.status == "fail"
        assert result.first_bad_cycle == 3

    def test_reports_the_peak_not_the_final_sample(self) -> None:
        # Regression (codex review on PR #186): using the *final* sample's
        # value for `end`/`change` rendered "went from 0 to 0 (+0)" in the
        # Markdown/text reports (which don't include `detail`) whenever the
        # count had already settled back to 0 by the last cycle — hiding the
        # leaked value from the primary failure artifact entirely.
        values = [0, 0, 5, 3, 0, 0]
        samples = [_sample(i, orphaned_callbacks=v) for i, v in enumerate(values)]
        result = assert_always_zero(samples, field="orphaned_callbacks")
        assert result.status == "fail"
        assert result.end == 5
        assert result.change == 5
        assert "peak 5" in result.detail


class TestAssertNeverGrows:
    def test_flat_at_baseline_passes(self) -> None:
        samples = [_sample(i, live_clients=1) for i in range(10)]
        result = assert_never_grows(samples, field="live_clients")
        assert result.status == "ok"

    def test_growth_past_the_starting_value_fails(self) -> None:
        values = [1, 1, 1, 2, 1]
        samples = [_sample(i, live_clients=v) for i, v in enumerate(values)]
        result = assert_never_grows(samples, field="live_clients")
        assert result.status == "fail"
        assert result.start == 1
        assert result.end == 2
