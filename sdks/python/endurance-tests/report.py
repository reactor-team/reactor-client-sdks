"""Small reporting layer for the endurance-tests suite: turns the raw
Sample/MetricResult data the tests already collect into a compact live
status block, and — via `write_reports()` — a JSON dump, a Markdown report,
and a plain-text summary, all three rendered from the same `RunResult` so
there is exactly one source of truth instead of formats that can drift apart.

Deliberately does not import helpers.py: keeping this module free of the
reactor_sdk/FFI import chain means its own unit tests (../tests/
test_endurance_report.py) never need a live service or a built dylib — they
just build a RunResult by hand and check what comes out.
"""

from __future__ import annotations

import dataclasses
import json
import os
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

# Same on/off parsing as ENDURANCE_DURATION_SECONDS in helpers.py: one env
# var, no new CLI surface. Purpose is debugging a short run, not normal
# long-running execution — see ../README.md.
ENDURANCE_VERBOSE = os.environ.get("ENDURANCE_VERBOSE", "") not in ("", "0", "false", "False")

# How often the compact live block reprints during a run. 30s is frequent
# enough that opening a GitHub Actions log mid-run always shows something
# recent, and sparse enough that a multi-hour run doesn't scroll a huge log.
LIVE_INTERVAL_SECONDS = float(os.environ.get("ENDURANCE_LIVE_INTERVAL_SECONDS", "30"))

DEFAULT_OUTPUT_DIR = Path("endurance-results")


def now_iso() -> str:
    import datetime

    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def git_commit_sha() -> str | None:
    """Best-effort commit SHA for the report header. `GITHUB_SHA` (set by
    every GitHub Actions run) first, falling back to `git rev-parse HEAD` for
    a local run — never raises, since a missing commit SHA is fine to just
    omit (see RunResult/report rendering: absent fields are left out, not
    faked).
    """
    sha = os.environ.get("GITHUB_SHA")
    if sha:
        return sha
    try:
        out = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            capture_output=True,
            text=True,
            timeout=2,
            check=True,
        )
        return out.stdout.strip() or None
    except Exception:
        return None


def format_duration(seconds: float) -> str:
    seconds = max(0, int(seconds))
    h, rem = divmod(seconds, 3600)
    m, s = divmod(rem, 60)
    if h:
        return f"{h}h {m:02d}m"
    if m:
        return f"{m}m {s:02d}s"
    return f"{s}s"


def _format_value(value: float, unit: str) -> str:
    if unit == "MB":
        return f"{value:,.1f} MB"
    if unit == "%":
        return f"{value:,.1f}%"
    if unit == "count":
        return f"{value:,.0f}"
    if unit == "s":
        return f"{value:,.3f}s"
    return f"{value:,.3f}"


def _format_delta(value: float, unit: str) -> str:
    sign = "+" if value >= 0 else ""
    return f"{sign}{_format_value(value, unit)}"


