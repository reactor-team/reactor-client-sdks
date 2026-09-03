// 07 — The trailer on each incoming frame.
//
// Every frame can carry three things beyond its pixels: the sender's frame id,
// the sender's capture time, and whatever bytes the sender tagged it with. All
// three are optional, and a frame without them arrives as zeros and no bytes.
//
// **Expect zeros against Helios.** This example's target model is purely
// generative — it has no input frames at all, so there is nothing of the
// client's for it to mirror back, and a trailer here reads
// `frame_id=false timestamp=false user_data=false` by construction, not
// because no model bothers. `reactor/echo` is different: as of version 1.7.5
// it reads a client's own webcam frames and mirrors whatever `user_data` was
// attached to them straight onto `main_video`. What this example is for
// either way is the *reading* side. Example 04 shows the sending side.
//
// The capture time is in the *engine's* clock, the one `reactor::time_micros()`
// reads. It is not a UNIX timestamp and comparing it to one is meaningless; what
// it is good for is the difference between two frames, and the offset from a local
// read at the same moment.
//
//   export REACTOR_API_KEY=...
//   ./07_frame_metadata
//
// Docs: https://docs.reactor.inc/concepts/frame-metadata

#include <algorithm>
#include <chrono>
#include <cstdlib>
#include <iostream>
#include <mutex>
#include <reactor/reactor.hpp>
#include <string>
#include <thread>
#include <vector>

namespace {

constexpr auto MODEL = "reactor/helios";

struct Trailer {
  std::uint64_t frame_id = 0;
  std::uint64_t timestamp_us = 0;
  std::size_t user_data_bytes = 0;
  std::int64_t local_time_us = 0;
};

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
    client.send_command("set_prompt", {{"prompt", "a hummingbird at a feeder, slow motion"}}).get();
    client.send_command("start").get();

    std::mutex mutex;
    std::vector<Trailer> seen;

    auto subscription = client.track("main_video").on_frame([&](const reactor::VideoFrame& frame) {
      Trailer trailer;
      trailer.frame_id = frame.frame_id;
      trailer.timestamp_us = frame.timestamp_us;
      trailer.user_data_bytes = frame.user_data.size;
      // Read on this side, at the moment of arrival, in the same clock the
      // sender stamped with. The difference is the only thing either value
      // means on its own.
      trailer.local_time_us = reactor::time_micros();

      const std::lock_guard<std::mutex> lock(mutex);
      seen.push_back(trailer);
    });

    std::cout << "collecting frames for 10s\n";
    std::this_thread::sleep_for(std::chrono::seconds(10));

    const std::lock_guard<std::mutex> lock(mutex);
    std::cout << "received " << seen.size() << " frames\n";
    if (seen.empty()) {
      client.disconnect().get();
      return 1;
    }

    const bool has_ids =
        std::any_of(seen.begin(), seen.end(), [](const Trailer& t) { return t.frame_id != 0; });
    const bool has_times =
        std::any_of(seen.begin(), seen.end(), [](const Trailer& t) { return t.timestamp_us != 0; });
    const bool has_tags = std::any_of(seen.begin(), seen.end(),
                                      [](const Trailer& t) { return t.user_data_bytes != 0; });

    std::cout << "trailer present: frame_id=" << std::boolalpha << has_ids
              << " timestamp=" << has_times << " user_data=" << has_tags << '\n';
    if (!has_ids && !has_times && !has_tags) {
      std::cout << "  (all zero — expected against Helios, which has no input frames "
                   "to mirror; see the note at the top)\n";
    }

    for (std::size_t index = 0; index < std::min<std::size_t>(seen.size(), 5); ++index) {
      const auto& trailer = seen[index];
      std::cout << "  frame_id=" << trailer.frame_id << " timestamp_us=" << trailer.timestamp_us;
      if (trailer.timestamp_us != 0) {
        // Positive: the frame was captured before it arrived here. Large means the
        // model is generating slower than real time, which is normal and is why
        // clip readiness is measured in media time.
        std::cout << " age_ms="
                  << (trailer.local_time_us - static_cast<std::int64_t>(trailer.timestamp_us)) /
                         1000;
      }
      std::cout << " user_data=" << trailer.user_data_bytes << "B\n";
    }

    if (has_ids && seen.size() > 1) {
      // Contiguous ids mean nothing was dropped between the sender and here. A gap
      // is not an error: the FFI keeps the newest frame while a handler runs.
      std::size_t gaps = 0;
      for (std::size_t index = 1; index < seen.size(); ++index) {
        if (seen[index].frame_id != seen[index - 1].frame_id + 1) {
          ++gaps;
        }
      }
      std::cout << "gaps in the frame_id sequence: " << gaps << '\n';
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
