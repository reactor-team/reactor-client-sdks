// One shared in-memory result for the endurance suite's reporting layer:
// mirrors sdks/python/endurance-tests/report.py — MetricResult/RunResult, the
// JSON/Markdown/text renderers, and the compact live status block — as
// structs rather than dataclasses.
//
// Deliberately has no dependency on this SDK's FFI or native library at all
// (not even a forward-declared reactor::Reactor): keeping this module free
// of that import chain is what lets its own unit tests
// (../tests/report_test.cpp) build and run with no live service and no built
// libreactor_ffi — the same reason report.py doesn't import helpers.py, see
// its own module docstring.
//
// Unlike Python's module split, `Sample` lives here rather than in
// trends.hpp: report.py's LiveReporter/compute_checkpoints duck-type their
// way around ever importing trends.Sample, which C++ has no equivalent way
// to do. trends.hpp includes this header for both `Sample` and
// `MetricResult`, so the dependency runs one direction only (trends on
// report), avoiding a cycle — the same "which module holds the shared type"
// question, just resolved the other way because C++ cannot duck-type it.
#pragma once

#include <cstdint>
#include <exception>
#include <map>
#include <optional>
#include <string>
#include <vector>

namespace endurance {

/// ENDURANCE_VERBOSE=1 → the old detailed per-cycle table, printed live,
/// instead of the compact status block `LiveReporter` prints by default. See
/// `ResourceSampler::print_live_row` (helpers.hpp) and `LiveReporter::update`
/// below.
bool endurance_verbose();

/// How often `LiveReporter` reprints its compact block, in seconds.
/// `ENDURANCE_LIVE_INTERVAL_SECONDS` overrides it; 30s by default — frequent
/// enough that opening a GitHub Actions log mid-run always shows something
/// recent, sparse enough that a multi-hour run doesn't scroll a huge log.
double live_interval_seconds();

/// Where `write_reports()` writes `{test_name}.json`/`-report.md`/`-summary
/// .txt`, unless a caller overrides it.
///
/// A path relative to the repo root, not just `"endurance-results"`: unlike
/// the Python suite (whose every invocation — the CI workflow, `mise run
/// test:python:endurance-tests`, this suite's own README — `cd`s into
/// `sdks/python` first), nothing that runs this binary ever changes
/// directory first (the mise task, the CI workflow's `cpp` job, and this
/// suite's own README all invoke the built binary by its repo-root-relative
/// path, from the repo root) — see
/// ../../../.github/workflows/endurance-tests.yml's `cpp` job and this
/// directory's own README.md for both call sites. Baking the `sdks/cpp/`
/// prefix in here is what makes the CI job's `working-directory: sdks/cpp`
/// job-summary step and its `path: sdks/cpp/endurance-results/`
/// upload-artifact step actually find what this binary wrote, without
/// either side having to pass an explicit `out_dir` override.
extern const std::string kDefaultOutputDir;

/// Current UTC time, ISO-8601.
std::string now_iso();

/// Best-effort commit SHA for the report header: `GITHUB_SHA` (set by every
/// GitHub Actions run) first, falling back to `git rev-parse HEAD` for a
/// local run. Nothing on failure — a missing commit SHA is fine to just
/// omit, not worth failing a report over.
std::optional<std::string> git_commit_sha();

std::string format_duration(double seconds);

/// One process-wide resource reading, taken once per cycle of an endurance
/// loop.
///
/// No SDK-level handle-bookkeeping fields here (Python's
/// `live_clients`/`orphaned_callbacks`) — see ../README.md's "known,
/// deliberate scope gap" section for why this binding has no equivalent: a
/// `Reactor`/`Track`/`Subscription` is destroyed exactly when its owner's
/// scope ends, deterministically, by construction, so there is no analogous
/// "did the destructor actually run yet" question for this suite to answer.
struct Sample {
  int cycle = 0;
  double elapsed_s = 0.0;
  std::uint64_t rss_bytes = 0;
  double cpu_s = 0.0;  // cumulative user+sys CPU time since process start
  // % of one CPU core busy since the *previous* sample — see
  // ResourceSampler::sample()'s own comment (helpers.cpp) for how this is
  // derived. 0.0 on the first sample, which has no previous one to diff
  // against.
  double cpu_percent = 0.0;
  long num_threads = 0;
  long num_fds = 0;
};

/// One row of the report's Results table — the start/mid/end/change/status
/// of a single measured signal, built by the `assert_*` functions in
/// trends.hpp instead of them throwing immediately, so a full table (not
/// just the first failing metric) renders even when something failed.
struct MetricResult {
  std::string name;
  double start = 0.0;
  double end = 0.0;
  double change = 0.0;
  std::string unit;    // "MB" | "%" | "count" | "s" | ""
  std::string status;  // "ok" | "fail"
  std::string detail;
  std::optional<std::string> threshold;
  // Cycle number of the first bad sample, when the underlying check tracks
  // one (assert_always_zero does; a start/mid/end trend check doesn't have a
  // single meaningful "first" cycle).
  std::optional<int> first_bad_cycle;
  // The run's middle-third mean — the same number assert_no_sustained_growth
  // (trends.cpp) already computes and decides pass/fail from (last third vs.
  // middle third, not vs. start). Exists so a reader of the table doesn't
  // have to mentally reconstruct "the middle-to-end move" from start/end
  // alone. Unset for the exact always-zero/never-grows checks, which have no
  // middle-third concept.
  std::optional<double> mid;

