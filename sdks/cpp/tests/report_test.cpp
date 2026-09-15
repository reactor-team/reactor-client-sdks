// Tests for the endurance-tests reporting layer (../endurance-tests/
// report.cpp). Pure formatting/aggregation logic, no FFI/reactor::sdk
// involved — mirrors sdks/python/tests/test_endurance_report.py.
#include "report.hpp"

#include <filesystem>
#include <fstream>
#include <nlohmann/json.hpp>
#include <random>
#include <sstream>

#include <catch2/catch_approx.hpp>
#include <catch2/catch_test_macros.hpp>

using endurance::Checkpoint;
using endurance::compute_checkpoints;
using endurance::finish_run;
using endurance::FinishRunArgs;
using endurance::MetricResult;
using endurance::render_markdown;
using endurance::render_text;
using endurance::RunResult;
using endurance::Sample;
using endurance::write_reports;

namespace {

MetricResult ok_metric(std::string name, double start, double end, std::string unit = "MB") {
  MetricResult m;
  m.name = std::move(name);
  m.start = start;
  m.end = end;
  m.change = end - start;
  m.unit = std::move(unit);
  m.status = "ok";
  m.detail = m.name + " stayed within bounds";
  m.threshold = "> 15% growth (min 5)";
  return m;
}

MetricResult fail_metric(std::string name, double start, double end, std::string unit = "MB") {
  MetricResult m = ok_metric(name, start, end, std::move(unit));
  m.status = "fail";
  m.detail = m.name + " grew past the threshold";
  m.first_bad_cycle = 42;
  return m;
}

RunResult passing_result() {
  RunResult r;
  r.test_name = "session-churn";
  r.sdk = "C++";
  r.status = "PASS";
  r.duration_s = 7200;
  r.elapsed_s = 7205;
  r.iterations = 2184;
  r.started_at = "2026-09-13T10:00:00+00:00";
  r.ended_at = "2026-09-13T12:00:05+00:00";
  r.metrics = {ok_metric("rss", 284.0, 291.0), ok_metric("num_threads", 14, 14, "count"),
               ok_metric("num_fds", 23, 23, "count")};
  r.errors = 0;
  r.commit_sha = std::string("abc123def456abc123def456");
  return r;
}

RunResult failing_result() {
  RunResult r = passing_result();
  r.status = "FAIL";
  r.metrics = {fail_metric("rss", 284.0, 612.0), ok_metric("num_threads", 14, 14, "count")};
  return r;
}

RunResult error_result() {
  RunResult r;
  r.test_name = "lifecycle-churn";
  r.sdk = "C++";
  r.status = "ERROR";
  r.duration_s = 300;
  r.elapsed_s = 12.0;
  r.iterations = 1;
  r.started_at = "2026-09-13T10:00:00+00:00";
  r.ended_at = "2026-09-13T10:00:12+00:00";
  r.run_error = std::string("connection refused: quota exceeded");
  return r;
}

std::vector<Sample> flat_ramp_flat_samples(int n = 100) {
  std::vector<Sample> samples;
  for (int i = 0; i < n; ++i) {
    const double pct = static_cast<double>(i) / static_cast<double>(n - 1);
    double rss = 0.0;
    if (pct < 0.3) {
      rss = 110.0;
    } else if (pct < 0.6) {
      rss = 110.0 + (pct - 0.3) / 0.3 * 18.0;
    } else {
      rss = 128.0;
    }
    Sample s;
    s.cycle = i;
    s.elapsed_s = pct * 300;
    s.rss_bytes = static_cast<std::uint64_t>(rss * 1e6);
    s.num_threads = 23;
    s.num_fds = 22;
    s.cpu_percent = 3.6;
    samples.push_back(s);
  }
  return samples;
}

std::string slurp(const std::string& path) {
  std::ifstream f(path);
  std::ostringstream oss;
  oss << f.rdbuf();
  return oss.str();
}

}  // namespace

TEST_CASE("passing report: status is PASS") { CHECK(passing_result().status == "PASS"); }

