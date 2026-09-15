// Endurance: one long-lived session, repeated pause/resume churn on a
// recvonly track — no frames, no commands, no reconnect. Mirrors
// sdks/python/endurance-tests/tests/test_pause_resume_churn.py.
//
// Isolates `Track::pause()`/`resume()` as its own scenario rather than
// folding it into test_session_churn.cpp's already-mixed publish/frame/
// command loop — a leak specific to the pause/resume path (e.g. the native
// session-side pause state not fully clearing) should read as its own
// signal. This is the scenario the Python suite's own first real CI run
// found a genuine, linear, no-plateau RSS climb on (+50% over 5 minutes)
// that publish-churn — same run, same process — did not show at all;
// folded into a broader mix that signal would have read as "RSS grew a bit,
// inconclusive" instead of naming pause/resume outright.
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
// The recvonly track reactor/echo always declares — the same one
// test_lifecycle_churn.cpp/test_session_churn.cpp already subscribe to for
// on_frame, chosen here for the same reason: no need to invent a name.
const char* const TRACK_NAME = "main_video";

const char* const DESCRIPTION =
    "One long-lived session: repeated pause/resume on a recvonly track. No "
    "frames, no commands, no reconnect — isolates Track::pause()/resume() as "
    "its own signal.";
}  // namespace

TEST_CASE("pause/resume churn has no sustained resource growth") {
  endurance::ResourceSampler sampler;
  endurance::LiveReporter live("pause-resume-churn", endurance::endurance_duration_seconds());
  int iteration = 0;
  std::exception_ptr pending;
  std::vector<endurance::MetricResult> metrics;
  const std::string started_at = endurance::now_iso();
  // Both constructed *inside* the try block below, not as plain locals
  // declared here: `ConnectedReactor`'s constructor is what actually
  // connects, and a connect failure there has to reach the catch block like
  // any other failure in this run, or finish_and_check() never runs and
  // this scenario silently writes no report at all — caught by actually
  // running this suite with no API key configured.
  std::optional<integration::ConnectedReactor> client_holder;
  std::optional<reactor::Track> track;

  // Same reasoning as test_publish_churn.cpp's own flag.
  bool invariant_violated = false;

  try {
    client_holder.emplace();
    auto& reactor = *client_holder;
    // Resolved once, up front: kind()/direction() are local, synchronous
    // lookups against the session's already-declared capabilities (see
    // Reactor::track()), so this is safe outside the loop rather than on
    // every iteration.
    track = reactor->track(TRACK_NAME);
    while (!sampler.deadline_reached()) {
      track->pause().get();
      track->resume().get();

      // Exact, not trend-based: resume() above already returned, so the
      // track should report not-paused on every single iteration — read
      // fresh from the session (see Track::paused()'s own docs), not
      // cached, so a leftover true here is a real leftover pause state.
      if (track->paused()) {
        endurance::MetricResult result;
        result.name = "track_paused_after_resume";
        result.start = 0;
        result.end = 1;
        result.change = 1;
        result.unit = "count";
        result.status = "fail";
        result.detail = std::string(TRACK_NAME) +
                        " still reports paused=true after resume() "
                        "on iteration " +
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
      // The fixture's one client is connected for the whole test — same
      // reasoning as the other long-lived-session scenarios.
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
  args.test_name = "pause-resume-churn";
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
