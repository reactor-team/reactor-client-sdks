// Stepped generation against a real runtime: `Options::generation = Stepped`,
// `step()`, and the codec its video step frames travel in.
//
// Mirrors sdks/python/integration-tests/tests/test_stepped_generation.py's echo
// tests. `reactor/echo` (MODEL_NAME) steps from 1.10.0: a step carries the
// pushed webcam frame and comes back inverted on main_video. An older echo
// drives its own run(), which a runtime from 3.9.0 refuses to start stepped
// (SESSION_TERMINAL naming START_REFUSED), and a runtime before 3.9.0 streams
// instead, which the client reports as a mode mismatch. Either refusal is
// accepted, as the Python, Swift and Android suites accept them; a stepped
// connect that silently streams is not.

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstdlib>
#include <map>
#include <optional>
#include <reactor/errors.hpp>
#include <reactor/reactor.hpp>
#include <stdexcept>
#include <string>
#include <vector>

#include <catch2/catch_test_macros.hpp>

#include "fixtures.hpp"

namespace {

using integration::API_KEY;
using integration::API_URL;
using integration::LOCAL;
using integration::MODEL_NAME;

constexpr std::uint32_t SIZE = 64;

/// A stepped client configured from this suite's env, not yet connected.
reactor::Reactor stepped_reactor() {
  reactor::Options options;
  options.api_url = API_URL;
  options.local = LOCAL;
  options.generation = reactor::Generation::Stepped;
  if (LOCAL) {
    return reactor::Reactor{MODEL_NAME, options};
  }
  if (API_KEY.empty()) {
    throw std::runtime_error(
        "INTEGRATION_TESTS_REACTOR_API_KEY is required unless REACTOR_LOCAL=true — see "
        "sdks/cpp/integration-tests/README.md.");
  }
  return reactor::Reactor{MODEL_NAME, reactor::ApiKey{API_KEY}, options};
}

/// Connect, or return false for a runtime or an echo that does not step.
bool connect_or_refused(reactor::Reactor& client) {
  try {
    integration::paced_connect(client);
    return true;
  } catch (const reactor::GenerationModeMismatchError&) {
    return false;
  } catch (const reactor::SessionTerminalError& refused) {
    INFO(refused.what());
    CHECK(std::string{refused.what()}.find("(START_REFUSED)") != std::string::npos);
    return false;
  }
}

/// Disconnects a connected client on the way out, whatever the test did.
class DisconnectOnExit {
 public:
  explicit DisconnectOnExit(reactor::Reactor& client) : client_(client) {}
  ~DisconnectOnExit() {
    if (!connected) {
      return;
    }
    try {
      client_.disconnect().get();
    } catch (const reactor::ReactorError&) {  // NOLINT(bugprone-empty-catch)
      // Best effort, as in ReactorFactory's teardown.
    }
  }
  DisconnectOnExit(const DisconnectOnExit&) = delete;
  DisconnectOnExit& operator=(const DisconnectOnExit&) = delete;
  DisconnectOnExit(DisconnectOnExit&&) = delete;
  DisconnectOnExit& operator=(DisconnectOnExit&&) = delete;

  bool connected = false;