@dataclasses.dataclass
class MetricResult:
    """One row of the report table — the start/end/change/status of a single
    measured signal, built by the `assert_*` helpers in helpers.py instead of
    them raising immediately, so a full table (not just the first failing
    metric) can be rendered even when something failed.
    """

    name: str
    start: float
    end: float
    change: float
    unit: str  # "MB" | "%" | "count" | "s" | ""
    status: str  # "ok" | "fail"
    detail: str
    threshold: str | None = None
    # Cycle number of the first bad sample, when the underlying check tracks
    # one (assert_always_zero does; a start/end trend check doesn't have a
    # single meaningful "first" cycle — see assert_no_sustained_growth).
    # Left None rather than guessed, per the "don't claim a root cause the
    # data doesn't support" rule.
    first_bad_cycle: int | None = None
    # The run's middle-third mean — the same number assert_no_sustained_growth
    # (trends.py) already computes and decides pass/fail from (last third vs.
    # middle third, not vs. start), but previously only lived in that
    # function's printed verdict, not the report. None for the exact
    # always-zero/never-grows checks, which have no middle-third concept.
    # Exists so a reader of the table doesn't have to mentally reconstruct
    # "the middle-to-end move" from `start`/`end` alone: `start` here is
    # already post-warmup and often *itself* well below `end` by design (see
    # `mid_change`/`row_markdown` below) — showing `mid` directly is what
    # makes "flat from the midpoint on, so not a leak" legible in the table
    # instead of requiring the surrounding prose.
    mid: float | None = None

    @property
    def emoji(self) -> str:
        return "🟢" if self.status == "ok" else "🔴"

    @property
    def status_label(self) -> str:
        return "Stable" if self.status == "ok" else "FAILED"

    @property
    def mid_change(self) -> float | None:
        """Change from the midpoint to the end — the actual quantity a trend
        check's pass/fail is based on (see assert_no_sustained_growth), as
        opposed to `change` (start-to-end), which can look like meaningful
        growth even on a healthy run: a one-time warm-up ramp (a buffer/pool
        reaching its steady-state size) moves `start`→`end` but not
        `mid`→`end`. None when `mid` itself is None.
        """
        return None if self.mid is None else self.end - self.mid

    def row_markdown(self) -> str:
        mid_str = _format_value(self.mid, self.unit) if self.mid is not None else "—"
        mid_change_str = (
            _format_delta(self.mid_change, self.unit) if self.mid_change is not None else "—"
        )
        return (
            f"| {self.name} | {_format_value(self.start, self.unit)} | {mid_str} | "
            f"{_format_value(self.end, self.unit)} | {mid_change_str} | "
            f"{self.emoji} {self.status_label} |"
        )

    def row_text(self) -> str:
        mid_str = _format_value(self.mid, self.unit) if self.mid is not None else "—"
        end_str = _format_value(self.end, self.unit)
        mid_change_str = (
            _format_delta(self.mid_change, self.unit) if self.mid_change is not None else "—"
        )
        return (
            f"  {self.name:<20} {_format_value(self.start, self.unit):>12} -> "
            f"{mid_str:>12} -> {end_str:<12} {mid_change_str:>10}  "
            f"{self.emoji} {self.status_label}"
        )


@dataclasses.dataclass
class RunResult:
    test_name: str
    sdk: str
    status: str  # "PASS" | "FAIL" | "ERROR"
    duration_s: float
    elapsed_s: float
    iterations: int
    started_at: str
    ended_at: str
    metrics: list[MetricResult]
    # A one-line, plain-language statement of what this scenario actually
    # does (e.g. "Repeated publish/unpublish on one sendonly slot — no
    # frames, no commands."). Rendered right under the title. Exists because
    # the report is what a human (or a future agent) reads standalone in a
    # GitHub Actions Job Summary or a downloaded artifact — without this, the
    # only clue to what a scenario exercises is its `test_name` slug, and
    # telling e.g. publish-churn apart from session-churn's broader mix
    # means going back to the source or ../README.md. Optional (left None
    # renders nothing) rather than derived from `test_name`, since a good
    # one-liner needs the same judgment a docstring does.
    description: str | None = None
    # None means this scenario doesn't count anything as a "transient error"
    # in the first place (nothing in its loop swallows/retries a failure) —
    # left unset rather than defaulted to 0, so the report doesn't render a
    # measurement that was never actually taken. See _conclusion_lines() and
    # the Errors row in render_markdown()/render_text().
    errors: int | None = None
    # The `reactor_sdk` version under test (`reactor_sdk.__version__`) — not
    # computed here, since this module deliberately never imports reactor_sdk
    # (see the module docstring: that's what keeps ../tests/
    # test_endurance_report.py FFI-free). Passed in by finish_run()'s caller,
    # which already imports reactor_sdk for the scenario itself.
    sdk_version: str | None = None
    commit_sha: str | None = None
    # Set only when the test raised before/without any metric failing (an
    # unhandled exception, an insufficient-samples guard, a fixture error) —
    # distinct from a metric actually crossing its threshold. See
    # _conclusion_lines(): the two are reported differently on purpose.
    run_error: str | None = None
    tracemalloc_top: list[str] | None = None
    # A handful of evenly-spaced snapshots across the run (see
    # compute_checkpoints()) — computed automatically by write_reports() from
    # `samples` when left as None, not meant to be set directly.
    checkpoints: list[dict[str, Any]] | None = None
    samples: list[dict[str, Any]] = dataclasses.field(default_factory=list)

    @property
    def emoji(self) -> str:
        return {"PASS": "🟢", "FAIL": "🔴", "ERROR": "🟠"}.get(self.status, "⚪")


