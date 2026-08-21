// 06 — Ask for a clip, then download it.
//
// Two things this example exists to teach.
//
// **Accepted is not ready.** `request_clip` returns as soon as the platform has
// taken the request; the manifest appears once the recording passes the end of the
// chunk holding the window. `download()` is what waits.
//
// **Readiness is in media time, not wall clock.** A snap clip's window ends at
// *now*, so its boundary chunk is always the open one — and it closes because the
// model keeps generating. That is why the wait is bounded by the session being
// alive rather than by a number of seconds: a model generating at a tenth of real
// time takes ten times as long to get there, and one that has stopped never will.
//
//   export REACTOR_API_KEY=...
//   ./06_record_clip [out.mp4]
//
// Docs: https://docs.reactor.inc/concepts/recordings

#include <atomic>
#include <chrono>
#include <cstdlib>
#include <filesystem>
#include <iostream>
#include <reactor/reactor.hpp>
#include <string>
#include <thread>

namespace {

constexpr auto MODEL = "reactor/helios";
constexpr double CLIP_SECONDS = 6.0;

}  // namespace

int main(int argc, char** argv) {
  const char* api_key = std::getenv("REACTOR_API_KEY");
  if (api_key == nullptr) {
    std::cerr << "set REACTOR_API_KEY\n";
    return 2;
  }
  const std::string out_path = argc > 1 ? argv[1] : "clip.mp4";

  reactor::Reactor client{MODEL, reactor::ApiKey{api_key}};
  auto errors = client.on_error(
      [](const reactor::ReactorError& error) { std::cerr << "error: " << error.what() << '\n'; });

  try {
    client.connect().get();
    client.send_command("set_prompt", {{"prompt", "waves breaking on black rocks"}}).get();
    client.send_command("start").get();

    // Something has to have been generated before there is anything to clip.
    std::atomic<int> frames{0};
    auto subscription =
        client.track("main_video").on_frame([&](const reactor::VideoFrame&) { ++frames; });

    std::cout << "generating for " << CLIP_SECONDS << "s before asking for the clip\n";
    const auto until =
        std::chrono::steady_clock::now() + std::chrono::seconds(static_cast<int>(CLIP_SECONDS) + 2);
    while (std::chrono::steady_clock::now() < until) {
      std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }
    std::cout << frames.load() << " frames generated\n";

    const auto clip = client.request_clip(CLIP_SECONDS).get();
    const double window = clip.end_marker() - clip.start_marker();
    std::cout << "clip accepted: " << clip.kind() << " over [" << clip.start_marker() << ", "
              << clip.end_marker() << "] = " << window << "s of media\n"
              << "  playlist: " << clip.playlist_url() << '\n';
    if (window + 0.5 < CLIP_SECONDS) {
      // Asked for six seconds and got less: the window is clamped to the media
      // that exists, and this model generates slower than real time. Nothing is
      // wrong — it is the same reason readiness cannot be waited out on a clock.
      std::cout << "  (asked for " << CLIP_SECONDS
                << "s; a clip cannot contain media the model has not generated yet)\n";
    }

    reactor::Clip::DownloadOptions options;
    options.on_progress = [](std::uint32_t done, std::uint32_t total) {
      std::cout << "  segment " << done << '/' << total << '\r' << std::flush;
    };
    // No timeout: the session is still connected and generating, which is the only
    // thing that can make this clip ready.
    clip.download(out_path, options).get();
    std::cout << '\n';

    const auto size = std::filesystem::file_size(out_path);
    std::cout << "wrote " << out_path << " (" << size << " bytes)\n";

    client.disconnect().get();
    return size > 0 ? 0 : 1;
  } catch (const reactor::ReactorError& error) {
    std::cerr << "failed: " << error.what() << '\n';
    try {
      client.disconnect().get();
    } catch (const reactor::ReactorError&) {
    }
    return 1;
  }
}
