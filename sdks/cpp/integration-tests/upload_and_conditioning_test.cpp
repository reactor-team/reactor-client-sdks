// Upload a file, condition a command on it — example 02.
//
// `upload_file`/`upload_bytes` return a `FileRef`; `send_command`'s third
// argument is a named-upload map, so the reference goes in as a named upload
// rather than embedded in the arguments (reactor.hpp's send_command docs).
// `reactor/echo`'s `set_overlay_image` is what this suite has that actually
// consumes an upload. Mirrors sdks/python/integration-tests/tests/
// test_upload_and_conditioning.py, with both of the two entry points Python's
// single overloaded `upload_file` collapses — `upload_file(path)` and
// `upload_bytes(data, name, mime_type)` are separate methods here.

#include <array>
#include <chrono>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <mutex>
#include <reactor/reactor.hpp>
#include <thread>

#include <catch2/catch_test_macros.hpp>

#include "support.hpp"

namespace {
constexpr std::uint32_t WIDTH = 64;
constexpr std::uint32_t HEIGHT = 64;
}  // namespace

TEST_CASE("upload_bytes returns a useable FileRef") {
  integration::ConnectedReactor reactor;
  const auto png = integration::solid_rgb_png(8, 8, 200, 30, 90);

  const auto ref =
      reactor->upload_bytes(reactor::Bytes{png.data(), png.size()}, "overlay.png", "image/png")
          .get();

  REQUIRE_FALSE(ref.upload_id.empty());
  REQUIRE(ref.name == "overlay.png");
  REQUIRE(ref.mime_type == "image/png");
  REQUIRE(ref.size == png.size());
}

TEST_CASE("upload_file (from disk) returns a useable FileRef") {
  integration::ConnectedReactor reactor;
  const auto png = integration::solid_rgb_png(8, 8, 90, 200, 30);

  const auto path = std::filesystem::temp_directory_path() / "reactor-cpp-integration-overlay.png";
  {
    std::ofstream out(path, std::ios::binary);
    REQUIRE(out.good());
    out.write(reinterpret_cast<const char*>(png.data()), static_cast<std::streamsize>(png.size()));
  }

  const auto ref = reactor->upload_file(path.string()).get();
  std::filesystem::remove(path);

  REQUIRE_FALSE(ref.upload_id.empty());
  REQUIRE_FALSE(ref.name.empty());
  REQUIRE(ref.size == png.size());
}

TEST_CASE("set_overlay_image at full strength dominates main_video") {
  integration::ConnectedReactor reactor;
  const auto png = integration::solid_rgb_png(16, 16, 220, 60, 15);
  const auto ref =
      reactor->upload_bytes(reactor::Bytes{png.data(), png.size()}, "overlay.png", "image/png")
          .get();

  auto webcam = reactor->track("webcam");
  webcam.publish().get();

  const auto bgra = integration::solid_bgra_frame(WIDTH, HEIGHT, 10, 10, 10);
  integration::FramePump pump{webcam, bgra, WIDTH, HEIGHT};

  // Set the overlay only once frames are already flowing, mirroring a caller
  // conditioning a live session rather than one that hasn't started yet.
  std::this_thread::sleep_for(std::chrono::milliseconds(500));
  reactor->send_command("set_overlay_image", {{"overlay_strength", 1.0}}, {{"overlay_image", ref}})
      .get();

  std::mutex mutex;
  int frames = 0;
  std::array<double, 3> mean{};
  auto main_video = reactor->track("main_video");
  auto subscription = main_video.on_frame([&](const reactor::VideoFrame& frame) {
    const std::lock_guard<std::mutex> lock(mutex);
    mean = integration::mean_rgb(frame);
    ++frames;
  });
  integration::wait_until(
      [&] {
        const std::lock_guard<std::mutex> lock(mutex);
        return frames >= 3;
      },
      6.0);

  // overlay_strength=1.0 replaces the frame with the (resized) overlay
  // outright (echo_model.py's _overlay_image: addWeighted(frame, 0, resized,
  // 1, 0) == resized) — the webcam's own colour should not show through at
  // all.
  integration::assert_dominant_color(mean, {220, 60, 15}, 35.0);

  pump.check();  // surface a background push_frame failure, if any, here
  webcam.unpublish();
}