def compute_checkpoints(samples: list[dict[str, Any]], *, n: int = 5) -> list[dict[str, Any]]:
    """`n` evenly-spaced snapshots across `samples` (by index, not by time —
    sampling is already roughly evenly spaced in wall-clock time since each
    cycle takes comparable work).

    Exists because the trend assertions in helpers.py (and the report's own
    Results table) only ever compare the *mean of the first third* to the
    *mean of the last third* — that's the right check for "did this end up
    somewhere worse than it started", but it collapses the actual shape of
    a metric over time into two numbers. A metric that ramped for one middle
    stretch and plateaued (a buffer/pool growing once to its steady-state
    size, then holding) reads identically in that table to one that climbed
    steadily the whole run — and those mean very different things for
    whether something is actually still leaking. This is the smallest
    addition that lets a report (or an agent reading the JSON) tell those
    apart without reaching for the full `samples` list and writing a
    bucketing script by hand.
    """
    if not samples:
        return []
    last_idx = len(samples) - 1
    checkpoints = []
    for i in range(n):
        pct = (i / (n - 1)) if n > 1 else 0.0
        s = samples[round(pct * last_idx)]
        checkpoints.append(
            {
                "pct": round(pct * 100),
                "cycle": s.get("cycle"),
                "elapsed_s": s.get("elapsed_s"),
                "rss_mb": s["rss_bytes"] / 1e6 if "rss_bytes" in s else None,
                "num_threads": s.get("num_threads"),
                "num_fds": s.get("num_fds"),
                "cpu_percent": s.get("cpu_percent"),
            }
        )
    return checkpoints


def _timeline_rows(checkpoints: list[dict[str, Any]]) -> list[tuple[str, str, str, str, str]]:
    rows = []
    for c in checkpoints:
        at = f"{c['pct']}%"
        if c.get("elapsed_s") is not None:
            at += f" ({format_duration(c['elapsed_s'])})"
        rss = f"{c['rss_mb']:,.1f} MB" if c.get("rss_mb") is not None else "—"
        threads = str(c["num_threads"]) if c.get("num_threads") is not None else "—"
        fds = str(c["num_fds"]) if c.get("num_fds") is not None else "—"
        cpu = f"{c['cpu_percent']:.1f}%" if c.get("cpu_percent") is not None else "—"
        rows.append((at, rss, threads, fds, cpu))
    return rows


def _conclusion_lines(result: RunResult) -> list[str]:
    if result.status == "ERROR":
        return [
            f"The test did not complete: {result.run_error or 'an unexpected error occurred'}.",
            "This looks like a test error or an infrastructure/setup problem, not a "
            "detected leak — no metric ran to completion to judge.",
            "See the downloadable JSON and logs for details.",
        ]

    tested = ", ".join(m.name for m in result.metrics) if result.metrics else "no metrics"

    if result.status == "PASS":
        lines = [
            f"The {result.sdk} SDK completed {format_duration(result.elapsed_s)} of endurance "
            f"testing across {result.iterations:,} iterations without detecting resource "
            f"leaks or sustained growth in the metrics tested ({tested})."
        ]
        for m in result.metrics:
            if m.unit == "count" and m.change == 0:
                lines.append(f"{m.name} stayed constant at {_format_value(m.end, m.unit)}.")
            else:
                lines.append(
                    f"{m.name} changed by {_format_delta(m.change, m.unit)} "
                    f"({_format_value(m.start, m.unit)} → {_format_value(m.end, m.unit)}) "
                    "and remained stable."
                )
        if result.errors is not None:
            lines.append(
                "No test errors occurred."
                if not result.errors
                else f"{result.errors} transient error(s) occurred but did not affect the result."
            )
        return lines

    # FAIL
    failed = [m for m in result.metrics if m.status == "fail"]
    lines: list[str] = []
    if failed:
        names = ", ".join(m.name for m in failed)
        lines.append(f"The endurance test detected a failure in: {names}.")
        for m in failed:
            threshold_str = (
                f", exceeding the configured threshold ({m.threshold})" if m.threshold else ""
            )
            cycle_str = (
                f" First detected at cycle {m.first_bad_cycle:,}."
                if m.first_bad_cycle is not None
                else ""
            )
            lines.append(
                f"{m.name} went from {_format_value(m.start, m.unit)} to "
                f"{_format_value(m.end, m.unit)} ({_format_delta(m.change, m.unit)})"
                f"{threshold_str}.{cycle_str}"
            )
    else:
        lines.append(
            "The endurance test failed without a specific metric crossing its threshold — "
            "likely a test error or infrastructure/setup failure rather than a detected leak."
        )
    lines.append("See the downloadable JSON and diagnostic artifacts for detailed samples.")
    return lines


