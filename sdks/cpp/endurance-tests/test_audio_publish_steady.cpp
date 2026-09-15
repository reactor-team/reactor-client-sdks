// Endurance: publish a single audio track once and hold it, continuously
// pushing PCM chunks at a steady rate for the whole run — no pause, no
// unpublish, no reconnect, no other operation in between. Mirrors
// sdks/python/endurance-tests/tests/test_audio_publish_steady.py.
//
// Same steady-state shape as test_video_publish_steady.cpp, mirrored for
// audio rather than parameterizing one scenario over both — the two paths
// share nothing below push_frame() (separate adapters, separate
// encoder/decoder threads in the native runtime), so a leak in one is not
// evidence about the other, and a report that says "audio-publish-steady
// failed" is more useful standing on its own than folded into a single
// parameterized scenario.
//
// Manual-only for now (see ../README.md). Run with:
//
//     ENDURANCE_DURATION_SECONDS=3600 mise run test:cpp:endurance-tests

#include <chrono>
#include <cstdint>
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
constexpr int FPS = 30;
constexpr std::uint32_t SAMPLE_RATE = 48'000;
constexpr std::uint32_t NUM_CHANNELS = 1;
constexpr std::size_t CHUNK_SAMPLES = SAMPLE_RATE / FPS;  // one chunk per tick
// reactor/echo declares a fixed set of track names — the sendonly audio
// slot, held open here instead of republished every cycle.
const char* const TRACK_NAME = "mic";

const char* const DESCRIPTION =
    "One audio track, published once and held for the whole run: continuous "
    "push_frame() PCM chunks at 30/s (48000 Hz), no pause, no unpublish, no "
    "reconnect — isolates the audio send path (separate from video's) in the "
    "same steady-state shape as video-publish-steady.";
}  // namespace

TEST_CASE("steady audio publish has no sustained resource growth") {
  endurance::ResourceSampler sampler;
  endurance::LiveReporter live("audio-publish-steady", endurance::endurance_duration_seconds());
  int iteration = 0;
  std::exception_ptr pending;
  std::vector<endurance::MetricResult> metrics;
  const std::string started_at = endurance::now_iso();
  // Constructed *inside* the try block below, not as a plain local declared
  // here — same reasoning as test_video_publish_steady.cpp's identical
  // comment: a connect failure has to reach the catch block below or
  // finish_and_check() never runs.
  std::optional<integration::ConnectedReactor> client_holder;
  std::optional<reactor::Track> track;
  double phase = 0.0;

  try {
    client_holder.emplace();
    auto& reactor = *client_holder;
    track = reactor->track(TRACK_NAME);
    track->publish().get();

    while (!sampler.deadline_reached()) {
      // One second of continuous streaming, then one sample — same
      // reasoning as video-publish-steady's identical shape.
      for (int i = 0; i < FPS; ++i) {
        const auto chunk =
            endurance::sine_wave_chunk(CHUNK_SAMPLES, NUM_CHANNELS, SAMPLE_RATE, phase);
        track->push_frame(reactor::Samples{chunk.data(), chunk.size()}, SAMPLE_RATE, NUM_CHANNELS);
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
      metrics = endurance::standard_resource_metrics(sampler.samples());
    }
  }

  if (track.has_value()) {
    try {
      track->unpublish();
    } catch (...) {
      // Best-effort — see test_video_publish_steady.cpp's identical comment.
    }
  }

  endurance::FinishAndCheckArgs args;
  args.test_name = "audio-publish-steady";
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
