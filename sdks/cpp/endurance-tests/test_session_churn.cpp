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
#include <optional>
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
// reactor/echo declares a fixed set of track names (see echo_model.py) —
// there is no such thing as a fresh per-iteration name to publish, unlike a
// client handle, which is why this scenario republishes the same "webcam"
// slot every iteration instead of test_lifecycle_churn.cpp's
// fresh-object-per-cycle shape.
const char* const TRACK_NAME = "webcam";

const char* const DESCRIPTION =
    "One long-lived session (connects once): repeated publish, push_frame, "
    "command, unpublish cycles against it. No reconnect overhead, so it packs "
    "far more iterations per run than lifecycle-churn.";
}  // namespace

TEST_CASE("session churn has no sustained resource growth") {
  endurance::ResourceSampler sampler;
  endurance::LiveReporter live("session-churn", endurance::endurance_duration_seconds());
  const auto frame = integration::solid_bgra_frame(WIDTH, HEIGHT, 30, 150, 90);
  int iteration = 0;
  std::exception_ptr pending;
  const std::string started_at = endurance::now_iso();
  // Constructed *inside* the try block, not as a plain local declared before
  // it: `ConnectedReactor`'s constructor is what actually connects (via
  // `paced_connect()`, not `connect_with_retries()` — unlike
  // test_lifecycle_churn.cpp, this scenario only ever connects here, once,
  // at the very start), and a connect failure there has to reach the catch
  // block below like any other failure in this run, or finish_and_check()
  // below never runs and this scenario silently writes no report at all —
  // caught by actually running this suite with no API key configured.
  std::optional<integration::ConnectedReactor> client_holder;

  try {
    client_holder.emplace();
    auto& reactor = *client_holder;
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
      live.update(sampler.samples());
      ++iteration;
    }
  } catch (...) {
    // Same reasoning as test_lifecycle_churn.cpp's own catch: a transient
    // failure mid-run shouldn't cost the whole accumulated trend, which is
    // the one thing a soak test actually exists to show.
    pending = std::current_exception();
  }

  std::vector<endurance::MetricResult> metrics;
  if (!pending) {
    if (iteration < 3) {
      pending = std::make_exception_ptr(std::runtime_error(
          "only completed " + std::to_string(iteration) +
          " iteration(s) — raise ENDURANCE_DURATION_SECONDS to get enough data for a trend"));
    } else {
      // No pending_completions/Track._adapters exact-emptiness checks here,
      // unlike the Python suite's identical scenario — see ../README.md's
      // "known, deliberate scope gap" for why: a std::future either gets
      // .get()'d (as every call above does) or its destructor blocks until
      // the operation completes, so there is no "still pending after the
      // loop moved on" state possible in the first place.
      //
      // `iteration < 3` above is a floor on completed iterations, not on
      // the sample count each metric actually needs — cpu_deltas() alone
      // drops one value relative to the raw samples, so a run landing at
      // exactly 3-6 iterations can still be short of what
      // assert_no_sustained_growth requires and throw here. Caught the
      // same way the run loop's own failures are, so a short run still
      // gets an ERROR report instead of an uncaught exception skipping
      // finish_and_check entirely.
      try {
        metrics = endurance::standard_resource_metrics(sampler.samples());
      } catch (...) {
        pending = std::current_exception();
      }
    }
  }

  endurance::FinishAndCheckArgs args;
  args.test_name = "session-churn";
  args.sdk = "C++";
  args.description = DESCRIPTION;
  args.sdk_version = std::string(reactor::version());
  args.duration_s = endurance::endurance_duration_seconds();
  args.started_at = started_at;
  args.metrics = metrics;
  args.iterations = iteration;
  args.run_error = pending;
  endurance::finish_and_check(sampler.samples(), live, args);

  if (pending) {
    std::rethrow_exception(pending);
  }
}