def _checkpoints_for(result: RunResult) -> list[dict[str, Any]]:
    """`result.checkpoints` if already computed (write_reports() does this
    before serializing to JSON), else computed on the fly from
    `result.samples` — so render_markdown()/render_text() called directly
    (as the tests do, and as anything reading a RunResult without going
    through write_reports() would) still show the Timeline section rather
    than silently omitting it.
    """
    if result.checkpoints is not None:
        return result.checkpoints
    return compute_checkpoints(result.samples)


def render_markdown(result: RunResult) -> str:
    lines: list[str] = []
    lines.append(f"# {result.emoji} Endurance Test Report — {result.test_name}")
    lines.append("")
    if result.description:
        lines.append(result.description)
        lines.append("")
    lines.append(
        f"{result.sdk} SDK · {format_duration(result.elapsed_s)} · "
        f"{result.iterations:,} iterations · **{result.status}**"
    )
    lines.append("")
    if result.sdk_version:
        lines.append(f"- **SDK version:** `{result.sdk_version}`")
    if result.commit_sha:
        lines.append(f"- **Commit:** `{result.commit_sha[:12]}`")
    lines.append(f"- **Started:** {result.started_at}")
    lines.append(f"- **Ended:** {result.ended_at}")
    lines.append(f"- **Target duration:** {format_duration(result.duration_s)}")
    lines.append("")

    if result.metrics:
        lines.append("## Results")
        lines.append("")
        lines.append("| Metric | Start | Mid | End | Δ (Mid→End) | Status |")
        lines.append("|---|---:|---:|---:|---:|---|")
        for m in result.metrics:
            lines.append(m.row_markdown())
        if result.errors is not None:
            lines.append(
                f"| Errors | {result.errors} | — | {result.errors} | — | "
                f"{'🟢' if result.errors == 0 else '🟡'} |"
            )
        lines.append("")

    checkpoints = _checkpoints_for(result)
    if checkpoints:
        # The Results table above only compares first-third vs last-third
        # means — see compute_checkpoints()'s own docstring for why that can
        # read as "steady climb" when the real shape is e.g. flat, then one
        # ramp, then flat again. This shows the actual shape.
        lines.append("## Timeline")
        lines.append("")
        lines.append("| At | RSS | Threads | FDs | CPU% |")
        lines.append("|---|---:|---:|---:|---:|")
        for at, rss, threads, fds, cpu in _timeline_rows(checkpoints):
            lines.append(f"| {at} | {rss} | {threads} | {fds} | {cpu} |")
        lines.append("")

    lines.append("## Conclusion")
    lines.append("")
    lines.append(f"{result.emoji} {result.status}")
    lines.append("")
    for line in _conclusion_lines(result):
        lines.append(line)
    lines.append("")

    if result.tracemalloc_top:
        # Printed always when available — pass or fail — not just on
        # failure: a human reads this diagnostic regardless (see
        # ../README.md's tracemalloc section), so the heading shouldn't
        # imply something went wrong when the run passed.
        heading = "Failure diagnostics" if result.status == "FAIL" else "Memory diagnostics"
        lines.append(f"## {heading}")
        lines.append("")
        lines.append("Top memory growth (tracemalloc, ~30% mark → end):")
        lines.append("")
        lines.append("```")
        lines.extend(result.tracemalloc_top)
        lines.append("```")
        lines.append("")

    lines.append("_Detailed samples are available as a downloadable JSON workflow artifact._")
    return "\n".join(lines).rstrip() + "\n"


