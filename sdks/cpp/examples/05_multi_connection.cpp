// 05 — Two clients on one session.
//
// The first creates the session and drives it. The second adopts it by id and
// only watches: same session, same generation, two independent connections.
//
// The teardown order is the lesson. The *creator* ends the session, so the
// watcher disconnects first — and both disconnect no matter what, because a
// creator that goes away without disconnecting leaves the session orphaned and
// the next run cannot start until it clears.
//
//   export REACTOR_API_KEY=...
//   ./05_multi_connection
//
// Docs: https://docs.reactor.inc/concepts/sessions

#include <atomic>
#include <chrono>
#include <cstdlib>
#include <iostream>
#include <reactor/reactor.hpp>
#include <thread>

#include "display.hpp"

namespace {

constexpr auto MODEL = "reactor/helios";

}  // namespace

int main() {
  const char* api_key = std::getenv("REACTOR_API_KEY");
  if (api_key == nullptr) {
    std::cerr << "set REACTOR_API_KEY\n";
    return 2;
  }

  reactor::Reactor creator{MODEL, reactor::ApiKey{api_key}};
  reactor::Reactor watcher{MODEL, reactor::ApiKey{api_key}};

  auto creator_errors = creator.on_error([](const reactor::ReactorError& error) {
    std::cerr << "creator error: " << error.what() << '\n';
  });
  auto watcher_errors = watcher.on_error([](const reactor::ReactorError& error) {
    std::cerr << "watcher error: " << error.what() << '\n';
  });

  int exit_code = 1;
  try {
    creator.connect().get();
    const auto session = creator.session_id().value();
    std::cout << "creator opened session " << session << '\n';

    creator.send_command("set_prompt", {{"prompt", "a paper boat on a wide river"}}).get();
    creator.send_command("start").get();

    // The same session, adopted rather than created. Everything else about this
    // client is ordinary.
    reactor::ConnectOptions adopt;
    adopt.session_id = session;
    watcher.connect(std::move(adopt)).get();
    std::cout << "watcher joined session " << watcher.session_id().value_or("?") << '\n';

    std::atomic<int> creator_frames{0};
    std::atomic<int> watcher_frames{0};
    examples::Display display{"05 — the watcher's view"};

    auto on_creator =
        creator.track("main_video").on_frame([&](const reactor::VideoFrame&) { ++creator_frames; });
    auto on_watcher = watcher.track("main_video").on_frame([&](const reactor::VideoFrame& frame) {
      ++watcher_frames;
      display.show(frame);
    });

    const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(10);
    while (std::chrono::steady_clock::now() < deadline && display.pump()) {
      std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }

    std::cout << "creator saw " << creator_frames.load() << " frames, watcher saw "
              << watcher_frames.load() << '\n';
    exit_code = (creator_frames.load() > 0 && watcher_frames.load() > 0) ? 0 : 1;
  } catch (const reactor::ReactorError& error) {
    std::cerr << "failed: " << error.what() << '\n';
  }

  // The watcher leaves first: disconnecting the creator ends the session for
  // everyone on it.
  try {
    watcher.disconnect().get();
  } catch (const reactor::ReactorError& error) {
    std::cerr << "watcher teardown: " << error.what() << '\n';
  }
  try {
    creator.disconnect().get();
  } catch (const reactor::ReactorError& error) {
    std::cerr << "creator teardown: " << error.what() << '\n';
  }
  return exit_code;
}