TEST_CASE("passing report: conclusion mentions tested metrics") {
  const auto md = render_markdown(passing_result());
  CHECK(md.find("rss") != std::string::npos);
  CHECK(md.find("num_threads") != std::string::npos);
  CHECK(md.find("num_fds") != std::string::npos);
  CHECK(md.find("PASS") != std::string::npos);
}

TEST_CASE("passing report: metric statuses render as Stable") {
  const auto md = render_markdown(passing_result());
  CHECK(md.find("Stable") != std::string::npos);
  CHECK(md.find("FAILED") == std::string::npos);
}

TEST_CASE("passing report: duration and iteration count included") {
  const auto md = render_markdown(passing_result());
  CHECK(md.find("2184 iterations") != std::string::npos);
  CHECK(md.find("2h 00m") != std::string::npos);
}

TEST_CASE("passing report: sdk name says SDK") {
  // Regression: an earlier draft could render "The C++ completed..." (missing
  // "SDK") — caught by actually reading a generated report, not by a test
  // that only checks the header line.
  const auto md = render_markdown(passing_result());
  const auto pos = md.find("## Conclusion");
  REQUIRE(pos != std::string::npos);
  const auto conclusion = md.substr(pos);
  CHECK(conclusion.find("The C++ SDK completed") != std::string::npos);
}

TEST_CASE("passing report: diagnostics heading not present without tracemalloc-equivalent data") {
  // This binding has no tracemalloc equivalent (see ../../endurance-tests/
  // README.md) — the diagnostics section simply never renders, distinct
  // from Python's own pass/fail-labeled heading toggle.
  const auto md = render_markdown(passing_result());
  CHECK(md.find("Failure diagnostics") == std::string::npos);
  CHECK(md.find("Memory diagnostics") == std::string::npos);
}

TEST_CASE("failing report: status is FAIL") { CHECK(failing_result().status == "FAIL"); }

TEST_CASE("failing report: actual/threshold/delta included") {
  const auto md = render_markdown(failing_result());
  CHECK(md.find("284.0 MB") != std::string::npos);
  CHECK(md.find("612.0 MB") != std::string::npos);
  CHECK(md.find("+328.0 MB") != std::string::npos);
  CHECK(md.find("15% growth") != std::string::npos);
}

TEST_CASE("failing report: failed metric identified as FAILED") {
  CHECK(render_markdown(failing_result()).find("FAILED") != std::string::npos);
}

TEST_CASE("failing report: conclusion does not claim pass") {
  const auto md = render_markdown(failing_result());
  const auto pos = md.find("## Conclusion");
  REQUIRE(pos != std::string::npos);
  const auto conclusion = md.substr(pos);
  CHECK(conclusion.find("PASS") == std::string::npos);
  CHECK(conclusion.find("\U0001F534") != std::string::npos);
}

TEST_CASE("failing report: conclusion names the failed metric") {
  const auto md = render_markdown(failing_result());
  const auto pos = md.find("## Conclusion");
  REQUIRE(pos != std::string::npos);
  CHECK(md.substr(pos).find("rss") != std::string::npos);
}

TEST_CASE("failing report: first_bad_cycle surfaced when available") {
  CHECK(render_markdown(failing_result()).find("cycle 42") != std::string::npos);
}

TEST_CASE("error report: status is ERROR, not FAIL") { CHECK(error_result().status == "ERROR"); }

TEST_CASE("error report: conclusion mentions the run error") {
  const auto md = render_markdown(error_result());
  CHECK(md.find("connection refused") != std::string::npos);
}

TEST_CASE("error report: conclusion does not claim pass or metric failure") {
  const auto md = render_markdown(error_result());
  const auto pos = md.find("## Conclusion");
  REQUIRE(pos != std::string::npos);
  CHECK(md.substr(pos).find("PASS") == std::string::npos);
}

TEST_CASE("error report: no metrics table when there are no metrics") {
  CHECK(render_markdown(error_result()).find("| Metric |") == std::string::npos);
}

TEST_CASE("missing optional diagnostics: no commit sha does not crash") {
  RunResult r = passing_result();
  r.commit_sha = std::nullopt;
  const auto md = render_markdown(r);
  CHECK(md.find("Commit") == std::string::npos);
}

