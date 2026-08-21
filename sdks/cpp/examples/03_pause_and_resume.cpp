// 03 — Pause a track, then resume it.
//
// Nothing is generated while a track is paused, and on video that is visible only
// as a frozen frame — so the thing to watch is the frame *rate*, printed each
// second. It goes to zero, then comes back.
//
//   export REACTOR_API_KEY=...
//   ./03_pause_and_resume
//
// Docs: https://docs.reactor.inc/concepts/tracks

#include <atomic>
#include <chrono>
#include <cstdlib>
#include <iostream>
#include <reactor/reactor.hpp>
#include <thread>

#include "display.hpp"

namespace {

constexpr auto MODEL = "reactor/helios";

/// Frames seen in the last second, printed and reset.
int drain(std::atomic<int>& counter) { return counter.exchange(0); }

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

  try {
    client.connect().get();
    client.send_command("set_prompt", {{"prompt", "a lighthouse in a storm"}}).get();
    client.send_command("start").get();

    std::atomic<int> frames{0};
    examples::Display display{"03 — pause and resume"};
    auto video = client.track("main_video");
    auto subscription = video.on_frame([&](const reactor::VideoFrame& frame) {
      ++frames;
      display.show(frame);
    });

    const auto second = [&](const char* phase) {
      std::this_thread::sleep_for(std::chrono::seconds(1));
      display.pump();
      std::cout << phase << ": " << drain(frames) << " frames/s, paused=" << std::boolalpha
                << video.paused() << '\n';
    };

    second("running");
    second("running");

    video.pause().get();
    std::cout << "-- paused --\n";
    second("paused");
    second("paused");

    video.resume().get();
    std::cout << "-- resumed --\n";
    second("running");
    second("running");

    client.disconnect().get();
    return 0;
  } catch (const reactor::ReactorError& error) {
    std::cerr << "failed: " << error.what() << '\n';
    try {
      client.disconnect().get();
    } catch (const reactor::ReactorError&) {
    }
    return 1;
  }
}