 private:
  reactor::Reactor& client_;
};

std::vector<std::uint8_t> solid_rgb(std::uint8_t r, std::uint8_t g, std::uint8_t b) {
  std::vector<std::uint8_t> rgb(static_cast<std::size_t>(SIZE) * SIZE * 3U);
  for (std::size_t i = 0; i < rgb.size(); i += 3) {
    rgb[i + 0] = r;
    rgb[i + 1] = g;
    rgb[i + 2] = b;
  }
  return rgb;
}

/// The one main_video frame of `result` is 64x64 RGB, its centre pixel within
/// `tolerance` per channel of (245, 235, 225): (10, 20, 30) inverted.
void check_inverted(const reactor::StepResult& result, int tolerance) {
  CHECK_FALSE(result.error.has_value());
  const auto& frames = result.track("main_video").frames;
  REQUIRE(frames.size() == 1);
  const auto& frame = frames.front();
  REQUIRE(frame.shape == std::vector<std::uint32_t>{SIZE, SIZE, 3});
  REQUIRE(frame.data.size() == static_cast<std::size_t>(SIZE) * SIZE * 3U);
  const std::size_t centre = ((static_cast<std::size_t>(SIZE / 2) * SIZE) + (SIZE / 2)) * 3U;
  const std::array<int, 3> expected{245, 235, 225};
  for (std::size_t channel = 0; channel < 3; ++channel) {
    const int value = frame.data[centre + channel];
    INFO("channel " << channel << ": got " << value << ", expected " << expected.at(channel)
                    << " within " << tolerance << " (encoding " << frame.encoding << ")");
    CHECK(std::abs(value - expected.at(channel)) <= tolerance);
  }
}

/// The same echo round trip with the step frames' codec chosen: H.264 both
/// ways, or encoding turned off so every frame travels raw. The client pushes
/// and receives RGB either way.
void run_codec_round_trip(const std::vector<std::string>& codecs, const std::string& expected) {
  reactor::Reactor client = stepped_reactor();
  client.set_codec_preference(codecs);
  DisconnectOnExit guard{client};
  if (!connect_or_refused(client)) {
    return;
  }
  guard.connected = true;

  auto webcam = client.track("webcam");
  webcam.publish().get();

  const auto encodings = client.step_frame_encodings();
  std::string listed;
  for (const auto& [name, encoding] : encodings) {
    listed += name + "=" + encoding + " ";
  }
  INFO("step_frame_encodings: " << listed);
  if (expected != "raw" && std::all_of(encodings.begin(), encodings.end(),
                                       [](const auto& entry) { return entry.second == "raw"; })) {
    // A runtime from before encoded step frames answers without them, so
    // every track travels raw. Accepted, as an echo that does not step is,
    // until a runtime with them is deployed.
    SKIP("the runtime offers no encoded step frames: " << listed);
  }
  CHECK(encodings.count("main_video") == 1);
  if (encodings.count("main_video") == 1) {
    CHECK(encodings.at("main_video") == expected);
  }
  // A track that travels raw may be left out; missing reads as "raw".
  const auto webcam_encoding = encodings.find("webcam");
  CHECK((webcam_encoding == encodings.end() ? std::string{"raw"} : webcam_encoding->second) ==
        expected);
  if (expected == "raw") {
    CHECK(std::all_of(encodings.begin(), encodings.end(),
                      [](const auto& entry) { return entry.second == "raw"; }));
  }

  const int tolerance = expected == "raw" ? 0 : 8;
  const auto pixels = solid_rgb(10, 20, 30);
  webcam.push_frame(reactor::Bytes{pixels.data(), pixels.size()}, reactor::PixelFormat::Rgb, SIZE,
                    SIZE);
  const auto result = client.step({{"effect", "invert"}}).get();
  check_inverted(result, tolerance);

  // The next step's frames continue the same stream.
  webcam.push_frame(reactor::Bytes{pixels.data(), pixels.size()}, reactor::PixelFormat::Rgb, SIZE,
                    SIZE);
  const auto again = client.step().get();
  CHECK(again.step == result.step + 1);
  check_inverted(again, tolerance);
}

}  // namespace

TEST_CASE("a stepped connect to echo steps or is refused, never streams", "[stepped]") {
  reactor::Reactor client = stepped_reactor();
  DisconnectOnExit guard{client};
  if (!connect_or_refused(client)) {
    return;
  }
  guard.connected = true;
  CHECK(client.status() == reactor::Status::Ready);

  auto webcam = client.track("webcam");
  webcam.publish().get();
  const auto pixels = solid_rgb(10, 20, 30);
  webcam.push_frame(reactor::Bytes{pixels.data(), pixels.size()}, reactor::PixelFormat::Rgb, SIZE,
                    SIZE);
  // Encoded both ways by default (H.264 when both ends have it), so close, not exact.
  check_inverted(client.step({{"effect", "invert"}}).get(), 8);
}

TEST_CASE("a stepped session carries frames in the codec it chose: h264", "[stepped][codecs]") {
  run_codec_round_trip({"h264"}, "h264");
}

TEST_CASE("a stepped session carries frames in the codec it chose: encoding off",
          "[stepped][codecs]") {
  run_codec_round_trip({}, "raw");
}
