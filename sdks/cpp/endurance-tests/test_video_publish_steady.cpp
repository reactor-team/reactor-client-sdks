// Endurance: publish a single video track once and hold it, continuously
// pushing frames at a steady rate for the whole run — no pause, no
// unpublish, no reconnect, no other operation in between. Mirrors
// sdks/python/endurance-tests/tests/test_video_publish_steady.py.
//
// Every other scenario in this suite is *churn* — repeatedly doing and
// undoing something to surface a leak in that specific operation. This one
// is the opposite shape on purpose: a real long call looks like "publish
// once, stream for a long time," not "publish and unpublish thousands of
// times a minute" — so this is the scenario for a leak that only shows up
// under sustained steady-state streaming (an encoder buffer that grows with
// elapsed time or frame count rather than with churn count, say), which the
// churn scenarios would not be positioned to catch even if they ran
// forever.
//
// Manual-only for now (see ../README.md). Run with:
//
//     ENDURANCE_DURATION_SECONDS=3600 mise run test:cpp:endurance-tests

#include <chrono>
#include <exception>
#include <optional>
#include <reactor/version.hpp>
#include <string>
#include <thread>

#include <catch2/catch_test_macros.hpp>

#include "fixtures.hpp"
#include "helpers.hpp"
#include "report.hpp"
#include "trends.hpp"

namespace {
constexpr std::uint32_t WIDTH = 64;
constexpr std::uint32_t HEIGHT = 64;
constexpr int FPS = 30;
// reactor/echo declares a fixed set of track names — same sendonly slot the
// churn scenarios publish, held open here instead of republished every
// cycle.
const char* const TRACK_NAME = "webcam";

const char* const DESCRIPTION =
    "One video track, published once and held for the whole run: continuous "
    "push_frame() at 30 fps, no pause, no unpublish, no reconnect — the "
    "steady-state shape a real long call actually takes, as opposed to the "
    "other scenarios' repeated setup/teardown.";
}  // namespace

TEST_CASE("steady video publish has no sustained resource growth") {
  endurance::ResourceSampler sampler;
  endurance::LiveReporter live("video-publish-steady", endurance::endurance_duration_seconds());
  const auto frame = integration::solid_bgra_frame(WIDTH, HEIGHT, 90, 140, 200);
  int iteration = 0;
  std::exception_ptr pending;
  std::vector<endurance::MetricResult> metrics;
  const std::string started_at = endurance::now_iso();
  // Constructed *inside* the try block below, not as a plain local declared
  // here: `ConnectedReactor`'s constructor is what actually connects, and a
  // connect failure there has to reach the catch block like any other
  // failure in this run, or finish_and_check() never runs and this
  // scenario silently writes no report at all — caught by actually running
  // this suite with no API key configured.
  std::optional<integration::ConnectedReactor> reactor_holder;
  // Empty until publish() below succeeds — guards the unpublish() at the end
  // against a publish that never got that far.
  std::optional<reactor::Track> track;

  try {
    reactor_holder.emplace();
    auto& reactor = *reactor_holder;
    track = reactor->track(TRACK_NAME);
    track->publish().get();

    while (!sampler.deadline_reached()) {
      // One second of continuous streaming, then one sample — a cycle here
      // is "how much streaming happened between samples," not a discrete
      // operation the way the other scenarios' cycles are. Keeps the sample
      // count (and so the JSON report size) bounded to roughly one per
      // second of ENDURANCE_DURATION_SECONDS, rather than one per
      // push_frame() call.
      for (int i = 0; i < FPS; ++i) {
        track->push_frame(reactor::Bytes{frame.data(), frame.size()}, WIDTH, HEIGHT);
        std::this_thread::sleep_for(std::chrono::duration<double>(1.0 / FPS));
      }

      sampler.sample(iteration);
      live.update(sampler.samples());
      ++iteration;
    }
  } catch (...) {
    pending = std::current_exception();
  }

  if (!pending) {
    if (iteration < 3) {
      pending = std::make_exception_ptr(std::runtime_error(
          "only completed " + std::to_string(iteration) +
          " second(s) of streaming — raise ENDURANCE_DURATION_SECONDS to get enough data "
          "for a trend"));
    } else {
      // live_clients baseline is 1 (the fixture's one client, connected and
      // publishing for the whole run) — same reasoning as the other
      // long-lived-session scenarios; this binding has no live_clients
      // check at all (see ../README.md).
      metrics = endurance::standard_resource_metrics(sampler.samples());
    }
  }

  if (track.has_value()) {
    try {
      track->unpublish();
    } catch (...) {
      // Best-effort: a scenario ending on its own exception (or already
      // past the deadline) shouldn't have that teardown call replace the
      // real failure.
    }
  }

  endurance::FinishAndCheckArgs args;
  args.test_name = "video-publish-steady";
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
