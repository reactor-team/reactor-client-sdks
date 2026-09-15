#include "helpers.hpp"

#include <dirent.h>
#include <unistd.h>

#include <chrono>
#include <cmath>
#include <cstdlib>
#include <fstream>
#include <iomanip>
#include <iostream>
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
// taken the exact same way).
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

double monotonic_now_s() {
  return std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

const char* kLiveRowHeader =
    "\n cycle  elapsed_s   ram_mb cpu_s_per_cycle cpu_percent num_threads num_fds";

}  // namespace

double endurance_sample_interval_seconds() {
  const char* raw = std::getenv("ENDURANCE_SAMPLE_INTERVAL_SECONDS");
  return raw != nullptr ? std::stod(raw) : 0.1;
}

ResourceSampler::ResourceSampler(double duration_s)
    : start_s_(monotonic_now_s()), duration_s_(duration_s) {}

bool ResourceSampler::deadline_reached() const {
  return monotonic_now_s() - start_s_ >= duration_s_;
}

const Sample& ResourceSampler::sample(int cycle) {
  // Throttled to at most one real sample per endurance_sample_interval_
  // seconds(). Exists because a scenario with no network wait at all (e.g.
  // pause-resume-churn: no frames, no commands, no reconnect — Track::pause()/
  // resume() round-trip through the FFI alone) iterates far faster than a
  // network-bound scenario, and this method used to append one `Sample`
  // per call unconditionally — the retained vector itself, and the /proc
  // reads behind every entry, then became the dominant cost the resource
  // trend was supposed to be measuring instead of the SDK, mirroring the
  // same false "RSS leak" the Python suite hit and fixed the same way (see
  // sdks/python/endurance-tests/helpers.py's ENDURANCE_SAMPLE_INTERVAL_
  // SECONDS). `samples_` is never empty past the first call, so this only
  // ever short-circuits from the second call on — the very first sample is
  // always taken, unconditionally, regardless of the interval.
  const double now = monotonic_now_s();
  if (last_sample_at_s_.has_value() &&
      (now - *last_sample_at_s_) < endurance_sample_interval_seconds()) {
    return samples_.back();
  }
  last_sample_at_s_ = now;

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
  if (endurance_verbose()) {
    print_live_row();
  }
  return samples_.back();
}

void ResourceSampler::print_live_row() const {
  if (samples_.size() == 1) {
    std::cout << kLiveRowHeader << "\n";
  }
  const Sample& s = samples_.back();
  const auto deltas = cpu_deltas(samples_);
  std::cout << std::setw(6) << s.cycle << " " << std::setw(10) << std::fixed << std::setprecision(1)
            << s.elapsed_s << " " << std::setw(8) << std::setprecision(2)
            << static_cast<double>(s.rss_bytes) / 1e6 << " " << std::setw(15)
            << std::setprecision(3);
  if (!deltas.empty()) {
    std::cout << deltas.back();
  } else {
    std::cout << "—";
  }
  std::cout << " " << std::setw(10) << std::setprecision(1) << s.cpu_percent << "% "
            << std::setw(11) << s.num_threads << " " << std::setw(7) << s.num_fds << "\n";
  std::cout << std::defaultfloat;
}

void ResourceSampler::print_report() const {
  const auto deltas = cpu_deltas(samples_);
  std::cout << kLiveRowHeader << "\n";
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

namespace {
constexpr double kTonePi = 3.14159265358979323846;
}  // namespace

std::vector<std::int16_t> sine_wave_chunk(std::size_t frames_per_chunk, std::uint32_t channels,
                                          double sample_rate, double& phase) {
  constexpr double kToneHz = 440.0;  // A4 — audible, arbitrary; echo passes audio through unchanged
  constexpr std::int16_t kAmplitude = 8000;  // headroom under INT16_MAX
  const double phase_step = 2.0 * kTonePi * kToneHz / sample_rate;

  std::vector<std::int16_t> chunk(frames_per_chunk * channels);
  for (std::size_t frame = 0; frame < frames_per_chunk; ++frame) {
    const auto sample_value = static_cast<std::int16_t>(std::sin(phase) * kAmplitude);
    phase += phase_step;
    for (std::uint32_t channel = 0; channel < channels; ++channel) {
      chunk[(frame * channels) + channel] = sample_value;
    }
  }
  return chunk;
}

}  // namespace endurance