TEST_CASE("missing optional diagnostics: empty metrics list does not crash") {
  RunResult r = passing_result();
  r.metrics.clear();
  CHECK_NOTHROW(render_markdown(r));
  CHECK_NOTHROW(render_text(r));
}

TEST_CASE("missing optional diagnostics: no samples means no timeline") {
  RunResult r = passing_result();
  REQUIRE(r.samples.empty());
  const auto md = render_markdown(r);
  const auto text = render_text(r);
  CHECK(md.find("## Timeline") == std::string::npos);
  CHECK(text.find("Timeline\n--------") == std::string::npos);
}

TEST_CASE("compute_checkpoints returns the requested count") {
  const auto checkpoints = compute_checkpoints(flat_ramp_flat_samples(), 5);
  REQUIRE(checkpoints.size() == 5);
  CHECK(checkpoints.front().pct == 0);
  CHECK(checkpoints.back().pct == 100);
}

TEST_CASE("compute_checkpoints on empty samples returns empty") {
  CHECK(compute_checkpoints({}).empty());
}

TEST_CASE("checkpoints reveal plateau then ramp then plateau") {
  // The whole point: a shape a first-third/last-third average alone would
  // flatten into "steady climb" is visible here as flat, then a jump, then
  // flat again.
  const auto checkpoints = compute_checkpoints(flat_ramp_flat_samples(), 5);
  auto rss_at = [&](int pct) -> double {
    for (const auto& c : checkpoints) {
      if (c.pct == pct) {
        return *c.rss_mb;
      }
    }
    FAIL("no checkpoint at pct " << pct);
    return 0.0;
  };
  CHECK(rss_at(0) == Catch::Approx(110.0).margin(0.5));
  CHECK(rss_at(25) == Catch::Approx(110.0).margin(0.5));
  CHECK(rss_at(50) < rss_at(75));
  CHECK(rss_at(75) == Catch::Approx(128.0).margin(0.5));
  CHECK(rss_at(100) == Catch::Approx(128.0).margin(0.5));
}

TEST_CASE("Timeline section appears in markdown and text") {
  RunResult r = passing_result();
  r.samples = flat_ramp_flat_samples();
  const auto md = render_markdown(r);
  const auto text = render_text(r);
  CHECK(md.find("## Timeline") != std::string::npos);
  CHECK(text.find("Timeline\n--------") != std::string::npos);
  CHECK(md.find("110.0 MB") != std::string::npos);
  CHECK(md.find("128.0 MB") != std::string::npos);
}

TEST_CASE("write_reports computes checkpoints from samples") {
  RunResult r = passing_result();
  r.samples = flat_ramp_flat_samples();
  REQUIRE_FALSE(r.checkpoints.has_value());
  const auto tmp = std::filesystem::temp_directory_path() /
                   ("endurance-report-test-" + std::to_string(std::random_device{}()));
  write_reports(r, tmp.string());
  REQUIRE(r.checkpoints.has_value());
  CHECK(r.checkpoints.value().size() == 5);  // NOLINT(bugprone-unchecked-optional-access)
  std::filesystem::remove_all(tmp);
}

TEST_CASE("written JSON contains checkpoints") {
  RunResult r = passing_result();
  r.samples = flat_ramp_flat_samples();
  const auto tmp = std::filesystem::temp_directory_path() /
                   ("endurance-report-test-" + std::to_string(std::random_device{}()));
  const auto paths = write_reports(r, tmp.string());
  const auto data = nlohmann::json::parse(slurp(paths.json));
  REQUIRE(data["checkpoints"].size() == 5);
  CHECK(data["checkpoints"][0]["pct"] == 0);
  std::filesystem::remove_all(tmp);
}

