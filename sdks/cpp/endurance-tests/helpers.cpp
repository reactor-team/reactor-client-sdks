#include "helpers.hpp"

#include <dirent.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cstdlib>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <numeric>
#include <reactor/errors.hpp>
#include <sstream>
#include <stdexcept>
#include <thread>

#include "fixtures.hpp"

namespace endurance {

namespace {

// ── /proc readers ────────────────────────────────────────────────────────────
//
// No dependency (no psutil equivalent linked into this SDK) reads these for
// us, so this suite reads /proc directly — see helpers.hpp's own note on why
// that's fine here (Linux-only, matches how CI runs it).

std::uint64_t read_rss_bytes() {
  std::ifstream status("/proc/self/status");
  std::string line;
  while (std::getline(status, line)) {
    if (line.rfind("VmRSS:", 0) == 0) {
      std::istringstream iss(line.substr(6));
      std::uint64_t kb = 0;
      iss >> kb;
      return kb * 1024;
    }
  }
  throw std::runtime_error("VmRSS not found in /proc/self/status");
}

struct ProcStat {
  double cpu_s = 0.0;
  long num_threads = 0;
};

// utime/stime/num_threads from /proc/self/stat — fields 14, 15, 20 in
// `man proc(5)`'s 1-indexed numbering. The `comm` field (2nd) is parenthesized
// and may itself contain spaces or parens, so this parses from the *last* ')'
// on the line rather than naively splitting on whitespace from the start —
// the same trick every /proc/[pid]/stat parser needs.
ProcStat read_proc_stat() {
  std::ifstream stat("/proc/self/stat");
  std::string line;
  std::getline(stat, line);
  const auto close_paren = line.rfind(')');
  if (close_paren == std::string::npos) {
    throw std::runtime_error("malformed /proc/self/stat: no ')' found");
  }
  std::istringstream rest(line.substr(close_paren + 2));  // skip ") "
  std::vector<std::string> fields;
  std::string field;
  while (rest >> field) {
    fields.push_back(field);
  }
  // fields[0] is `state` (proc(5) field 3); utime is field 14, i.e.
  // fields[11], stime is field 15 (fields[12]), num_threads is field 20
  // (fields[17]).
  if (fields.size() <= 17) {
    throw std::runtime_error("malformed /proc/self/stat: too few fields after comm");
  }
  const long clock_ticks_per_s = sysconf(_SC_CLK_TCK);
  const auto utime = std::stoll(fields[11]);
  const auto stime = std::stoll(fields[12]);
  const auto num_threads = std::stol(fields[17]);
  return ProcStat{static_cast<double>(utime + stime) / static_cast<double>(clock_ticks_per_s),
                  num_threads};
}

// Counts entries under /proc/self/fd. The directory handle opened to do the
// counting is itself briefly one of those entries, which can inflate the
// count by up to 1 — a constant, cycle-to-cycle offset that doesn't affect
// any of this suite's checks (all of them compare against a baseline reading
// taken the exact same way, via assert_never_grows).
long read_num_fds() {
  DIR* dir = opendir("/proc/self/fd");
  if (dir == nullptr) {
    throw std::runtime_error("could not open /proc/self/fd");
  }
  long count = 0;
  while (readdir(dir) != nullptr) {
    ++count;
  }
  closedir(dir);
  return count - 2;  // "." and ".."
}

}  // namespace

double endurance_duration_seconds() {
  // Read fresh, not cached in a static: a static initialized before main()
  // (or before a test harness sets the env var) would freeze in the default,
  // and every call site here is once-per-ResourceSampler-construction anyway.
  const char* raw = std::getenv("ENDURANCE_DURATION_SECONDS");
  return raw != nullptr ? std::stod(raw) : 300.0;
}

namespace {
double monotonic_now_s() {
  return std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count();
}
}  // namespace

ResourceSampler::ResourceSampler(double duration_s)
    : start_s_(monotonic_now_s()), duration_s_(duration_s) {}

bool ResourceSampler::deadline_reached() const {
  return monotonic_now_s() - start_s_ >= duration_s_;
}

const Sample& ResourceSampler::sample(int cycle) {
  const ProcStat stat = read_proc_stat();
  Sample s;
  s.cycle = cycle;
  s.elapsed_s = monotonic_now_s() - start_s_;
  s.rss_bytes = read_rss_bytes();
  s.cpu_s = stat.cpu_s;
  s.num_threads = stat.num_threads;
  s.num_fds = read_num_fds();
  // Same reasoning as the Python suite's own psutil.cpu_percent(interval=None):
  // busy-ness *since the previous sample*, not since process start — the
  // first sample has no previous one, so it reads 0.0 rather than something
  // meaningless.
  if (!samples_.empty()) {
    const Sample& prev = samples_.back();
    const double dt = s.elapsed_s - prev.elapsed_s;
    s.cpu_percent = dt > 0.0 ? 100.0 * (s.cpu_s - prev.cpu_s) / dt : 0.0;
  }
  samples_.push_back(s);
  return samples_.back();
}

void ResourceSampler::print_report() const {
  const auto deltas = cpu_deltas(samples_);
  std::cout << "\n"
            << std::setw(6) << "cycle" << " " << std::setw(10) << "elapsed_s" << " " << std::setw(8)
            << "ram_mb" << " " << std::setw(15) << "cpu_s_per_cycle" << " " << std::setw(11)
            << "cpu_percent" << " " << std::setw(11) << "num_threads" << " " << std::setw(7)
            << "num_fds" << "\n";
  std::cout << std::fixed;
  for (std::size_t i = 0; i < samples_.size(); ++i) {
    const Sample& s = samples_[i];
    std::cout << std::setw(6) << s.cycle << " " << std::setw(10) << std::setprecision(1)
              << s.elapsed_s << " " << std::setw(8) << std::setprecision(2)
              << static_cast<double>(s.rss_bytes) / 1e6 << " " << std::setw(15)
              << std::setprecision(3);
    if (i > 0) {
      std::cout << deltas[i - 1];
    } else {
      std::cout << "—";
    }
    std::cout << " " << std::setw(10) << std::setprecision(1) << s.cpu_percent << "% "
              << std::setw(11) << s.num_threads << " " << std::setw(7) << s.num_fds << "\n";
  }
  std::cout << std::defaultfloat;
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
}  // namespace

void assert_no_sustained_growth(const std::vector<double>& values, const std::string& name,
                                double max_growth_ratio, double min_absolute_delta,
                                double warmup_fraction, bool use_median) {
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
  const std::vector<double> last(warmed_up.end() - static_cast<std::ptrdiff_t>(third),
                                 warmed_up.end());

  const double first_mean = use_median ? median(first) : mean(first);
  const double last_mean = use_median ? median(last) : mean(last);
  const double delta = last_mean - first_mean;
  const double ratio = first_mean != 0.0 ? (delta / first_mean) : (delta > 0 ? 1.0 : 0.0);
  const bool is_leak = delta > min_absolute_delta && ratio > max_growth_ratio;

  std::ostringstream trend_stream;
  trend_stream << std::fixed << std::setprecision(3) << first_mean << " -> " << last_mean << " ("
               << std::showpos << std::setprecision(0) << ratio * 100 << std::noshowpos << "%)";
  const std::string trend = trend_stream.str();

  std::string reason;
  if (is_leak) {
    reason = "over the " + std::to_string(static_cast<int>(max_growth_ratio * 100)) +
             "% growth threshold — looks like a real leak, not noise";
  } else if (delta <= min_absolute_delta) {
    reason = "under the " + std::to_string(min_absolute_delta) + " floor, so it's noise";
  } else {
    reason = "under the " + std::to_string(static_cast<int>(max_growth_ratio * 100)) +
             "% growth threshold";
  }
  std::cout << "[" << name << "] " << (is_leak ? "LEAK?" : "ok") << ": " << trend << " — " << reason
            << "\n";

  if (is_leak) {
    throw std::runtime_error(name + " grew " + std::to_string(static_cast<int>(ratio * 100)) +
                             "% across the run (" + trend + ") — " + reason);
  }
}

void assert_never_grows(const std::vector<Sample>& samples,
                        const std::function<long(const Sample&)>& get, const std::string& name) {
  const long baseline = get(samples.front());
  long peak = baseline;
  for (const auto& s : samples) {
    peak = std::max(peak, get(s));
  }
  if (peak > baseline) {
    std::cout << "[" << name << "] LEAK?: grew from " << baseline << " to " << peak
              << " during the run\n";
    throw std::runtime_error(name + " grew from " + std::to_string(baseline) + " to " +
                             std::to_string(peak) + " during the run");
  }
  std::cout << "[" << name << "] ok: never exceeded its starting value (" << baseline
            << "; peak seen was " << peak << ")\n";
}

void connect_with_retries(reactor::Reactor& client, int max_attempts, double retry_delay_s) {
  for (int attempt = 1;; ++attempt) {
    try {
      integration::paced_connect(client);
      return;
    } catch (const reactor::ReactorError&) {
      if (attempt >= max_attempts) {
        throw;
      }
      std::this_thread::sleep_for(std::chrono::duration<double>(retry_delay_s));
    }
  }
}

bool pump_until_frame_received(reactor::Track& track, const std::vector<std::uint8_t>& bgra,
                               std::uint32_t width, std::uint32_t height,
                               std::atomic<bool>& received, double timeout_s, double fps) {
  const auto deadline = std::chrono::steady_clock::now() + std::chrono::duration<double>(timeout_s);
  const auto interval = std::chrono::duration<double>(1.0 / fps);
  while (!received.load() && std::chrono::steady_clock::now() < deadline) {
    track.push_frame(reactor::Bytes{bgra.data(), bgra.size()}, width, height);
    std::this_thread::sleep_for(interval);
  }
  return received.load();
}

}  // namespace endurance