  std::string emoji() const { return status == "ok" ? "\U0001F7E2" : "\U0001F534"; }
  std::string status_label() const { return status == "ok" ? "Stable" : "FAILED"; }

  /// Change from the midpoint to the end — the actual quantity a trend
  /// check's pass/fail is based on, as opposed to `change` (start-to-end),
  /// which can look like meaningful growth even on a healthy run: a one-time
  /// warm-up ramp moves start→end but not mid→end. Empty when `mid` itself
  /// is empty.
  std::optional<double> mid_change() const {
    if (!mid.has_value()) {
      return std::nullopt;
    }
    return end - *mid;
  }

  std::string row_markdown() const;
  std::string row_text() const;
};

/// One evenly-spaced snapshot across a run — see `compute_checkpoints()`.
struct Checkpoint {
  int pct = 0;
  std::optional<int> cycle;
  std::optional<double> elapsed_s;
  std::optional<double> rss_mb;
  std::optional<long> num_threads;
  std::optional<long> num_fds;
  std::optional<double> cpu_percent;
};

/// `n` evenly-spaced snapshots across `samples` (by index, not by time —
/// sampling is already roughly evenly spaced in wall-clock time since each
/// cycle takes comparable work).
///
/// Exists because the trend assertions (trends.cpp) and the report's own
/// Results table only ever compare the mean of the first third to the mean
/// of the last third — the right check for "did this end up somewhere worse
/// than it started", but it collapses the actual shape of a metric over
/// time into two numbers. A metric that ramped once and plateaued reads
/// identically in that table to one that climbed steadily the whole run —
/// this is the smallest addition that tells those apart.
std::vector<Checkpoint> compute_checkpoints(const std::vector<Sample>& samples, int n = 5);

/// One scenario's whole run, in one place — rendered to JSON, Markdown, and
/// plain text by the functions below, so all three come from the same
/// numbers instead of hand-written duplicates that can drift apart.
struct RunResult {
  std::string test_name;
  std::string sdk;
  std::string status;  // "PASS" | "FAIL" | "ERROR"
  double duration_s = 0.0;
  double elapsed_s = 0.0;
  int iterations = 0;
  std::string started_at;
  std::string ended_at;
  std::vector<MetricResult> metrics;
  // A one-line, plain-language statement of what this scenario's loop
  // actually does. Rendered right under the title — the report is read
  // standalone (a GitHub Actions Job Summary, a downloaded artifact), and
  // `test_name` alone ("publish-churn") doesn't say "no frames, no
  // commands" or distinguish it from session-churn's broader mix.
  std::optional<std::string> description;
  // Unset (not defaulted to 0) for a scenario that never actually counts
  // anything — left unmeasured rather than rendering a hardcoded-looking
  // "Errors 0".
  std::optional<int> errors;
  // reactor::version() — not computed here (this module never depends on
  // reactor::sdk, see the file's own top comment), passed in by whatever
  // built the RunResult.
  std::optional<std::string> sdk_version;
  std::optional<std::string> commit_sha;
  // Set only when the scenario's loop raised before/without any metric
  // failing — distinct from a metric actually crossing its threshold.
  std::optional<std::string> run_error;
  // Computed automatically by write_reports() from `samples` when left
  // empty, not meant to be set directly.
  std::optional<std::vector<Checkpoint>> checkpoints;
  std::vector<Sample> samples;

