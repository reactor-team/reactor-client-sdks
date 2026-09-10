// Endurance: one long-lived session, repeated publish/push_frame/command/
// unpublish cycles against it. Mirrors
// sdks/python/endurance-tests/tests/test_session_churn.py.
//
// Manual-only for now (see ../README.md). Run with:
//
//     ENDURANCE_DURATION_SECONDS=3600 mise run test:cpp:endurance-tests
//
// No reconnect overhead here (unlike test_lifecycle_churn.cpp), so this
// packs far more iterations into the same wall-clock window — the scenario
// for per-operation leaks (frame buffers, Track objects) that a coarser
// connect/disconnect cycle wouldn't surface as clearly.

#include <atomic>
#include <exception>

#include <catch2/catch_test_macros.hpp>

#include "fixtures.hpp"
#include "helpers.hpp"

namespace {
constexpr std::uint32_t WIDTH = 64;
constexpr std::uint32_t HEIGHT = 64;
// reactor/echo declares a fixed set of track names (see echo_model.py) —
// there is no such thing as a fresh per-iteration name to publish, unlike a
// client handle, which is why this scenario republishes the same "webcam"
// slot every iteration instead of test_lifecycle_churn.cpp's
// fresh-object-per-cycle shape.
const char* const TRACK_NAME = "webcam";
}  // namespace

TEST_CASE("session churn has no sustained resource growth") {
  endurance::ResourceSampler sampler;
  const auto frame = integration::solid_bgra_frame(WIDTH, HEIGHT, 30, 150, 90);
  // `ConnectedReactor` connects once via plain paced_connect(), not
  // connect_with_retries() — unlike test_lifecycle_churn.cpp, this scenario
  // only ever connects here, once, at the very start, so a transient
  // coordinator-side blip has one narrow window to land in rather than
  // hundreds of chances over the run.
  integration::ConnectedReactor reactor;
  int iteration = 0;
  std::exception_ptr pending;

  try {
    while (!sampler.deadline_reached()) {
      // Registered and torn down every iteration — unlike
      // test_lifecycle_churn.cpp, which registers once per client and lets
      // its destructor clean it up, this is the scenario for repeated
      // subscribe/unsubscribe on a *long-lived* track: does the on_frame/
      // remove() pair leak across many cycles on the same session, not just
      // survive one client's teardown.
      std::atomic<bool> received{false};
      auto main_video = reactor->track("main_video");
      auto subscription =
          main_video.on_frame([&](const reactor::VideoFrame&) { received.store(true); });

      auto track = reactor->track(TRACK_NAME);
      track.publish().get();
      endurance::pump_until_frame_received(track, frame, WIDTH, HEIGHT, received);
      reactor->send_command("set_effect", {{"effect", "invert"}}).get();
      track.unpublish();
      // Explicit, not left to scope exit: mirrors the Python suite's
      // explicit off_frame() call, and matters for the same reason —
      // Subscription's destructor would unregister it too, but only at the
      // end of this whole TEST_CASE, not once per iteration, which is
      // exactly the repeated-teardown path this scenario means to exercise.
      subscription.remove();

      sampler.sample(iteration);
      ++iteration;
    }
  } catch (...) {
    // Same reasoning as test_lifecycle_churn.cpp's own catch: a transient
    // failure mid-run shouldn't cost the whole accumulated trend, which is
    // the one thing a soak test actually exists to show.
    pending = std::current_exception();
  }

  if (!sampler.samples().empty()) {
    sampler.print_report();
  }
  if (pending) {
    std::rethrow_exception(pending);
  }

  REQUIRE(iteration >= 3);

  // Trend-based, not exact — see test_lifecycle_churn.cpp's own comment on
  // its identical check for why: two real runs of that scenario each showed
  // num_fds take one isolated step on a single cycle, not proven exact in
  // this binding the way it was in the Python suite's own real run.
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
  // Same reasoning as test_lifecycle_churn.cpp's own identical call: one
  // long-lived session can legitimately grow a thread or two during warm-up
  // and then plateau, so this is trend-based (with the wider median-backed
  // window), not a strict "never past the first sample" check.
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