TEST_CASE("written JSON contains metadata, samples, metrics, and status") {
  RunResult r = passing_result();
  r.samples.push_back(Sample{0, 0.0, 1000, 0.0, 0.0, 0, 0});
  r.samples.push_back(Sample{1, 1.0, 1010, 0.0, 0.0, 0, 0});
  const auto tmp = std::filesystem::temp_directory_path() /
                   ("endurance-report-test-" + std::to_string(std::random_device{}()));
  const auto paths = write_reports(r, tmp.string());
  const auto data = nlohmann::json::parse(slurp(paths.json));
  CHECK(data["test_name"] == "session-churn");
  CHECK(data["sdk"] == "C++");
  CHECK(data["status"] == "PASS");
  CHECK(data["iterations"] == 2184);
  CHECK(data["samples"].size() == 2);
  CHECK(data["metrics"].size() == 3);
  CHECK(data["metrics"][0]["name"] == "rss");
  std::filesystem::remove_all(tmp);
}

TEST_CASE("write_reports creates all three files") {
  const auto tmp = std::filesystem::temp_directory_path() /
                   ("endurance-report-test-" + std::to_string(std::random_device{}()));
  RunResult r = passing_result();
  const auto paths = write_reports(r, tmp.string());
  CHECK(std::filesystem::exists(paths.json));
  CHECK(std::filesystem::exists(paths.markdown));
  CHECK(std::filesystem::exists(paths.text));
  CHECK(std::filesystem::path(paths.json).filename() == "session-churn.json");
  CHECK(std::filesystem::path(paths.markdown).filename() == "session-churn-report.md");
  CHECK(std::filesystem::path(paths.text).filename() == "session-churn-summary.txt");
  std::filesystem::remove_all(tmp);
}

TEST_CASE("markdown and text report the same status") {
  const auto r = failing_result();
  CHECK(render_markdown(r).find("FAIL") != std::string::npos);
  CHECK(render_text(r).find("FAIL") != std::string::npos);
}

TEST_CASE("markdown and text mention the same metric values") {
  const auto r = failing_result();
  const auto md = render_markdown(r);
  const auto text = render_text(r);
  for (const auto& token : {"284.0 MB", "612.0 MB"}) {
    CHECK(md.find(token) != std::string::npos);
    CHECK(text.find(token) != std::string::npos);
  }
}

TEST_CASE("errors field: none omits the row in markdown and text") {
  RunResult r = passing_result();
  r.errors = std::nullopt;
  const auto md = render_markdown(r);
  const auto text = render_text(r);
  CHECK(md.find("Errors") == std::string::npos);
  CHECK(text.find("Errors") == std::string::npos);
}

TEST_CASE("errors field: none omits the conclusion sentence") {
  RunResult r = passing_result();
  r.errors = std::nullopt;
  const auto md = render_markdown(r);
  const auto pos = md.find("## Conclusion");
  REQUIRE(pos != std::string::npos);
  const auto conclusion = md.substr(pos);
  CHECK(conclusion.find("test errors occurred") == std::string::npos);
  CHECK(conclusion.find("transient error") == std::string::npos);
}

TEST_CASE("errors field: zero is still shown as a real measurement") {
  RunResult r = passing_result();
  r.errors = 0;
  const auto md = render_markdown(r);
  CHECK(md.find("| Errors | 0 | — | 0 |") != std::string::npos);
  const auto pos = md.find("## Conclusion");
  REQUIRE(pos != std::string::npos);
  CHECK(md.substr(pos).find("No test errors occurred.") != std::string::npos);
}

TEST_CASE("errors field: nonzero is reported") {
  RunResult r = passing_result();
  r.errors = 3;
  const auto md = render_markdown(r);
  const auto pos = md.find("## Conclusion");
  REQUIRE(pos != std::string::npos);
  CHECK(md.substr(pos).find("3 transient error(s) occurred") != std::string::npos);
}

TEST_CASE("finish_run: PASS when no metric failed and nothing thrown") {
  const auto tmp = std::filesystem::temp_directory_path() /
                   ("endurance-finish-run-test-" + std::to_string(std::random_device{}()));
  FinishRunArgs args;
  args.test_name = "lifecycle-churn";
  args.sdk = "C++";
  args.duration_s = 300;
  args.started_at = "2026-09-13T10:00:00+00:00";
  args.metrics = {ok_metric("rss", 1.0, 1.0)};
  args.iterations = 5;
  args.out_dir = tmp.string();
  const auto result = finish_run(args);
  CHECK(result.status == "PASS");
  CHECK_FALSE(result.run_error.has_value());
  std::filesystem::remove_all(tmp);
}

