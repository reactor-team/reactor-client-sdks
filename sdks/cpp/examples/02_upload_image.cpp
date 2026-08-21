// 02 — Upload a file, then pass the reference into a command.
//
// The bytes cross the wire once: `upload_file` hands them to the platform and
// returns a `FileRef`, and the command carries the *reference*. Helios takes a
// prompt and an image together through `set_conditioning`, so the two arrive as
// one atomic change rather than as two the model has to reconcile.
//
//   export REACTOR_API_KEY=...
//   ./02_upload_image path/to/image.png
//
// Docs: https://docs.reactor.inc/concepts/file-uploads

#include <atomic>
#include <chrono>
#include <cstdlib>
#include <iostream>
#include <reactor/reactor.hpp>
#include <string>
#include <thread>

#include "display.hpp"

namespace {

constexpr auto MODEL = "reactor/helios";
constexpr auto PROMPT = "the same scene at golden hour, cinematic";

}  // namespace

int main(int argc, char** argv) {
  const char* api_key = std::getenv("REACTOR_API_KEY");
  if (api_key == nullptr) {
    std::cerr << "set REACTOR_API_KEY\n";
    return 2;
  }
  if (argc != 2) {
    std::cerr << "usage: " << argv[0] << " <image>\n";
    return 2;
  }
  const std::string image = argv[1];

  reactor::Reactor client{MODEL, reactor::ApiKey{api_key}};

  // A refused upload arrives as `command_error` from the model, not as a failed
  // call: the platform accepted the file, and the model declined to use it.
  auto messages = client.on_message([](const reactor::Json& message) {
    if (message.value("type", std::string{}) == "command_error") {
      std::cerr << "the model refused a command: " << message.dump() << '\n';
    }
  });
  auto errors = client.on_error(
      [](const reactor::ReactorError& error) { std::cerr << "error: " << error.what() << '\n'; });

  try {
    client.connect().get();
    std::cout << "session " << client.session_id().value_or("?") << " is ready\n";

    const auto uploaded = client.upload_file(image).get();
    std::cout << "uploaded: " << uploaded.name << ' ' << uploaded.mime_type << " (" << uploaded.size
              << " bytes)\n";

    // The reference goes in as a named upload, not as an argument: the platform
    // resolves it on its side.
    client.send_command("set_conditioning", {{"prompt", PROMPT}}, {{"image", uploaded}}).get();
    client.send_command("start").get();

    std::atomic<int> frames{0};
    examples::Display display{std::string{"02 — "} + uploaded.name};
    auto video = client.track("main_video");
    auto subscription = video.on_frame([&](const reactor::VideoFrame& frame) {
      ++frames;
      display.show(frame);
    });

    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(10);
    while (std::chrono::steady_clock::now() < deadline && display.pump()) {
      std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }

    std::cout << "received " << frames.load() << " frames conditioned on " << uploaded.name << '\n';
    client.disconnect().get();
    return frames.load() > 0 ? 0 : 1;
  } catch (const reactor::ReactorError& error) {
    std::cerr << "failed: " << error.what() << '\n';
    try {
      client.disconnect().get();
    } catch (const reactor::ReactorError&) {
    }
    return 1;
  }
}
