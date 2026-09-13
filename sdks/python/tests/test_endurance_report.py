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

sys.path.insert(0, str(Path(__file__).parent.parent / "endurance-tests"))

from report import (  # noqa: E402
    MetricResult,
    RunResult,
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
