// Endurance-loop plumbing: duration handling, resource sampling, and the
// trend/count assertions built on top of it. Mirrors
// sdks/python/endurance-tests/helpers.py file-for-file — see ../../python/
// endurance-tests/README.md for the fuller rationale behind every signal
// here, and this directory's own README.md for what's deliberately different
// in this binding.
//
// Linux-only: /proc/self/{status,stat,fd} is how this reads RSS/CPU/thread/fd
// counts, matching how CI runs this suite (ubuntu-latest, same as
// cpp-integration-tests — see ../../../.github/workflows/ci.yml). Nothing
// else here is Linux-specific; a macOS/Windows implementation of
// ResourceSampler's three readings would be a self-contained follow-up.
#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <reactor/reactor.hpp>
#include <string>
#include <vector>

namespace endurance {

// ── duration ─────────────────────────────────────────────────────────────────
//
// Wall-clock driven, not a fixed iteration count — same reasoning as the
// Python suite's own ENDURANCE_DURATION_SECONDS: one knob, shared by every
// scenario, so a quick manual sanity check and an hours-long leak hunt run
// the exact same code.
double endurance_duration_seconds();

/// One process-wide resource reading, taken once per cycle of an endurance
/// loop.
struct Sample {
  int cycle = 0;
  double elapsed_s = 0.0;
  std::uint64_t rss_bytes = 0;
  double cpu_s = 0.0;  // cumulative user+sys CPU time since process start
  // % of one CPU core busy since the *previous* sample — see
  // ResourceSampler::sample()'s own comment for how this is derived. 0.0 on
  // the first sample, which has no previous one to diff against.
  double cpu_percent = 0.0;
  // OS-level, not SDK-level: unlike the Python binding, this SDK has no
  // GC-driven handle registry to also check (_LIVE_CLIENTS/
  // _ORPHANED_CALLBACKS) — see README.md's "known scope gap" section for why
  // that's a deliberate omission here, not an oversight.
  long num_threads = 0;
  long num_fds = 0;
};

/// Samples process-wide resource usage once per cycle of an endurance loop,
/// against a shared wall-clock deadline.
class ResourceSampler {
 public:
  explicit ResourceSampler(double duration_s = endurance_duration_seconds());

  bool deadline_reached() const;

  /// Takes one reading and appends it to `samples()`.
  const Sample& sample(int cycle);

  const std::vector<Sample>& samples() const noexcept { return samples_; }

  // The report table's columns, in plain language — read this before reading
  // a printed table, not just README.md's own copy of the same explanation:
  //
  //   cycle           which iteration of the loop this row is (0, 1, 2, ...).
  //   elapsed_s       seconds since this test started.
  //   ram_mb          physical RAM this whole process is using right now
  //                   (not just the SDK — everything in this one process).
  //                   Should plateau, not keep climbing.
  //   cpu_s_per_cycle CPU time *this one cycle* burned (not a running total —
  //                   see cpu_deltas()'s own comment for why that matters).
  //                   Should stay roughly flat cycle to cycle.
  //   cpu_percent     % of one CPU core busy since the previous row. Can read
  //                   over 100% if more than one native thread is genuinely
  //                   busy at once — expected, not a bug. Should stay roughly
  //                   flat, same as cpu_s_per_cycle.
  //   num_threads     OS-level threads this process currently has (mostly
  //                   the native Rust runtime's). Some warm-up wobble is
  //                   normal; should not keep climbing.
  //   num_fds         open file descriptors (sockets, mainly — every WebRTC
  //                   connection needs some). Should not keep climbing.
  void print_report() const;

