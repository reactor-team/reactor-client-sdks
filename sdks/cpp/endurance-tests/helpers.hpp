// Endurance-loop plumbing that *does* need this SDK's FFI: resource
// sampling (backed by /proc, Linux-only — see below) plus the real-client
// helpers every scenario shares (connect_with_retries,
// pump_until_frame_received). The pass/fail semantics
// (assert_no_sustained_growth and friends) live in trends.hpp, and the
// report data types and rendering live in report.hpp — both FFI-free on
// purpose, so they can be unit-tested without a live service or a built
// libreactor_ffi. See those headers' own top comments, and ../README.md for
// the fuller rationale behind every signal here.
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

#include "report.hpp"
#include "trends.hpp"

namespace endurance {

/// Samples process-wide resource usage once per cycle of an endurance loop,
/// against a shared wall-clock deadline.
class ResourceSampler {
 public:
  explicit ResourceSampler(double duration_s = endurance_duration_seconds());

  bool deadline_reached() const;

  /// Takes one reading and appends it to `samples()`. Under
  /// `ENDURANCE_VERBOSE=1`, also prints it immediately as one row of the
  /// detailed per-cycle table (see `print_live_row()`) — the debug path;
  /// `report::LiveReporter` handles the default compact path instead, and a
  /// scenario's own loop is what calls it, not this method.
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
  //
  // print_report(): the full recap table, one row per sample — kept for
  // ad-hoc/manual use (a REPL, a one-off debugging session) rather than
  // called by the scenarios themselves: under ENDURANCE_VERBOSE=1 every row
  // already printed live via print_live_row() below as it happened, and
  // under the default compact mode a full raw dump defeats the point of
  // LiveReporter's summaries (report.hpp) — the JSON/MD/text reports
  // finish_and_check() writes are what a scenario relies on instead.
  void print_report() const;

  /// One detailed row for the most recent sample, printed immediately — the
  /// `ENDURANCE_VERBOSE=1` debug path. Prints the header once, on the first
  /// sample, rather than buffering the whole table for a final dump.
  void print_live_row() const;

 private:
  double start_s_;
  double duration_s_;
  std::vector<Sample> samples_;
};

/// Connects `client` (via `integration::paced_connect`), retrying up to
/// `max_attempts` times with a fixed delay between attempts, before letting
/// the last failure propagate.
///
/// `paced_connect` already retries once on a `RateLimitedError` — this is a
/// coarser, outer retry for anything else that can fail a connect, written
/// after a real CI run lost ~20 minutes of accumulated trend to one
/// isolated, transient 503 from the coordinator's token-exchange endpoint
/// (confirmed via Grafana as a one-off, not an outage) — an unattended,
/// hours-long run shouldn't die over a few seconds of blip.
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

/// One chunk of a synthetic sine-wave tone as interleaved 16-bit PCM — the
/// endurance suite's own small stand-in for integration-tests/'s AudioPump
/// (which owns and paces its own background thread; the steady-state audio
/// scenario here paces itself instead, on the same thread as its sampling
/// loop, so it can interleave `push_frame` with `sampler.sample()` on a fixed
/// cadence the way test_video_publish_steady.cpp does for video).
///
/// `phase` is threaded through by the caller (not reset every call) so
/// consecutive chunks form one continuous tone rather than restarting from
/// zero phase — and clicking — every chunk boundary.
std::vector<std::int16_t> sine_wave_chunk(std::size_t frames_per_chunk, std::uint32_t channels,
                                          double sample_rate, double& phase);

}  // namespace endurance