  std::string emoji() const;
};

std::string render_markdown(const RunResult& result);
std::string render_text(const RunResult& result);

struct ReportPaths {
  std::string json;
  std::string markdown;
  std::string text;
};

/// Writes `{test_name}.json` / `{test_name}-report.md` / `{test_name}-
/// summary.txt` under `out_dir`, all three derived from `result`.
///
/// Fills in `result.checkpoints` from `result.samples` first, when not
/// already set — callers only need to hand over the raw samples they
/// already collect.
ReportPaths write_reports(RunResult& result, const std::string& out_dir = kDefaultOutputDir);

/// Everything `finish_run()` needs, gathered so the function itself takes
/// one argument instead of a dozen.
///
/// `run_error`: unlike report.py's `finish_run()`, which infers "an
/// exception is in flight" from `sys.exc_info()`, this takes that fact
/// explicitly — nothing in C++ can safely observe "is an exception
/// currently propagating" from outside the `catch` that is handling it, the
/// way Python's `finally` block can. Callers already have exactly this: the
/// same `std::exception_ptr pending` every scenario's own catch-all already
/// stores (see test_lifecycle_churn.cpp for the shape).
struct FinishRunArgs {
  std::string test_name;
  std::string sdk;
  double duration_s = 0.0;
  std::string started_at;
  std::vector<Sample> samples;
  std::vector<MetricResult> metrics;
  int iterations = 0;
  std::optional<int> errors;
  std::optional<std::string> sdk_version;
  std::optional<std::string> description;
  std::exception_ptr run_error;
  std::string out_dir = kDefaultOutputDir;
};

/// Builds the `RunResult` for one scenario's end-of-run reporting and writes
/// it: status is `"ERROR"` when `args.run_error` is set, else `"FAIL"` when
/// any metric failed, else `"PASS"`.
///
/// Writing the reports is wrapped in a try/catch internally: if that itself
/// throws (disk full, ...), printing and moving on keeps whatever exception
/// the *test* raised as the one that actually propagates, instead of a
/// reporting-layer bug masking it.
RunResult finish_run(FinishRunArgs args);

/// Prints a periodic, human-scale status block during a long endurance run
/// so a GitHub Actions log stays legible instead of accumulating one row per
/// cycle for hours (the default). Under `ENDURANCE_VERBOSE=1` this stays
/// silent — `ResourceSampler::print_live_row` (helpers.hpp) prints one
/// detailed row per cycle itself in that mode instead.
class LiveReporter {
 public:
  LiveReporter(std::string test_name, double duration_s,
               std::optional<double> interval_s = std::nullopt);

  void update(const std::vector<Sample>& samples, std::optional<int> errors = std::nullopt,
              const std::map<std::string, std::string>& extra = {}, bool force = false);

 private:
  std::string format(const std::vector<Sample>& samples, std::optional<int> errors,
                     const std::map<std::string, std::string>& extra) const;

  std::string test_name_;
  double duration_s_;
  double interval_s_;
  double last_print_s_ = 0.0;
  bool printed_once_ = false;
};

/// Everything `finish_and_check()` needs, besides the samples/live reporter
/// it's handed separately (see below).
struct FinishAndCheckArgs {
  std::string test_name;
  std::string sdk;
  std::string description;
  std::optional<std::string> sdk_version;
  double duration_s = 0.0;
  std::string started_at;
  std::vector<MetricResult> metrics;
  int iterations = 0;
  std::optional<int> errors;
  std::map<std::string, std::string> extra;
  // See FinishRunArgs::run_error's own comment for why this is explicit here
  // rather than inferred.
  std::exception_ptr run_error;
  std::string out_dir = kDefaultOutputDir;
};

/// The end-of-loop boilerplate every scenario needs: forces one last
/// `LiveReporter` update, calls `finish_run()`, then throws
/// `std::runtime_error` if the run's status came back `"FAIL"`.
///
/// An `"ERROR"` status (`args.run_error` set) throws nothing new here — the
/// caller's own pending exception (the same one passed in as `run_error`) is
/// what should propagate, exactly once, after this returns. See
/// test_lifecycle_churn.cpp for the calling shape:
///
///     std::exception_ptr pending;
///     try { ... } catch (...) { pending = std::current_exception(); }
///     endurance::finish_and_check(sampler.samples(), live, {..., pending});
///     if (pending) std::rethrow_exception(pending);
void finish_and_check(const std::vector<Sample>& samples, LiveReporter& live,
                      FinishAndCheckArgs args);

}  // namespace endurance
