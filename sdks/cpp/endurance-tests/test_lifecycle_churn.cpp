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

#include <catch2/catch_test_macros.hpp>

#include "fixtures.hpp"
#include "helpers.hpp"

namespace {
constexpr std::uint32_t WIDTH = 64;
constexpr std::uint32_t HEIGHT = 64;
}  // namespace

TEST_CASE("lifecycle churn leaves no leftover threads/fds or resource growth") {
  endurance::ResourceSampler sampler;
  const auto frame = integration::solid_bgra_frame(WIDTH, HEIGHT, 200, 80, 40);
  int cycle = 0;
  std::exception_ptr pending;

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
      ++cycle;
    }
  } catch (...) {
    // A transient failure (a RateLimitedError, a network hiccup) would
    // otherwise abort this test before the report below ever prints —
    // losing the whole run's accumulated RSS/CPU/thread/fd trend to one
    // hiccup defeats a soak test more than the hiccup itself. Whatever was
    // collected up to the failure still prints, then the failure still
    // fails the test.
    pending = std::current_exception();
  }

  if (!sampler.samples().empty()) {
    sampler.print_report();
  }
  if (pending) {
    std::rethrow_exception(pending);
  }

  REQUIRE(cycle >= 3);

  // Trend-based, not exact: two real runs each showed num_fds take one
  // isolated step (a handful of fds, num_threads moving with it) on a single
  // cycle — once between cycle 0 and cycle 1 (consistent with some
  // process-wide resource finishing its warm-up on this process's very first
  // cycle), and separately on the very last cycle of a 90s/10-cycle run
  // (consistent with that cycle's sample catching the *previous* cycle's own
  // socket teardown still in flight — the same single-cycle artifact the
  // Python suite's README already documents for num_threads, not proven
  // exact for num_fds in this binding the way it was there). A strict
  // "never past its starting value" check has no tolerance for either kind
  // of one-off; the same median-backed trend check num_threads gets, just
  // below, absorbs both without hiding a real, sustained leak.
  endurance::assert_no_sustained_growth(
      [&] {
        std::vector<double> fds;
        fds.reserve(sampler.samples().size());
        for (const auto& s : sampler.samples()) {
          fds.push_back(static_cast<double>(s.num_fds));
        }
        return fds;
      }(),
      "num_fds", 0.15, 3.0, 0.2, /*use_median=*/true);
  endurance::assert_no_sustained_growth(
      [&] {
        std::vector<double> rss;
        rss.reserve(sampler.samples().size());
        for (const auto& s : sampler.samples()) {
          rss.push_back(static_cast<double>(s.rss_bytes));
        }
        return rss;
      }(),
      "rss_bytes", 0.15, 5'000'000);
  endurance::assert_no_sustained_growth(endurance::cpu_deltas(sampler.samples()), "cpu_s_per_cycle",
                                        0.5, 0.05);
  endurance::assert_no_sustained_growth(
      [&] {
        std::vector<double> pct;
        pct.reserve(sampler.samples().size());
        for (const auto& s : sampler.samples()) {
          pct.push_back(s.cpu_percent);
        }
        return pct;
      }(),
      "cpu_percent", 0.5, 5.0);
  // Trend-based, not exact like num_fds above — see helpers.hpp's own
  // comment on assert_no_sustained_growth's use_median parameter: native
  // thread teardown isn't guaranteed synchronous the way an fd close is, so
  // some cycles catch a previous cycle's worker mid-exit.
  endurance::assert_no_sustained_growth(
      [&] {
        std::vector<double> threads;
        threads.reserve(sampler.samples().size());
        for (const auto& s : sampler.samples()) {
          threads.push_back(static_cast<double>(s.num_threads));
        }
        return threads;
      }(),
      "num_threads", 0.15, 4.0, 0.2, /*use_median=*/true);
}
