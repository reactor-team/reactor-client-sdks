// Endurance: one long-lived session, repeated publish/unpublish churn on a
// sendonly track slot — no frames, no commands, no reconnect. Mirrors
// sdks/python/endurance-tests/tests/test_publish_churn.py.
//
// Isolates the publish/unpublish slot-activation path itself
// (`Track::publish`/`unpublish`) from test_session_churn.cpp's broader mix
// (which also pushes frames and sends a command each iteration): a leak
// specific to *this* pair should read as its own clear signal instead of
// being folded into a trend that several different operations are all
// contributing to. Also packs in far more iterations per minute than
// session-churn, since there is no per-cycle frame-pump wait or command
// round trip.
//
// Manual-only for now (see ../README.md). Run with:
//
//     ENDURANCE_DURATION_SECONDS=3600 mise run test:cpp:endurance-tests

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
// reactor/echo declares a fixed set of track names — same reasoning as
// test_session_churn.cpp's own TRACK_NAME: republish the same slot every
// iteration rather than invent a fresh per-iteration name.
const char* const TRACK_NAME = "webcam";

const char* const DESCRIPTION =
    "One long-lived session: repeated publish/unpublish on a single sendonly "
    "slot. No frames, no commands, no reconnect — isolates the publish/unpublish "
    "path from session-churn's broader mix.";
}  // namespace

TEST_CASE("publish/unpublish churn has no sustained resource growth", "[publish_churn]") {
  endurance::ResourceSampler sampler;
  endurance::LiveReporter live("publish-churn", endurance::endurance_duration_seconds());
  int iteration = 0;
  std::exception_ptr pending;
  std::vector<endurance::MetricResult> metrics;
  const std::string started_at = endurance::now_iso();
  // Constructed *inside* the try block below, not as a plain local declared
  // here: `ConnectedReactor`'s constructor is what actually connects, and a
  // connect failure there has to reach the catch block like any other
  // failure in this run, or finish_and_check() never runs and this scenario
  // silently writes no report at all — caught by actually running this
  // suite with no API key configured.
  std::optional<integration::ConnectedReactor> client_holder;

  // Set on the first in-loop invariant violation below — breaks the loop
  // (further iterations' trend data would be unreliable against a corrupted
  // invariant) rather than letting the failure surface only as a thrown
  // exception, which report.hpp's finish_and_check() would otherwise read as
  // an "ERROR" (not a detected leak) instead of the "FAIL" this check
  // actually is.
  bool invariant_violated = false;

  try {
    client_holder.emplace();
    auto& reactor = *client_holder;
    while (!sampler.deadline_reached()) {
      auto track = reactor->track(TRACK_NAME);
      track.publish().get();
      track.unpublish();

      // Exact, not trend-based: unpublish() above already returned, so the
      // slot should report not-published on every single iteration — a
      // leftover true here means the SDK-side flag didn't clear even though
      // the cycle otherwise completed normally.
      if (track.published()) {
        endurance::MetricResult result;
        result.name = "track_published_after_unpublish";
        result.start = 0;
        result.end = 1;
        result.change = 1;
        result.unit = "count";
        result.status = "fail";
        result.detail = std::string(TRACK_NAME) +
                        " still reports published=true after "
                        "unpublish() on iteration " +
                        std::to_string(iteration);
        result.threshold = "always false";
        result.first_bad_cycle = iteration;
        metrics.push_back(result);
        invariant_violated = true;
        break;
      }

      sampler.sample(iteration);
      live.update(sampler.samples());
      ++iteration;
    }
  } catch (...) {
    pending = std::current_exception();
  }

  if (!invariant_violated && !pending) {
    if (iteration < 3) {
      pending = std::make_exception_ptr(std::runtime_error(
          "only completed " + std::to_string(iteration) +
          " iteration(s) — raise ENDURANCE_DURATION_SECONDS to get enough data for a trend"));
    } else {
      // The fixture's one client is connected for the whole test, so this
      // scenario's own invariant above is the only thing beyond the shared
      // resource metrics — same reasoning as test_session_churn.cpp.
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
        const auto shared = endurance::standard_resource_metrics(sampler.samples());
        metrics.insert(metrics.end(), shared.begin(), shared.end());
      } catch (...) {
        pending = std::current_exception();
      }
    }
  }

  endurance::FinishAndCheckArgs args;
  args.test_name = "publish-churn";
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
