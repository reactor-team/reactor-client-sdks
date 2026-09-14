"""Tests for the endurance-tests reporting layer (../endurance-tests/report.py).

Pure formatting/aggregation logic, no FFI/reactor_sdk involved — these build a
`RunResult` by hand and check what `write_reports()`/`render_markdown()`/
`render_text()` produce from it. Belongs in the fast unit suite (run by `mise
run test:python`), not endurance-tests/ itself: this is not a long-running
soak test, and report.py has no live-service dependency to skip without one.

Imported by explicit sys.path insert, not a package import: report.py lives
in ../endurance-tests, a sibling suite outside this package's normal import
path.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent.parent / "endurance-tests"))

import report as report_module  # noqa: E402
from report import (  # noqa: E402
    MetricResult,
    RunResult,
    compute_checkpoints,
    finish_run,
    render_markdown,
    render_text,
    write_reports,
)


def _ok_metric(name: str, start: float, end: float, unit: str = "MB") -> MetricResult:
    return MetricResult(
        name=name,
        start=start,
        end=end,
        change=end - start,
        unit=unit,
        status="ok",
        detail=f"{name} stayed within bounds",
        threshold="> 15% growth (min 5)",
    )


def _fail_metric(name: str, start: float, end: float, unit: str = "MB") -> MetricResult:
    return MetricResult(
        name=name,
        start=start,
        end=end,
        change=end - start,
        unit=unit,
        status="fail",
        detail=f"{name} grew past the threshold",
        threshold="> 15% growth (min 5)",
        first_bad_cycle=42,
    )


def _passing_result() -> RunResult:
    return RunResult(
        test_name="session-churn",
        sdk="Python",
        status="PASS",
        duration_s=7200,
        elapsed_s=7205,
        iterations=2184,
        started_at="2026-09-13T10:00:00+00:00",
        ended_at="2026-09-13T12:00:05+00:00",
        metrics=[
            _ok_metric("rss", 284.0, 291.0),
            _ok_metric("num_threads", 14, 14, unit="count"),
            _ok_metric("num_fds", 23, 23, unit="count"),
        ],
        errors=0,
        commit_sha="abc123def456abc123def456",
    )


def _failing_result() -> RunResult:
    result = _passing_result()
    result.status = "FAIL"
    result.metrics = [
        _fail_metric("rss", 284.0, 612.0),
        _ok_metric("num_threads", 14, 14, unit="count"),
    ]
    return result


class TestPassingReport:
    def test_status_is_pass(self) -> None:
        assert _passing_result().status == "PASS"

    def test_conclusion_mentions_tested_metrics_and_does_not_claim_untested_ones(self) -> None:
        md = render_markdown(_passing_result())
        assert "rss" in md
        assert "num_threads" in md
        assert "num_fds" in md
        assert "PASS" in md

    def test_metric_statuses_render_as_stable(self) -> None:
        md = render_markdown(_passing_result())
        assert "Stable" in md
        assert "FAILED" not in md

    def test_duration_and_iteration_count_included(self) -> None:
        md = render_markdown(_passing_result())
        assert "2,184 iterations" in md
        assert "2h 00m" in md

    def test_sdk_name_says_sdk(self) -> None:
        # Regression: an earlier draft rendered "The Python completed..."
        # (missing "SDK") — caught by actually reading a generated report,
        # not by a test that only checked the header line.
        md = render_markdown(_passing_result())
        conclusion = md.split("## Conclusion", 1)[1]
        assert "The Python SDK completed" in conclusion

    def test_diagnostics_heading_does_not_say_failure_when_passing(self) -> None:
        # Regression: tracemalloc diagnostics print regardless of pass/fail
        # (see README.md), but a PASSing report titling that section
        # "Failure diagnostics" reads as if something went wrong.
        result = _passing_result()
        result.tracemalloc_top = ["some/file.py:10: size=1 KiB (+1 KiB)"]
        md = render_markdown(result)
        text = render_text(result)
        assert "Memory diagnostics" in md
        assert "Failure diagnostics" not in md
        assert "Memory diagnostics" in text
        assert "Failure diagnostics" not in text


class TestFailingReport:
    def test_status_is_fail(self) -> None:
        assert _failing_result().status == "FAIL"

    def test_actual_and_threshold_and_delta_included(self) -> None:
        md = render_markdown(_failing_result())
        assert "284.0 MB" in md
        assert "612.0 MB" in md
        assert "+328.0 MB" in md
        assert "15% growth" in md

    def test_failed_metric_is_identified_as_failed(self) -> None:
        md = render_markdown(_failing_result())
        assert "FAILED" in md

    def test_diagnostics_heading_says_failure_when_failing(self) -> None:
        result = _failing_result()
        result.tracemalloc_top = ["some/file.py:10: size=400 MB (+400 MB)"]
        md = render_markdown(result)
        assert "Failure diagnostics" in md
        assert "Memory diagnostics" not in md

    def test_conclusion_does_not_claim_pass(self) -> None:
        md = render_markdown(_failing_result())
        conclusion = md.split("## Conclusion", 1)[1]
        assert "PASS" not in conclusion
        assert "🔴" in conclusion

    def test_conclusion_names_the_failed_metric(self) -> None:
        md = render_markdown(_failing_result())
        conclusion = md.split("## Conclusion", 1)[1]
        assert "rss" in conclusion

    def test_first_bad_cycle_surfaced_when_available(self) -> None:
        md = render_markdown(_failing_result())
        assert "cycle 42" in md


class TestErrorReport:
    """A test that never got as far as evaluating a metric (an unhandled
    exception, an insufficient-samples guard) — distinct from a threshold
    violation: no metric failed, the run itself didn't complete.
    """

    def _error_result(self) -> RunResult:
        return RunResult(
            test_name="lifecycle-churn",
            sdk="Python",
            status="ERROR",
            duration_s=300,
            elapsed_s=12.0,
            iterations=1,
            started_at="2026-09-13T10:00:00+00:00",
            ended_at="2026-09-13T10:00:12+00:00",
            metrics=[],
            run_error="RateLimitedError: quota exceeded",
        )

    def test_status_is_error_not_fail(self) -> None:
        assert self._error_result().status == "ERROR"

    def test_conclusion_mentions_the_run_error(self) -> None:
        md = render_markdown(self._error_result())
        assert "RateLimitedError" in md

    def test_conclusion_does_not_claim_pass_or_metric_failure(self) -> None:
        md = render_markdown(self._error_result())
        conclusion = md.split("## Conclusion", 1)[1]
        assert "PASS" not in conclusion

    def test_no_metrics_table_when_there_are_no_metrics(self) -> None:
        md = render_markdown(self._error_result())
        assert "| Metric |" not in md


class TestMissingOptionalDiagnostics:
    def test_no_tracemalloc_data_does_not_crash(self) -> None:
        result = _passing_result()
        assert result.tracemalloc_top is None
        md = render_markdown(result)
        text = render_text(result)
        assert "Failure diagnostics" not in md
        assert "Failure diagnostics" not in text

    def test_no_commit_sha_does_not_crash(self) -> None:
        result = _passing_result()
        result.commit_sha = None
        md = render_markdown(result)
        assert "Commit" not in md

    def test_empty_metrics_list_does_not_crash(self) -> None:
        result = _passing_result()
        result.metrics = []
        render_markdown(result)
        render_text(result)

    def test_no_samples_means_no_timeline_and_does_not_crash(self) -> None:
        result = _passing_result()
        assert result.samples == []
        md = render_markdown(result)
        text = render_text(result)
        assert "## Timeline" not in md
        assert "Timeline\n--------" not in text


def _flat_ramp_flat_samples(n: int = 100) -> list[dict]:
    """RSS flat for the first ~40%, ramps for the middle ~40%, flat again for
    the last ~20% — the exact shape the real CI run that motivated
    compute_checkpoints() showed (see PR discussion): a first-third-vs-
    last-third trend check correctly flags growth here, but reading only
    that average makes it look like a steady climb the whole run, when the
    metric was actually flat most of the time with one ramp in the middle.
    """
    samples = []
    for i in range(n):
        pct = i / (n - 1)
        if pct < 0.3:
            rss = 110.0
        elif pct < 0.6:
            rss = 110.0 + (pct - 0.3) / 0.3 * 18.0
        else:
            rss = 128.0
        samples.append(
            {
                "cycle": i,
                "elapsed_s": pct * 300,
                "rss_bytes": rss * 1e6,
                "num_threads": 23,
                "num_fds": 22,
                "cpu_percent": 3.6,
            }
        )
    return samples


class TestTimeline:
    def test_compute_checkpoints_returns_requested_count(self) -> None:
        checkpoints = compute_checkpoints(_flat_ramp_flat_samples(), n=5)
        assert len(checkpoints) == 5
        assert checkpoints[0]["pct"] == 0
        assert checkpoints[-1]["pct"] == 100

    def test_compute_checkpoints_on_empty_samples_returns_empty(self) -> None:
        assert compute_checkpoints([]) == []

    def test_checkpoints_reveal_plateau_then_ramp_then_plateau(self) -> None:
        # The whole point: a shape a first-third/last-third average alone
        # would flatten into "steady climb" is visible here as flat, then a
        # jump, then flat again.
        checkpoints = compute_checkpoints(_flat_ramp_flat_samples(), n=5)
        rss_by_pct = {c["pct"]: c["rss_mb"] for c in checkpoints}
        assert rss_by_pct[0] == pytest.approx(110.0, abs=0.5)
        assert rss_by_pct[25] == pytest.approx(110.0, abs=0.5)
        assert rss_by_pct[50] < rss_by_pct[75]
        assert rss_by_pct[75] == pytest.approx(128.0, abs=0.5)
        assert rss_by_pct[100] == pytest.approx(128.0, abs=0.5)

    def test_timeline_section_appears_in_markdown_and_text(self) -> None:
        result = _passing_result()
        result.samples = _flat_ramp_flat_samples()
        md = render_markdown(result)
        text = render_text(result)
        assert "## Timeline" in md
        assert "Timeline\n--------" in text
        assert "110.0 MB" in md
        assert "128.0 MB" in md

    def test_write_reports_computes_checkpoints_from_samples(self, tmp_path: Path) -> None:
        result = _passing_result()
        result.samples = _flat_ramp_flat_samples()
        assert result.checkpoints is None
        write_reports(result, out_dir=tmp_path)
        assert result.checkpoints is not None
        assert len(result.checkpoints) == 5

    def test_written_json_contains_checkpoints(self, tmp_path: Path) -> None:
        import json

        result = _passing_result()
        result.samples = _flat_ramp_flat_samples()
        paths = write_reports(result, out_dir=tmp_path)
        data = json.loads(paths["json"].read_text())
        assert len(data["checkpoints"]) == 5
        assert data["checkpoints"][0]["pct"] == 0


class TestJsonOutput:
    def test_written_json_contains_metadata_samples_metrics_and_status(
        self, tmp_path: Path
    ) -> None:
        import json

        result = _passing_result()
        result.samples = [{"cycle": 0, "rss_bytes": 1000}, {"cycle": 1, "rss_bytes": 1010}]
        paths = write_reports(result, out_dir=tmp_path)

        data = json.loads(paths["json"].read_text())
        assert data["test_name"] == "session-churn"
        assert data["sdk"] == "Python"
        assert data["status"] == "PASS"
        assert data["iterations"] == 2184
        assert len(data["samples"]) == 2
        assert len(data["metrics"]) == 3
        assert data["metrics"][0]["name"] == "rss"

    def test_write_reports_creates_all_three_files(self, tmp_path: Path) -> None:
        paths = write_reports(_passing_result(), out_dir=tmp_path)
        assert paths["json"].exists()
        assert paths["markdown"].exists()
        assert paths["text"].exists()
        assert paths["json"].name == "session-churn.json"
        assert paths["markdown"].name == "session-churn-report.md"
        assert paths["text"].name == "session-churn-summary.txt"


class TestMarkdownTextConsistency:
    def test_both_report_the_same_status(self) -> None:
        result = _failing_result()
        md = render_markdown(result)
        text = render_text(result)
        assert "FAIL" in md
        assert "FAIL" in text

    def test_both_mention_the_same_metric_values(self) -> None:
        result = _failing_result()
        md = render_markdown(result)
        text = render_text(result)
        for token in ("284.0 MB", "612.0 MB"):
            assert token in md
            assert token in text

    def test_both_include_the_same_conclusion_text(self) -> None:
        result = _passing_result()
        md = render_markdown(result)
        text = render_text(result)
        # The conclusion body (not the surrounding heading markup, which
        # legitimately differs between the two formats) should match.
        md_conclusion = md.split("## Conclusion", 1)[1].strip()
        text_conclusion = text.split("Conclusion\n----------", 1)[1].strip()
        # The trailing "artifacts available" line is markdown-italicized in
        # the .md rendering (`_..._`) and plain in .txt — strip that markup
        # before comparing, since the content itself should still match.
        md_lines = [line.strip("_") for line in md_conclusion.splitlines()[-3:]]
        text_lines = text_conclusion.splitlines()[-3:]
        assert md_lines == text_lines


class TestErrorsField:
    """`RunResult.errors` is `int | None`, not defaulted to 0 — a scenario
    that never actually counts anything (test_session_churn.py: nothing in
    its loop catches/retries a failure) leaves it unset rather than render a
    hardcoded-looking "Errors 0" that was never really measured. See
    report.py's own comment on the field and _conclusion_lines().
    """

    def test_errors_none_omits_the_row_in_markdown_and_text(self) -> None:
        result = _passing_result()
        result.errors = None
        md = render_markdown(result)
        text = render_text(result)
        assert "Errors" not in md
        assert "Errors" not in text

    def test_errors_none_omits_the_conclusion_sentence(self) -> None:
        result = _passing_result()
        result.errors = None
        md = render_markdown(result)
        conclusion = md.split("## Conclusion", 1)[1]
        assert "test errors occurred" not in conclusion
        assert "transient error" not in conclusion

    def test_errors_zero_is_still_shown_as_a_real_measurement(self) -> None:
        result = _passing_result()
        result.errors = 0
        md = render_markdown(result)
        assert "| Errors | 0 | — | 0 |" in md
        conclusion = md.split("## Conclusion", 1)[1]
        assert "No test errors occurred." in conclusion

    def test_errors_nonzero_is_reported(self) -> None:
        result = _passing_result()
        result.errors = 3
        md = render_markdown(result)
        conclusion = md.split("## Conclusion", 1)[1]
        assert "3 transient error(s) occurred" in conclusion


class TestFinishRun:
    """finish_run() — the exc_info() -> status -> RunResult -> write_reports()
    sequence every scenario's `finally` block needs, deduplicated into one
    place (was previously copied into both test_lifecycle_churn.py and
    test_session_churn.py, and had already drifted: only one had
    tracemalloc_top).
    """

    def test_pass_when_no_metric_failed_and_nothing_raised(self, tmp_path: Path) -> None:
        result = finish_run(
            test_name="lifecycle-churn",
            sdk="Python",
            duration_s=300,
            started_at="2026-09-13T10:00:00+00:00",
            samples=[],
            metrics=[_ok_metric("rss", 1.0, 1.0)],
            iterations=5,
            out_dir=tmp_path,
        )
        assert result.status == "PASS"
        assert result.run_error is None

    def test_fail_when_a_metric_failed_and_nothing_raised(self, tmp_path: Path) -> None:
        result = finish_run(
            test_name="lifecycle-churn",
            sdk="Python",
            duration_s=300,
            started_at="2026-09-13T10:00:00+00:00",
            samples=[],
            metrics=[_fail_metric("rss", 1.0, 2.0)],
            iterations=5,
            out_dir=tmp_path,
        )
        assert result.status == "FAIL"
        assert result.run_error is None

    def test_error_when_an_exception_is_in_flight_regardless_of_metrics(
        self, tmp_path: Path
    ) -> None:
        # finish_run() must be called from inside a `finally` block to see
        # the in-flight exception via sys.exc_info() — reproduced here the
        # same way.
        try:
            try:
                raise RuntimeError("boom")
            finally:
                result = finish_run(
                    test_name="lifecycle-churn",
                    sdk="Python",
                    duration_s=300,
                    started_at="2026-09-13T10:00:00+00:00",
                    samples=[],
                    metrics=[],
                    iterations=1,
                    out_dir=tmp_path,
                )
        except RuntimeError:
            pass
        assert result.status == "ERROR"
        assert result.run_error == "RuntimeError: boom"

    def test_writes_reports_to_out_dir(self, tmp_path: Path) -> None:
        finish_run(
            test_name="lifecycle-churn",
            sdk="Python",
            duration_s=300,
            started_at="2026-09-13T10:00:00+00:00",
            samples=[],
            metrics=[_ok_metric("rss", 1.0, 1.0)],
            iterations=5,
            out_dir=tmp_path,
        )
        assert (tmp_path / "lifecycle-churn.json").exists()
        assert (tmp_path / "lifecycle-churn-report.md").exists()

    def test_write_reports_failure_does_not_mask_the_original_test_exception(
        self, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        # Regression: write_reports() raising inside the `finally` block used
        # to replace the test's own exception in pytest's output — a report-
        # writing bug (disk full, a non-serialisable field) would hide the
        # actual failure. finish_run() must swallow it instead.
        def _raise(*_args: object, **_kwargs: object) -> None:
            raise OSError("disk full")

        monkeypatch.setattr(report_module, "write_reports", _raise)
        try:
            try:
                raise RuntimeError("the real failure")
            finally:
                result = finish_run(
                    test_name="lifecycle-churn",
                    sdk="Python",
                    duration_s=300,
                    started_at="2026-09-13T10:00:00+00:00",
                    samples=[],
                    metrics=[],
                    iterations=1,
                    out_dir=tmp_path,
                )
        except RuntimeError as e:
            assert str(e) == "the real failure"
        else:
            pytest.fail("the original RuntimeError should have propagated")
        assert result.status == "ERROR"
