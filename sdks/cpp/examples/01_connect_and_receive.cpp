// 01 — Connect, send the model's first command, read the reply, count frames.
//
// The spine every other example builds on: connect, wait for ready, give the
// model the minimum it needs, receive frames.
//
// "The minimum it needs" is per model and not optional. Helios stays silent until
// `set_prompt` and then `start` — its own schema is where that is written down,
// and the first place to look when nothing arrives.
//
//   export REACTOR_API_KEY=...
//   ./01_connect_and_receive
//
// Docs: https://docs.reactor.inc/model-api-reference/helios/schema

#include <atomic>
#include <chrono>
#include <cstdlib>
#include <iostream>
#include <reactor/reactor.hpp>
#include <thread>

#include "display.hpp"

namespace {

// `owner/name`, always. A bare name resolves under `reactor/`, so it works by
// luck of ownership and answers 403 for anyone else's model.
constexpr auto MODEL = "reactor/helios";

}  // namespace

int main() {
  const char* api_key = std::getenv("REACTOR_API_KEY");
  if (api_key == nullptr) {
    std::cerr << "set REACTOR_API_KEY (from https://reactor.inc dashboard)\n";
    return 2;
  }

  reactor::Reactor client{MODEL, reactor::ApiKey{api_key}};

  auto status = client.on_status(
      [](reactor::Status now) { std::cout << "status: " << reactor::to_string(now) << '\n'; });
  auto errors = client.on_error(
      [](const reactor::ReactorError& error) { std::cerr << "error: " << error.what() << '\n'; });

  try {
    std::cout << "connecting to " << MODEL << "...\n";
    client.connect().get();
    std::cout << "session " << client.session_id().value_or("?") << " is ready\n";

    // What the session declared. Worth printing once: it is the answer to "why is
    // nothing arriving" more often than anything else.
    for (const auto& track : client.tracks()) {
      std::cout << "  track " << track.name() << ": " << reactor::to_string(*track.kind()) << ' '
                << reactor::to_string(*track.direction()) << '\n';
    }

    std::atomic<int> frames{0};
    examples::Display display{"01 — connect and receive"};

    auto video = client.track("main_video");
    auto subscription = video.on_frame([&](const reactor::VideoFrame& frame) {
      // Inline, on the library's thread. Counting is free; anything slow here
      // costs frames, which is the deal.
      ++frames;
      display.show(frame);
    });

    // Helios' own minimum, in its own order.
    client.send_command("set_prompt", {{"prompt", "a red fox in tall grass, cinematic"}}).get();
    client.send_command("start").get();
    std::cout << "started; receiving for 10s\n";

    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(10);
    while (std::chrono::steady_clock::now() < deadline) {
      if (!display.pump()) {
        break;  // the window was closed
      }
      std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }

    std::cout << "received " << frames.load() << " frames\n";
    // A count proves something arrived, not that it was the right something —
    // REACTOR_SHOW=1 is how you check the second part.
    client.disconnect().get();
    std::cout << "disconnected\n";
    return frames.load() > 0 ? 0 : 1;
  } catch (const reactor::ReactorError& error) {
    std::cerr << "failed: " << error.what() << '\n';
    // Teardown regardless: a creator that goes away without disconnecting leaves
    // the session orphaned, and the next run cannot start until it clears.
    try {
      client.disconnect().get();
    } catch (const reactor::ReactorError&) {
    }
    return 1;
  }
}
