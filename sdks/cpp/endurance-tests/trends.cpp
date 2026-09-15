#include "trends.hpp"

#include <algorithm>
#include <cstdlib>
#include <iomanip>
#include <iostream>
#include <numeric>
#include <sstream>
#include <stdexcept>

namespace endurance {

double endurance_duration_seconds() {
  const char* raw = std::getenv("ENDURANCE_DURATION_SECONDS");
  return raw != nullptr ? std::stod(raw) : 300.0;
}

std::vector<double> cpu_deltas(const std::vector<Sample>& samples) {
  std::vector<double> deltas;
  if (samples.size() < 2) {
    return deltas;
  }
  deltas.reserve(samples.size() - 1);
  for (std::size_t i = 1; i < samples.size(); ++i) {
    deltas.push_back(samples[i].cpu_s - samples[i - 1].cpu_s);
  }
  return deltas;
}

namespace {

double mean(const std::vector<double>& xs) {
  return std::accumulate(xs.begin(), xs.end(), 0.0) / static_cast<double>(xs.size());
}

double median(std::vector<double> xs) {
  std::sort(xs.begin(), xs.end());
  const std::size_t mid = xs.size() / 2;
  return xs.size() % 2 == 0 ? (xs[mid - 1] + xs[mid]) / 2.0 : xs[mid];
}

std::string format_ratio(double ratio) {
  std::ostringstream oss;
  oss << (ratio >= 0 ? "+" : "") << std::fixed << std::setprecision(0) << (ratio * 100) << "%";
  return oss.str();
}

}  // namespace

MetricResult assert_no_sustained_growth(const std::vector<double>& values, const std::string& name,
                                        double max_growth_ratio, double min_absolute_delta,
                                        double warmup_fraction, bool use_median,
                                        const std::string& unit) {
  const std::size_t n = values.size();
  if (n < 6) {
    throw std::runtime_error("only " + std::to_string(n) + " " + name +
                             " sample(s) collected — raise ENDURANCE_DURATION_SECONDS to get "
                             "enough data for a trend");
  }
  const std::vector<double> warmed_up(
      values.begin() + static_cast<std::ptrdiff_t>(static_cast<double>(n) * warmup_fraction),
      values.end());
  const std::size_t third = std::max<std::size_t>(1, warmed_up.size() / 3);
  const std::vector<double> first(warmed_up.begin(),
                                  warmed_up.begin() + static_cast<std::ptrdiff_t>(third));
  const std::vector<double> middle(warmed_up.begin() + static_cast<std::ptrdiff_t>(third),
                                   warmed_up.begin() + static_cast<std::ptrdiff_t>(2 * third));
  const std::vector<double> last(warmed_up.end() - static_cast<std::ptrdiff_t>(third),
                                 warmed_up.end());

  const auto average = [use_median](const std::vector<double>& xs) {
    return use_median ? median(xs) : mean(xs);
  };
  const double first_mean = average(first);
  const double middle_mean = average(middle);
  const double last_mean = average(last);

  // What decides pass/fail — see this function's own doc-comment (trends.hpp)
  // for why this is middle-vs-last, not first-vs-last.
  const double tail_delta = last_mean - middle_mean;
  const double tail_ratio =
      middle_mean != 0.0 ? (tail_delta / middle_mean) : (tail_delta > 0 ? 1.0 : 0.0);
  const bool is_leak = tail_delta > min_absolute_delta && tail_ratio > max_growth_ratio;

  // What's reported to a human — the overall first-to-last movement, a
  // different (and both useful) number from what decided the verdict above.
  const double overall_delta = last_mean - first_mean;
  const double overall_ratio =
      first_mean != 0.0 ? (overall_delta / first_mean) : (overall_delta > 0 ? 1.0 : 0.0);

  std::ostringstream trend_stream;
  trend_stream << std::fixed << std::setprecision(3) << first_mean << " → " << middle_mean << " → "
               << last_mean << " (overall " << format_ratio(overall_ratio) << ", tail "
               << format_ratio(tail_ratio) << ")";
  const std::string trend = trend_stream.str();

  std::string reason;
  if (is_leak) {
    reason = "still climbing in the tail (last third " + format_ratio(tail_ratio) +
             " over the middle third) — over the " +
             std::to_string(static_cast<int>(max_growth_ratio * 100)) +
             "% threshold, looks like a real leak";
  } else if (tail_delta <= min_absolute_delta) {
    std::ostringstream floor_stream;
    floor_stream << std::setprecision(3) << tail_delta;
    reason = "the " + floor_stream.str() + " tail change is under the " +
             std::to_string(min_absolute_delta) +
             " floor, so it's noise (or an already-settled one-time step) regardless of the " +
             format_ratio(tail_ratio) + " tail ratio";
  } else {
    reason = "tail growth is under the " +
             std::to_string(static_cast<int>(max_growth_ratio * 100)) +
             "% threshold — not still climbing";
  }
  std::cout << "[" << name << "] " << (is_leak ? "LEAK?" : "ok") << ": " << trend << " — " << reason
            << "\n";

  MetricResult result;
  result.name = name;
  result.start = first_mean;
  result.end = last_mean;
  result.change = overall_delta;
  result.unit = unit;
  result.status = is_leak ? "fail" : "ok";
  result.detail = reason;
  result.threshold = "still climbing: last third > " +
                     std::to_string(static_cast<int>(max_growth_ratio * 100)) +
                     "% over middle third";
  result.mid = middle_mean;
  return result;
}

MetricResult assert_always_zero(const std::vector<Sample>& samples,
                                const std::function<long(const Sample&)>& get,
                                const std::string& name, const std::string& unit) {
  std::vector<std::pair<int, long>> bad;
  for (const auto& s : samples) {
    const long value = get(s);
    if (value != 0) {
      bad.emplace_back(s.cycle, value);
    }
  }
  const long last_value = get(samples.back());

  MetricResult result;
  result.name = name;
  result.unit = unit;
  result.start = 0;
  result.threshold = "always 0";

  if (!bad.empty()) {
    const auto [cycle, value] = bad.front();
    long peak_value = value;
    for (const auto& [c, v] : bad) {
      peak_value = std::max(peak_value, v);
    }
    const std::string reason = "nonzero on " + std::to_string(bad.size()) + "/" +
                               std::to_string(samples.size()) + " cycles (first at cycle " +
                               std::to_string(cycle) + ": " + std::to_string(value) + ", peak " +
                               std::to_string(peak_value) + ") — a handle leaked mid-run";
    std::cout << "[" << name << "] LEAK?: " << reason << "\n";
    result.end = static_cast<double>(peak_value);
    result.change = static_cast<double>(peak_value);
    result.status = "fail";
    result.detail = reason;
    result.first_bad_cycle = cycle;
    return result;
  }

  const std::string reason =
      "stayed at exactly 0 across all " + std::to_string(samples.size()) + " cycles";
  std::cout << "[" << name << "] ok: " << reason << "\n";
  result.end = static_cast<double>(last_value);
  result.change = static_cast<double>(last_value);
  result.status = "ok";
  result.detail = reason;
  return result;
}

MetricResult assert_never_grows(const std::vector<Sample>& samples,
                                const std::function<long(const Sample&)>& get,
                                const std::string& name, const std::string& unit) {
  const long baseline = get(samples.front());
  long peak = baseline;
  for (const auto& s : samples) {
    peak = std::max(peak, get(s));
  }

  MetricResult result;
  result.name = name;
  result.unit = unit;
  result.start = static_cast<double>(baseline);
  result.end = static_cast<double>(peak);
  result.change = static_cast<double>(peak - baseline);
  result.threshold = "never above " + std::to_string(baseline);

  if (peak > baseline) {
    const std::string reason =
        "grew from " + std::to_string(baseline) + " to " + std::to_string(peak) + " during the run";
    std::cout << "[" << name << "] LEAK?: " << reason << "\n";
    result.status = "fail";
    result.detail = reason;
    return result;
  }
  const std::string reason = "never exceeded its starting value (" + std::to_string(baseline) +
                             "; peak seen was " + std::to_string(peak) + ")";
  std::cout << "[" << name << "] ok: " << reason << "\n";
  result.status = "ok";
  result.detail = reason;
  return result;
}

std::vector<MetricResult> standard_resource_metrics(const std::vector<Sample>& samples) {
  std::vector<MetricResult> metrics;

  metrics.push_back(assert_no_sustained_growth(
      [&] {
        std::vector<double> rss;
        rss.reserve(samples.size());
        for (const auto& s : samples) {
          rss.push_back(static_cast<double>(s.rss_bytes) / 1e6);
        }
        return rss;
      }(),
      "rss", 0.15, 5.0, 0.2, /*use_median=*/false, "MB"));

  metrics.push_back(assert_no_sustained_growth(cpu_deltas(samples), "cpu_s_per_cycle", 0.5, 0.05,
                                               0.2, /*use_median=*/false, "s"));

  metrics.push_back(assert_no_sustained_growth(
      [&] {
        std::vector<double> pct;
        pct.reserve(samples.size());
        for (const auto& s : samples) {
          pct.push_back(s.cpu_percent);
        }
        return pct;
      }(),
      "cpu_percent", 0.5, 5.0, 0.2, /*use_median=*/false, "%"));

  // Median-backed, both of these — see this function's own doc-comment
  // (trends.hpp) for why num_fds joins num_threads here rather than getting
  // Python's exact "never past its starting value" check.
  metrics.push_back(assert_no_sustained_growth(
      [&] {
        std::vector<double> threads;
        threads.reserve(samples.size());
        for (const auto& s : samples) {
          threads.push_back(static_cast<double>(s.num_threads));
        }
        return threads;
      }(),
      "num_threads", 0.15, 4.0, 0.2, /*use_median=*/true, "count"));

  metrics.push_back(assert_no_sustained_growth(
      [&] {
        std::vector<double> fds;
        fds.reserve(samples.size());
        for (const auto& s : samples) {
          fds.push_back(static_cast<double>(s.num_fds));
        }
        return fds;
      }(),
      "num_fds", 0.15, 3.0, 0.2, /*use_median=*/true, "count"));

  return metrics;
}

}  // namespace endurance
