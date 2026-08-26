// 08 — Save a snapshot, list them, rewind to one.
//
// What `send_command()` actually hands back is the point. Every earlier
// example either fires a command without waiting on the reply or reads only
// its bare `type`. `save_snapshot`, `list_snapshots`, and `rewind` each
// answer with a `data` payload worth reading: the assigned index, the full
// snapshot list, where a rewind landed.
//
//   export REACTOR_API_KEY=...
//   ./08_snapshot_and_rewind
//
// Docs: https://docs.reactor.inc/concepts/commands-and-messages
//       https://docs.reactor.inc/model-api-reference/helios/schema

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

  reactor::Reactor client{MODEL, reactor::ApiKey{api_key}};

  auto status = client.on_status(
      [](reactor::Status now) { std::cout << "status: " << reactor::to_string(now) << '\n'; });
  auto errors = client.on_error(
      [](const reactor::ReactorError& error) { std::cerr << "error: " << error.what() << '\n'; });
  // A broadcast, not a reply — the correlated replies below arrive as each
  // send_command() call's own resolved value instead.
  auto messages =
      client.on_message([](const reactor::Json& msg) { std::cout << "message: " << msg.dump() << '\n'; });

  try {
    client.connect().get();
    std::cout << "session " << client.session_id().value_or("?") << " is ready\n";
    client.send_command("set_prompt", {{"prompt", "a lighthouse in a storm"}}).get();
    client.send_command("start").get();

    // `save_snapshot` captures the current world state — nothing to save
    // until a frame has actually been generated.
    std::atomic<int> frames{0};
    examples::Display display{"08 — snapshot and rewind"};
    auto video = client.track("main_video");
    auto subscription = video.on_frame([&](const reactor::VideoFrame& frame) {
      ++frames;
      display.show(frame);
    });

    while (frames.load() == 0) {
      display.pump();
      std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }

    const auto first = client.send_command("save_snapshot", {{"label", "before the wave"}}).get();
    std::cout << "save_snapshot -> " << (first ? first->dump() : "(empty)") << '\n';
    std::this_thread::sleep_for(std::chrono::seconds(1));
    const auto second = client.send_command("save_snapshot", {{"label", "after the wave"}}).get();
    std::cout << "save_snapshot -> " << (second ? second->dump() : "(empty)") << '\n';

    const auto listing = client.send_command("list_snapshots").get();
    std::cout << "list_snapshots -> " << (listing ? listing->dump() : "(empty)") << '\n';

    reactor::Json data = reactor::Json::object();
    if (listing && listing->contains("data") && (*listing)["data"].is_object()) {
      data = (*listing)["data"];
    }
    const auto snapshots = data.value("snapshots", reactor::Json::array());
    if (!snapshots.empty()) {
      const int target = snapshots.front()["snapshot_index"].get<int>();
      const auto rewound = client.send_command("rewind", {{"snapshot_index", target}}).get();
      std::cout << "rewind -> " << (rewound ? rewound->dump() : "(empty)") << '\n';
    } else {
      std::cout << "no snapshots to rewind to\n";
    }

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