TEST_CASE("finish_run: FAIL when a metric failed and nothing thrown") {
  const auto tmp = std::filesystem::temp_directory_path() /
                   ("endurance-finish-run-test-" + std::to_string(std::random_device{}()));
  FinishRunArgs args;
  args.test_name = "lifecycle-churn";
  args.sdk = "C++";
  args.duration_s = 300;
  args.started_at = "2026-09-13T10:00:00+00:00";
  args.metrics = {fail_metric("rss", 1.0, 2.0)};
  args.iterations = 5;
  args.out_dir = tmp.string();
  const auto result = finish_run(args);
  CHECK(result.status == "FAIL");
  CHECK_FALSE(result.run_error.has_value());
  std::filesystem::remove_all(tmp);
}

TEST_CASE("finish_run: ERROR when a pending exception is passed, regardless of metrics") {
  const auto tmp = std::filesystem::temp_directory_path() /
                   ("endurance-finish-run-test-" + std::to_string(std::random_device{}()));
  std::exception_ptr pending;
  try {
    throw std::runtime_error("boom");
  } catch (...) {
    pending = std::current_exception();
  }
  FinishRunArgs args;
  args.test_name = "lifecycle-churn";
  args.sdk = "C++";
  args.duration_s = 300;
  args.started_at = "2026-09-13T10:00:00+00:00";
  args.iterations = 1;
  args.out_dir = tmp.string();
  args.run_error = pending;
  const auto result = finish_run(args);
  CHECK(result.status == "ERROR");
  REQUIRE(result.run_error.has_value());
  CHECK(result.run_error.value() == "boom");  // NOLINT(bugprone-unchecked-optional-access)
  std::filesystem::remove_all(tmp);
}

TEST_CASE("finish_run: writes reports to out_dir") {
  const auto tmp = std::filesystem::temp_directory_path() /
                   ("endurance-finish-run-test-" + std::to_string(std::random_device{}()));
  FinishRunArgs args;
  args.test_name = "lifecycle-churn";
  args.sdk = "C++";
  args.duration_s = 300;
  args.started_at = "2026-09-13T10:00:00+00:00";
  args.metrics = {ok_metric("rss", 1.0, 1.0)};
  args.iterations = 5;
  args.out_dir = tmp.string();
  finish_run(args);
  CHECK(std::filesystem::exists(tmp / "lifecycle-churn.json"));
  CHECK(std::filesystem::exists(tmp / "lifecycle-churn-report.md"));
  std::filesystem::remove_all(tmp);
}

TEST_CASE("finish_run: a write_reports failure does not mask the original run_error") {
  // Regression (mirrors sdks/python/tests/test_endurance_report.py's
  // identical case): write_reports() throwing inside finish_run() must not
  // replace the caller's own `run_error` — a reporting-layer bug (disk
  // full, an unwritable path) should not hide the real failure. Forced here
  // by pointing `out_dir` at a path that already exists as a regular file,
  // so create_directories() cannot succeed.
  const auto blocking_file =
      std::filesystem::temp_directory_path() /
      ("endurance-finish-run-blocker-" + std::to_string(std::random_device{}()));
  {
    std::ofstream f(blocking_file);
    f << "not a directory";
  }
  std::exception_ptr pending;
  try {
    throw std::runtime_error("the real failure");
  } catch (...) {
    pending = std::current_exception();
  }
  FinishRunArgs args;
  args.test_name = "lifecycle-churn";
  args.sdk = "C++";
  args.duration_s = 300;
  args.started_at = "2026-09-13T10:00:00+00:00";
  args.iterations = 1;
  args.out_dir = (blocking_file / "endurance-results").string();
  args.run_error = pending;
  const auto result = finish_run(args);
  CHECK(result.status == "ERROR");
  REQUIRE(result.run_error.has_value());
  // NOLINTNEXTLINE(bugprone-unchecked-optional-access)
  CHECK(result.run_error.value() == "the real failure");
  std::filesystem::remove(blocking_file);
}
