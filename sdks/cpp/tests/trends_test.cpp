// Tests for the endurance-tests suite's pass/fail semantics
// (../endurance-tests/trends.cpp). Pure functions, no FFI/reactor::sdk
// involved — mirrors sdks/python/tests/test_trends.py.
#include "trends.hpp"

#include <array>

#include <catch2/catch_approx.hpp>
#include <catch2/catch_test_macros.hpp>

using endurance::assert_always_zero;
using endurance::assert_never_grows;
using endurance::assert_no_sustained_growth;
using endurance::cpu_deltas;
using endurance::Sample;

namespace {

Sample make_sample(int cycle, double cpu_s = 0.0, long num_threads = 20, long num_fds = 15,
                   double cpu_percent = 5.0) {
  Sample s;
  s.cycle = cycle;
  s.elapsed_s = static_cast<double>(cycle);
  s.rss_bytes = 100'000'000;
  s.cpu_s = cpu_s;
  s.cpu_percent = cpu_percent;
  s.num_threads = num_threads;
  s.num_fds = num_fds;
  return s;
}

}  // namespace

TEST_CASE("cpu_deltas diffs consecutive cumulative values") {
  std::vector<Sample> samples;
  samples.reserve(4);
  const std::array<double, 4> values = {0.0, 0.5, 1.2, 1.4};
  for (int i = 0; i < 4; ++i) {
    samples.push_back(make_sample(i, values.at(static_cast<std::size_t>(i))));
  }
  const auto deltas = cpu_deltas(samples);
  REQUIRE(deltas.size() == 3);
  CHECK(deltas[0] == Catch::Approx(0.5));
  CHECK(deltas[1] == Catch::Approx(0.7));
  CHECK(deltas[2] == Catch::Approx(0.2));
}

TEST_CASE("cpu_deltas on empty or single sample yields no deltas") {
  CHECK(cpu_deltas({}).empty());
  CHECK(cpu_deltas({make_sample(0)}).empty());
}

TEST_CASE("assert_no_sustained_growth: too few samples throws, not just fails") {
  // n < 6 is a test misconfiguration (ENDURANCE_DURATION_SECONDS too short),
  // not a metric to report on — see the function's own doc-comment for why
  // this one case still throws directly.
  CHECK_THROWS_AS(assert_no_sustained_growth({1.0, 2.0, 3.0}, "x", 0.15), std::runtime_error);
}

TEST_CASE("assert_no_sustained_growth: flat values pass") {
  const std::vector<double> values(30, 100.0);
  const auto result = assert_no_sustained_growth(values, "rss", 0.15, 5.0, 0.2, false, "MB");
  CHECK(result.status == "ok");
}

TEST_CASE("assert_no_sustained_growth: still climbing in the tail fails") {
  // Genuinely still trending up in the last third relative to the middle
  // third, well past both the ratio and absolute floors.
  std::vector<double> values;
  values.reserve(30);
  for (int i = 0; i < 30; ++i) {
    values.push_back(100.0 + static_cast<double>(i) * 5.0);
  }
  const auto result = assert_no_sustained_growth(values, "rss", 0.15, 5.0, 0.2, false, "MB");
  CHECK(result.status == "fail");
  CHECK(result.detail.rfind("still climbing", 0) == 0);
}

TEST_CASE("assert_no_sustained_growth: a one-time ramp that has already plateaued passes") {
  // The exact shape that motivated middle-vs-last over first-vs-last: flat,
  // then a one-time ramp to a new plateau early in the run, then flat for
  // the rest. The *overall* first-to-last movement is large, but the tail
  // (last third vs middle third) is flat — a native buffer/pool growing
  // once to steady-state, not an unbounded leak, so this must pass.
  constexpr int n = 30;
  std::vector<double> values;
  for (int i = 0; i < n; ++i) {
    const double pct = static_cast<double>(i) / static_cast<double>(n - 1);
    if (pct < 0.25) {
      values.push_back(100.0);
    } else if (pct < 0.45) {
      values.push_back(100.0 + (pct - 0.25) / 0.20 * 40.0);
    } else {
      values.push_back(140.0);
    }
  }
  const auto result = assert_no_sustained_growth(values, "rss", 0.15, 5.0, 0.2, false, "MB");
  CHECK(result.status == "ok");
  // The reported start/end still show the true overall movement, even
  // though that's not what decided pass/fail.
  CHECK(result.change > 20.0);
}

TEST_CASE("assert_no_sustained_growth: min_absolute_delta floor suppresses a tiny-baseline ratio") {
  // A tiny near-zero baseline can turn an insignificant wobble into a ratio
  // that looks huge — the absolute floor exists for exactly this.
  std::vector<double> values(15, 0.01);
  values.insert(values.end(), 15, 0.02);
  const auto result =
      assert_no_sustained_growth(values, "cpu_s_per_cycle", 0.15, 0.05, 0.2, false, "s");
  CHECK(result.status == "ok");
}