def render_text(result: RunResult) -> str:
    lines: list[str] = []
    lines.append(f"Endurance Test Report — {result.test_name}")
    if result.description:
        lines.append(result.description)
    lines.append(f"{result.sdk} SDK")
    lines.append(result.status)
    lines.append(f"Duration: {format_duration(result.elapsed_s)}")
    lines.append("")
    if result.sdk_version:
        lines.append(f"SDK version:    {result.sdk_version}")
    if result.commit_sha:
        lines.append(f"Commit:         {result.commit_sha[:12]}")
    lines.append(f"Started:        {result.started_at}")
    lines.append(f"Ended:          {result.ended_at}")
    lines.append(f"Target duration: {format_duration(result.duration_s)}")
    lines.append(f"Iterations:     {result.iterations:,}")
    lines.append("")

    if result.metrics:
        lines.append("Results")
        lines.append("-------")
        for m in result.metrics:
            lines.append(m.row_text())
        if result.errors is not None:
            lines.append(
                f"  {'Errors':<20} {result.errors:>12} {'':>12} {'':<12} {'':>10}  "
                + ("🟢" if result.errors == 0 else "🟡")
            )
        lines.append("")

    checkpoints = _checkpoints_for(result)
    if checkpoints:
        lines.append("Timeline")
        lines.append("--------")
        for at, rss, threads, fds, cpu in _timeline_rows(checkpoints):
            lines.append(
                f"  {at:<14} RSS {rss:>10}  threads {threads:>4}  fds {fds:>4}  cpu {cpu:>6}"
            )
        lines.append("")

    lines.append("Conclusion")
    lines.append("----------")
    lines.append(f"{result.status}")
    lines.append("")
    for line in _conclusion_lines(result):
        lines.append(line)
    lines.append("")

    if result.tracemalloc_top:
        heading = "Failure diagnostics" if result.status == "FAIL" else "Memory diagnostics"
        lines.append(heading)
        lines.append("-" * len(heading))
        lines.append("Top memory growth (tracemalloc, ~30% mark -> end):")
        lines.extend(f"  {entry}" for entry in result.tracemalloc_top)
        lines.append("")

    lines.append("Detailed samples are available as a downloadable JSON workflow artifact.")
    return "\n".join(lines).rstrip() + "\n"


def write_reports(result: RunResult, out_dir: Path = DEFAULT_OUTPUT_DIR) -> dict[str, Path]:
    """Writes `{test_name}.json` / `{test_name}-report.md` / `{test_name}-
    summary.txt` under `out_dir`, all three derived from the same
    `RunResult` — see the module docstring for why that matters.

    Fills in `result.checkpoints` from `result.samples` first, when not
    already set — callers (the tests) only need to hand over the raw
    samples they already collect; they don't need to know
    `compute_checkpoints()` exists.
    """
    if result.checkpoints is None and result.samples:
        result.checkpoints = compute_checkpoints(result.samples)
    out_dir.mkdir(parents=True, exist_ok=True)
    paths = {
        "json": out_dir / f"{result.test_name}.json",
        "markdown": out_dir / f"{result.test_name}-report.md",
        "text": out_dir / f"{result.test_name}-summary.txt",
    }
    paths["json"].write_text(json.dumps(dataclasses.asdict(result), indent=2, default=str))
    paths["markdown"].write_text(render_markdown(result))
    paths["text"].write_text(render_text(result))
    return paths


