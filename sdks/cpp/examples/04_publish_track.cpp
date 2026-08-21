// 04 — Publish an input track and push frames into it.
//
// Publishing is what puts a sender behind the slot: a push before it is dropped,
// and this SDK refuses rather than letting that happen quietly. X2 edits the
// track it is given as soon as it has a prompt — there is no `start`.
//
// The frames pushed here are a moving gradient rather than a camera, so the
// example needs no capture device and still shows the model something changing.
//
//   export REACTOR_API_KEY=...
//   ./04_publish_track
//
// Docs: https://docs.reactor.inc/concepts/tracks

#include <atomic>
#include <chrono>
#include <cstdlib>
#include <iostream>
#include <reactor/reactor.hpp>
#include <string>
#include <thread>
#include <vector>

#include "display.hpp"

namespace {

// X2 takes an input track, which Helios does not — hence a different model, and
// the owner spelled out.
constexpr auto MODEL = "xmax/x2";
constexpr std::uint32_t WIDTH = 512;
constexpr std::uint32_t HEIGHT = 512;

/// A frame that changes every time, so the model has motion to work with.
void paint(std::vector<std::uint8_t>& bgra, int tick) {
  for (std::uint32_t y = 0; y < HEIGHT; ++y) {
    for (std::uint32_t x = 0; x < WIDTH; ++x) {
      const std::size_t index = (static_cast<std::size_t>(y) * WIDTH + x) * 4U;
      bgra[index + 0] = static_cast<std::uint8_t>((x + tick * 3) & 0xFF);  // B
      bgra[index + 1] = static_cast<std::uint8_t>((y + tick * 2) & 0xFF);  // G
      bgra[index + 2] = static_cast<std::uint8_t>((x + y + tick) & 0xFF);  // R
      bgra[index + 3] = 0xFF;                                              // A
    }
  }
}

}  // namespace

int main() {
  const char* api_key = std::getenv("REACTOR_API_KEY");
  if (api_key == nullptr) {
    std::cerr << "set REACTOR_API_KEY\n";
    return 2;
  }

  reactor::Reactor client{MODEL, reactor::ApiKey{api_key}};
  auto errors = client.on_error(
      [](const reactor::ReactorError& error) { std::cerr << "error: " << error.what() << '\n'; });
  auto messages = client.on_message([](const reactor::Json& message) {
    if (message.value("type", std::string{}) == "command_error") {
      std::cerr << "the model refused a command: " << message.dump() << '\n';
    }
  });

  try {
    client.connect().get();
    std::cout << "session " << client.session_id().value_or("?") << " is ready\n";

    auto source = client.track("source");
    auto output = client.track("main_video");

    std::atomic<int> received{0};
    examples::Display display{"04 — the edited output"};
    auto subscription = output.on_frame([&](const reactor::VideoFrame& frame) {
      ++received;
      display.show(frame);
    });

    // Before this, a push is refused: there is no sender behind the slot, and the
    // FFI would take the frame and drop it.
    source.publish().get();
    std::cout << "published \"source\" (published=" << std::boolalpha << source.published()
              << ")\n";

    client.send_command("set_prompt", {{"prompt", "make the subject a bronze statue"}}).get();

    std::vector<std::uint8_t> bgra(static_cast<std::size_t>(WIDTH) * HEIGHT * 4U);
    int pushed = 0;
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(12);
    while (std::chrono::steady_clock::now() < deadline && display.pump()) {
      paint(bgra, pushed);

      reactor::Track::FrameOptions options;
      // One read per unit of produced media, shared by every track of that
      // capture. With one track it is the same thing; with two it is the only way
      // the far end reads them as one moment.
      const auto captured_at = reactor::time_micros();
      options.capture_time_us = captured_at;
      // Bytes, not text: what they mean is between this example and the model, and
      // a peer that does not read tags drops them without complaint.
      const std::string tag = "frame=" + std::to_string(pushed);
      options.user_data =
          reactor::Bytes{reinterpret_cast<const std::uint8_t*>(tag.data()), tag.size()};

      source.push_frame(reactor::Bytes{bgra.data(), bgra.size()}, WIDTH, HEIGHT, options);
      ++pushed;
      std::this_thread::sleep_for(std::chrono::milliseconds(40));  // ~25fps
    }

    std::cout << "pushed " << pushed << " frames, received " << received.load() << " back\n";
    source.unpublish();
    client.disconnect().get();
    return received.load() > 0 ? 0 : 1;
  } catch (const reactor::ReactorError& error) {
    std::cerr << "failed: " << error.what() << '\n';
    try {
      client.disconnect().get();
    } catch (const reactor::ReactorError&) {
    }
    return 1;
  }
}