 private:
  double start_s_;
  double duration_s_;
  std::vector<Sample> samples_;
};

// ── trend assertions ─────────────────────────────────────────────────────────

/// Per-interval CPU consumption, derived from `Sample::cpu_s`.
///
/// `cpu_s` is cumulative process CPU time since the process started —
/// monotonically non-decreasing by construction, so a trend check on the raw
/// values would always report growth regardless of whether anything is
/// actually getting more expensive. Diffing consecutive samples reframes it
/// as CPU spent *per interval*, which is what can actually flag a cycle
/// getting slower over the course of a run.
std::vector<double> cpu_deltas(const std::vector<Sample>& samples);

/// Fail (throw std::runtime_error) if `values`'s mean over the run's last
/// third exceeds its mean over the first third (after dropping
/// `warmup_fraction` to let one-time costs — allocator warm-up, connection
/// setup — settle) by more than `max_growth_ratio`, *and* by more than
/// `min_absolute_delta` in absolute terms.
///
/// The absolute floor exists so a tiny, near-zero baseline can't turn an
/// insignificant wobble into a ratio that looks huge. `use_median` swaps the
/// mean for a median within each window — see
/// sdks/python/endurance-tests/helpers.py's identical parameter for why
/// (num_threads' one-cycle teardown-in-flight artifact).
///
/// Always prints one line verdict, pass or fail, so a clean run still says
/// *why* each signal looked fine.
void assert_no_sustained_growth(const std::vector<double>& values, const std::string& name,
                                double max_growth_ratio, double min_absolute_delta = 0.0,
                                double warmup_fraction = 0.2, bool use_median = false);

/// Fail if `get(sample)`'s peak across `samples` ever exceeds its value on
/// the first sample — for a count that's expected to stay flat across the
/// whole run (not necessarily 0), *and* whose teardown is synchronous enough
/// that no single cycle should ever catch it mid-flight. Always prints one
/// line verdict, pass or fail.
///
/// Mirrors `sdks/python/endurance-tests/helpers.py`'s identical
/// `assert_never_grows` — but unlike that binding, nothing sampled by this
/// suite has actually proven exact enough in a real run to use this check;
/// see test_lifecycle_churn.cpp's own comment on why num_fds uses
/// `assert_no_sustained_growth` instead. Kept here for parity and for a
/// signal a future run does prove exact.
void assert_never_grows(const std::vector<Sample>& samples,
                        const std::function<long(const Sample&)>& get, const std::string& name);

/// Connects `client` (via `integration::paced_connect`), retrying up to
/// `max_attempts` times with a fixed delay between attempts, before letting
/// the last failure propagate.
///
/// `paced_connect` already retries once on a `RateLimitedError` — this is a
/// coarser, outer retry for anything else that can fail a connect, written
/// after a real CI run lost ~20 minutes of accumulated trend to one
/// coordinator-side blip (the token-exchange service answering 503 for a
/// few seconds, surfaced as an auth failure). An endurance run is long and
/// unattended specifically so a human doesn't have to babysit it; failing
/// the whole run over a handful of seconds of transient unavailability
/// defeats that. Retrying on a *non*-transient failure (a genuinely invalid
/// key) just costs a few extra fast attempts before still failing with the
/// same error — an acceptable trade for not losing hours of data to
/// something that clears itself up in seconds.
void connect_with_retries(reactor::Reactor& client, int max_attempts = 5,
                          double retry_delay_s = 5.0);

/// Pushes `bgra` into `track` at ~`fps` until `received` is set (by an
/// `on_frame` handler registered on the *receiving* track, e.g. `main_video`)
/// or `timeout_s` elapses.
///
/// Exists so the endurance loops also exercise the receive path — not just
/// publish/push_frame, which never touches the on_frame subscription at all.
/// Best-effort and never throws: this suite is about churn/leak detection
/// over many cycles, not frame-delivery correctness (integration-tests/
/// already covers that with a real timeout-and-fail wait_until) — one cycle
/// where nothing arrived in time just means that cycle's receive path went
/// untouched, not a failure worth stopping an hours-long run over.
///
/// `received` is written from the on_frame callback, which runs on the FFI's
/// own delivery thread — an std::atomic<bool>, not a plain bool, because
/// unlike Python's GIL this has no implicit synchronization.
bool pump_until_frame_received(reactor::Track& track, const std::vector<std::uint8_t>& bgra,
                               std::uint32_t width, std::uint32_t height,
                               std::atomic<bool>& received, double timeout_s = 2.0,
                               double fps = 30.0);

}  // namespace endurance
