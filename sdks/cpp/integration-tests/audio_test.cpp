// The audio data plane — push_audio/on_audio — new to this suite, not
// mirrored from Python or JS: neither of those suites' own scenarios cover it
// either, and it was skipped here too until asked whether it should be.
//
// `reactor::sdk_audio` (Speaker/Microphone, real device I/O via miniaudio) is
// deliberately out of scope: the core is pinned to the synthetic ADM
// (sdk-from-ffi skill's "Audio devices" section), so a real microphone or
// speaker can never reach the wire regardless, and this suite's own build
// disables that optional module entirely (see mise.toml's
// REACTOR_SDK_BUILD_AUDIO=OFF). What *is* testable without any hardware is
// Track::push_audio/on_audio itself — synthetic PCM in, synthetic PCM out —
// the same way the rest of this suite pushes synthetic BGRA rather than
// reading a webcam.
//
// reactor/echo declares `mic: Audio` (sendonly) and `main_audio: Audio`
// (recvonly) — see echo_model.py's EchoInput/EchoOutput — and passes audio
// through unchanged (no effect applies to it, unlike main_video). Its own
// tick loop only advances on a `webcam` read, though: `main_audio` is never
// emitted unless `webcam` is also being pumped, regardless of whether `mic`
// has anything queued. Every test here publishes and pumps both for that
// reason, even the ones that only assert on audio.

#include <atomic>
#include <cmath>
#include <cstdint>
#include <reactor/errors.hpp>
#include <reactor/reactor.hpp>
#include <vector>

#include <catch2/catch_test_macros.hpp>

#include "fixtures.hpp"

namespace {
constexpr std::uint32_t WIDTH = 64;
constexpr std::uint32_t HEIGHT = 64;
}  // namespace

TEST_CASE("publish(mic) + push_audio reaches main_audio") {
  integration::ConnectedReactor reactor;
  auto webcam = reactor->track("webcam");
  webcam.publish().get();
  auto mic = reactor->track("mic");
  mic.publish().get();

  const auto bgra = integration::solid_bgra_frame(WIDTH, HEIGHT, 30, 30, 30);
  integration::FramePump video_pump{webcam, bgra, WIDTH, HEIGHT};
  integration::AudioPump audio_pump{mic};

  auto main_audio = reactor->track("main_audio");
  std::atomic<int> audible_chunks{0};
  auto subscription = main_audio.on_audio([&](const reactor::AudioFrame& frame) {
    if (frame.num_samples == 0) {
      return;
    }
    double sum_abs = 0.0;
    for (std::uint32_t i = 0; i < frame.num_samples; ++i) {
      sum_abs += std::abs(static_cast<double>(frame.samples[i]));
    }
    const double mean_abs = sum_abs / frame.num_samples;
    // A real tone's mean absolute amplitude sits well above digital silence
    // (near-zero); no attempt to match the pushed tone's exact amplitude —
    // this has gone through a real Opus encode/decode round trip, the audio
    // equivalent of the video suite's colour-tolerance assertions.
    if (mean_abs > 50.0) {
      ++audible_chunks;
    }
  });

  integration::wait_until([&] { return audible_chunks.load() >= 3; }, 10.0);

  video_pump.check();
  audio_pump.check();
  mic.unpublish();
  webcam.unpublish();
}

TEST_CASE("pushing audio before publish() raises InvalidStateError") {
  integration::ConnectedReactor reactor;
  auto mic = reactor->track("mic");

  const std::vector<std::int16_t> pcm(960, 1000);  // 20ms @ 48kHz mono
  REQUIRE_THROWS_AS(mic.push_audio(reactor::Samples{pcm.data(), pcm.size()}, 48'000, 1),
                    reactor::InvalidStateError);
}

TEST_CASE("on_audio on a video track is refused") {
  integration::ConnectedReactor reactor;
  auto webcam = reactor->track("webcam");  // video kind
  REQUIRE_THROWS_AS(webcam.on_audio([](const reactor::AudioFrame&) {}), reactor::InvalidStateError);
}

TEST_CASE("on_frame on an audio track is refused") {
  integration::ConnectedReactor reactor;
  auto mic = reactor->track("mic");  // audio kind
  REQUIRE_THROWS_AS(mic.on_frame([](const reactor::VideoFrame&) {}), reactor::InvalidStateError);
}

TEST_CASE("push_audio with a sample count that doesn't divide evenly by channels is refused") {
  // track.hpp's own docs state the requirement ("pcm.size must divide evenly
  // by channels") without saying what happens if it doesn't — probed here
  // rather than assumed, the same way the wrong-length BGRA buffer is probed
  // in refusals_and_edge_cases_test.cpp.
  integration::ConnectedReactor reactor;
  auto mic = reactor->track("mic");
  mic.publish().get();

  const std::vector<std::int16_t> odd_pcm(961, 1000);  // 961 does not divide by 2
  REQUIRE_THROWS_AS(mic.push_audio(reactor::Samples{odd_pcm.data(), odd_pcm.size()}, 48'000, 2),
                    reactor::BadRequestError);

  mic.unpublish();
}