def finish_run(
    *,
    test_name: str,
    sdk: str,
    duration_s: float,
    started_at: str,
    samples: list[Any],
    metrics: list[MetricResult],
    iterations: int,
    errors: int | None = None,
    tracemalloc_top: list[str] | None = None,
    sdk_version: str | None = None,
    description: str | None = None,
    out_dir: Path = DEFAULT_OUTPUT_DIR,
) -> RunResult:
    """Builds the `RunResult` for one scenario's `finally` block and writes
    its reports — the `sys.exc_info() -> status -> RunResult -> write_reports()`
    sequence every scenario needs at the end of its run, in one place instead
    of copied verbatim into each `tests/test_*.py` (which used to drift
    against each other as fields were added — e.g. only one of the two had
    `tracemalloc_top`).

    Must be called from inside the `finally` block itself: relies on
    `sys.exc_info()` still reflecting the exception being handled (if any) —
    that's what tells an unhandled exception (`run_error`, status "ERROR")
    apart from a metric that actually crossed its threshold (status "FAIL").

    `write_reports()` is called inside a try/except here on purpose: if
    writing the report itself raises (disk full, a field that turns out not
    to be JSON-serialisable), printing and moving on keeps whatever
    exception the *test* raised — the actual failure — as what pytest
    reports, instead of a reporting-layer bug masking it.
    """
    exc_type, exc_val, _ = sys.exc_info()
    run_error = f"{exc_type.__name__}: {exc_val}" if exc_type is not None else None
    failed = [m for m in metrics if m.status == "fail"]
    status = "ERROR" if run_error else ("FAIL" if failed else "PASS")
    result = RunResult(
        test_name=test_name,
        sdk=sdk,
        status=status,
        duration_s=duration_s,
        elapsed_s=samples[-1].elapsed_s if samples else 0.0,
        iterations=iterations,
        started_at=started_at,
        ended_at=now_iso(),
        metrics=metrics,
        errors=errors,
        sdk_version=sdk_version,
        description=description,
        commit_sha=git_commit_sha(),
        run_error=run_error,
        tracemalloc_top=tracemalloc_top,
        samples=[dataclasses.asdict(s) for s in samples],
    )
    try:
        write_reports(result, out_dir)
    except Exception as e:
        print(f"[finish_run] write_reports() failed, continuing: {e!r}")
    return result


class LiveReporter:
    """Prints a periodic, human-scale status block during a long endurance
    run so a GitHub Actions log stays legible instead of accumulating one row
    per cycle for hours (the default). Under `ENDURANCE_VERBOSE=1` this stays
    silent — helpers.py's `ResourceSampler` prints one detailed row per cycle
    itself in that mode instead, which is the debugging path.
    """

    def __init__(
        self, test_name: str, duration_s: float, *, interval_s: float | None = None
    ) -> None:
        self.test_name = test_name
        self.duration_s = duration_s
        self.interval_s = LIVE_INTERVAL_SECONDS if interval_s is None else interval_s
        self._last_print = 0.0
        self._printed_once = False

    def update(
        self,
        samples: list[Any],
        *,
        errors: int | None = None,
        extra: dict[str, str] | None = None,
        force: bool = False,
    ) -> None:
        if ENDURANCE_VERBOSE or not samples:
            return
        now = time.monotonic()
        if not force and self._printed_once and (now - self._last_print) < self.interval_s:
            return
        self._last_print = now
        self._printed_once = True
        print(self._format(samples, errors=errors, extra=extra))

    def _format(
        self, samples: list[Any], *, errors: int | None, extra: dict[str, str] | None
    ) -> str:
        first, last = samples[0], samples[-1]
        elapsed = last.elapsed_s
        pct = min(100, int(elapsed / self.duration_s * 100)) if self.duration_s else 0
        rss_start_mb = first.rss_bytes / 1e6
        rss_now_mb = last.rss_bytes / 1e6
        avg_cpu = sum(s.cpu_percent for s in samples) / len(samples)
        bar = "─" * 46
        lines = [
            bar,
            f"\U0001f680 Endurance Test — {self.test_name}",
            bar,
            "",
            f"Duration       {format_duration(self.duration_s)}",
            f"Elapsed        {format_duration(elapsed)}",
            f"Progress       {pct}%",
            "",
            f"Iterations     {last.cycle + 1:,}",
        ]
        if errors is not None:
            lines.append(f"Errors         {errors}")
        lines += [
            "",
            "Resources",
            f"  RSS           {rss_start_mb:,.0f} MB → {rss_now_mb:,.0f} MB   "
            f"({_format_delta(rss_now_mb - rss_start_mb, 'MB')})",
            f"  CPU           avg {avg_cpu:.1f}%",
            f"  Threads       {first.num_threads} → {last.num_threads}",
            f"  File desc.    {first.num_fds} → {last.num_fds}",
        ]
        if extra:
            for key, value in extra.items():
                lines.append(f"  {key:<13} {value}")
        status = "\U0001f7e1 Errors detected" if errors else "\U0001f7e2 Healthy"
        lines.append("")
        lines.append(f"Status         {status}")
        lines.append(bar)
        return "\n".join(lines)
