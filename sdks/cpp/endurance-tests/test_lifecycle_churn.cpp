// Endurance: repeated full connect -> publish -> command -> disconnect ->
// destroy cycles, each on a brand-new `reactor::Reactor`. Mirrors
// sdks/python/endurance-tests/tests/test_lifecycle_churn.py.
//
// Manual-only for now (see ../README.md). Run with:
//
//     ENDURANCE_DURATION_SECONDS=3600 mise run test:cpp:endurance-tests
//
// A fresh client per cycle is what actually exercises the full native-handle
// lifecycle (construction through the FFI, then destruction releasing it),
// unlike test_session_churn.cpp's single long-lived session, which only ever
// takes the per-operation paths.

#include <atomic>
#include <exception>
#include <reactor/version.hpp>
#include <string>

#include <catch2/catch_test_macros.hpp>

#include "fixtures.hpp"
#include "helpers.hpp"
#include "report.hpp"
#include "trends.hpp"

namespace {
constexpr std::uint32_t WIDTH = 64;
constexpr std::uint32_t HEIGHT = 64;

const char* const DESCRIPTION =
    "Fresh Reactor per cycle: connect, publish, push frames, send a command, "
    "disconnect, destroy. Exercises the whole native-handle lifecycle.";
}  // namespace

TEST_CASE("lifecycle churn leaves no leftover threads/fds or resource growth",
          "[lifecycle_churn]") {
  endurance::ResourceSampler sampler;
  endurance::LiveReporter live("lifecycle-churn", endurance::endurance_duration_seconds());
  const auto frame = integration::solid_bgra_frame(WIDTH, HEIGHT, 200, 80, 40);
  int cycle = 0;
  std::exception_ptr pending;
  const std::string started_at = endurance::now_iso();

  try {
    while (!sampler.deadline_reached()) {
      // A fresh ReactorFactory per cycle, scoped to this block: its
      // destructor disconnects and destroys this cycle's one client before
      // the next cycle starts, the RAII equivalent of the Python suite's
      // disconnect()-then-close() pair. Unlike ReactorFactory's usual
      // integration-test role (accumulate everything a whole test creates,
      // tear down at the very end), a fresh one every cycle is what actually
      // exercises repeated construction/destruction rather than
      // accumulating thousands of clients for the run's whole duration.
      {
        // A unique_ptr, not a plain local: C++ destroys locals in *reverse*
        // declaration order, so a plain `ReactorFactory factory;` declared
        // here (before `subscription` below) would have its destructor run
        // *after* subscription's — unregistering the frame handler first,
        // then disconnecting/destroying an already-handler-free client. That
        // silently skips the one thing this cycle means to exercise: closing
        // a client while its subscription is still registered (caught by
        // Codex review on PR #170). `factory.reset()` below destroys the
        // client at the exact point that matters, independent of where
        // `subscription` happens to be declared.
        auto factory = std::make_unique<integration::ReactorFactory>();
        auto& client = factory->create();
        // Not plain paced_connect(): this cycle's connect is one of
        // hundreds over a run that can last hours, so a transient
        // coordinator-side blip here shouldn't cost the whole accumulated
        // trend — see connect_with_retries' own comment for why.
        endurance::connect_with_retries(client);

        // Registered and never explicitly torn down (unlike
        // test_session_churn.cpp's explicit remove()) — the client's
        // destruction below has to clean this up on its own, on a client
        // that may still have a frame in flight. A bounded flag, not an
        // accumulating buffer: only whether one frame arrived matters to
        // pump_until_frame_received() below.
        std::atomic<bool> received{false};
        auto main_video = client.track("main_video");
        auto subscription =
            main_video.on_frame([&](const reactor::VideoFrame&) { received.store(true); });

        auto webcam = client.track("webcam");
        webcam.publish().get();
        endurance::pump_until_frame_received(webcam, frame, WIDTH, HEIGHT, received);
        client.send_command("set_intensity", {{"intensity", 0.5}}).get();
        webcam.unpublish();

        // Disconnects and destroys the client here, now, while `subscription`
        // is still a live, registered handler — see the comment on
        // `factory`'s declaration above for why this can't just be left to
        // the closing brace below.
        factory.reset();
      }  // subscription/main_video/webcam/received destroyed here, on an
         // already-gone client — safe: Subscription::remove() is documented
         // idempotent and safe after the client is gone, and Track holds its
         // client only weakly.

      sampler.sample(cycle);
      live.update(sampler.samples());
      ++cycle;
    }
  } catch (...) {
    // A transient failure (a RateLimitedError, a network hiccup) would
    // otherwise abort this test before the report below ever renders —
    // losing the whole run's accumulated RSS/CPU/thread/fd trend to one
    // hiccup defeats a soak test more than the hiccup itself. Whatever was
    // collected up to the failure still gets reported, then the failure
    // still fails the test.
    pending = std::current_exception();
  }

  std::vector<endurance::MetricResult> metrics;
  if (!pending) {
    if (cycle < 3) {
      pending = std::make_exception_ptr(std::runtime_error(
          "only completed " + std::to_string(cycle) +
          " cycle(s) — raise ENDURANCE_DURATION_SECONDS to get enough data for a trend"));
    } else {
      // No live_clients/orphaned_callbacks check here, unlike the Python
      // suite's identical scenario — see ../README.md's "known, deliberate
      // scope gap" for why this binding has no equivalent, and
      // trends.hpp's own comment on standard_resource_metrics() for why
      // num_fds gets the same median-backed trend check as num_threads
      // rather than Python's exact "never past its starting value": two
      // real runs of this scenario each showed num_fds take one isolated
      // step on a single cycle — once between cycle 0 and cycle 1
      // (consistent with some process-wide resource finishing its warm-up
      // on this process's very first cycle), and separately on the very
      // last cycle of a 90s/10-cycle run (consistent with that cycle's
      // sample catching the *previous* cycle's own socket teardown still in
      // flight) — neither a per-cycle leak, which would keep climbing
      // sample over sample rather than step once.
      //
      // `cycle < 3` above is a floor on completed cycles, not on the sample
      // count each metric actually needs — cpu_deltas() alone drops one
      // value relative to the raw samples, so a run landing at exactly 3-6
      // cycles can still be short of what assert_no_sustained_growth
      // requires and throw here. Caught the same way the run loop's own
      // failures are, so a short run still gets an ERROR report instead of
      // an uncaught exception skipping finish_and_check entirely.
      try {
        metrics = endurance::standard_resource_metrics(sampler.samples());
      } catch (...) {
        pending = std::current_exception();
      }
    }
  }

  endurance::FinishAndCheckArgs args;
  args.test_name = "lifecycle-churn";
  args.sdk = "C++";
  args.description = DESCRIPTION;
  args.sdk_version = std::string(reactor::version());
  args.duration_s = endurance::endurance_duration_seconds();
  args.started_at = started_at;
  args.metrics = metrics;
  args.iterations = cycle;
  args.run_error = pending;
  endurance::finish_and_check(sampler.samples(), live, args);

  if (pending) {
    std::rethrow_exception(pending);
  }
}