TEST_CASE("assert_no_sustained_growth: use_median ignores a single outlier cycle") {
  // A one-cycle double-counted-thread/fd artifact landing in the last
  // window shouldn't by itself read as a sustained trend.
  std::vector<double> values(12, 25.0);
  const std::array<double, 6> tail = {25.0, 25.0, 31.0, 25.0, 25.0, 25.0};
  values.insert(values.end(), tail.begin(), tail.end());
  const auto result = assert_no_sustained_growth(values, "num_threads", 0.15, 4.0, 0.2,
                                                 /*use_median=*/true, "count");
  CHECK(result.status == "ok");
}

TEST_CASE("assert_always_zero: stays zero passes") {
  std::vector<Sample> samples;
  samples.reserve(10);
  for (int i = 0; i < 10; ++i) {
    samples.push_back(make_sample(i));
  }
  const auto result = assert_always_zero(samples, [](const Sample&) { return 0L; }, "orphaned");
  CHECK(result.status == "ok");
}

TEST_CASE("assert_always_zero: nonzero mid-run fails even if it settles back to zero") {
  // Regression: a leak on cycle 3 that happens to get cleaned up by the
  // final cycle is still a real bug — must not read as "ok" just because
  // the last sample is 0.
  const std::array<long, 6> values = {0, 0, 0, 2, 0, 0};
  std::vector<Sample> samples;
  samples.reserve(6);
  for (int i = 0; i < 6; ++i) {
    samples.push_back(make_sample(i));
  }
  const auto result = assert_always_zero(
      samples, [&](const Sample& s) { return values.at(static_cast<std::size_t>(s.cycle)); },
      "orphaned");
  CHECK(result.status == "fail");
  REQUIRE(result.first_bad_cycle.has_value());
  CHECK(result.first_bad_cycle.value() == 3);  // NOLINT(bugprone-unchecked-optional-access)
}

TEST_CASE("assert_always_zero: reports the peak, not the final sample") {
  // Regression (codex review on PR #186 upstream, ported here): using the
  // *final* sample's value for end/change would render "went from 0 to 0
  // (+0)" whenever the count had already settled back to 0 by the last
  // cycle — hiding the leaked value entirely.
  const std::array<long, 6> values = {0, 0, 5, 3, 0, 0};
  std::vector<Sample> samples;
  samples.reserve(6);
  for (int i = 0; i < 6; ++i) {
    samples.push_back(make_sample(i));
  }
  const auto result = assert_always_zero(
      samples, [&](const Sample& s) { return values.at(static_cast<std::size_t>(s.cycle)); },
      "orphaned");
  CHECK(result.status == "fail");
  CHECK(result.end == 5);
  CHECK(result.change == 5);
  CHECK(result.detail.find("peak 5") != std::string::npos);
}

TEST_CASE("assert_never_grows: flat at baseline passes") {
  std::vector<Sample> samples;
  samples.reserve(10);
  for (int i = 0; i < 10; ++i) {
    samples.push_back(make_sample(i));
  }
  const auto result =
      assert_never_grows(samples, [](const Sample& s) { return s.num_threads; }, "num_threads");
  CHECK(result.status == "ok");
}

TEST_CASE("assert_never_grows: growth past the starting value fails") {
  const std::array<long, 5> values = {1, 1, 1, 2, 1};
  std::vector<Sample> samples;
  samples.reserve(5);
  for (int i = 0; i < 5; ++i) {
    samples.push_back(make_sample(i));
  }
  const auto result = assert_never_grows(
      samples, [&](const Sample& s) { return values.at(static_cast<std::size_t>(s.cycle)); },
      "live_clients");
  CHECK(result.status == "fail");
  CHECK(result.start == 1);
  CHECK(result.end == 2);
}

TEST_CASE("standard_resource_metrics returns the shared five checks") {
  std::vector<Sample> samples;
  samples.reserve(30);
  for (int i = 0; i < 30; ++i) {
    samples.push_back(make_sample(i, /*cpu_s=*/static_cast<double>(i) * 0.01));
  }
  const auto metrics = endurance::standard_resource_metrics(samples);
  // rss, cpu_s_per_cycle, cpu_percent, num_threads, num_fds — no
  // live_clients/orphaned_callbacks equivalent in this binding (see
  // ../../endurance-tests/README.md).
  REQUIRE(metrics.size() == 5);
  for (const auto& m : metrics) {
    CHECK(m.status == "ok");
  }
}
