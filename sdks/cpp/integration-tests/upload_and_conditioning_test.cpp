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

#include <atomic>
#include <chrono>
#include <cstdint>
#include <filesystem>
#include <fstream>
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

TEST_CASE("set_overlay_image round-trips (visual check disabled — REA-5931)") {
  // Pixel assertion disabled — REA-5931 (see README.md): a shared prod worker
  // can already be carrying a *different* session's leaked overlay before
  // this command even runs, so this session's own output isn't a reliable
  // thing to diff against. Same call sdks/js/integration-tests/tests/
  // tracks-and-upload.spec.ts and sdks/python/integration-tests/tests/
  // test_upload_and_conditioning.py already made for their own
  // set_overlay_image step. The command still goes out below, keeping
  // coverage that the SDK's own upload + send path works; only the
  // model-side visual verification is off.
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

  std::atomic<int> frames{0};
  auto main_video = reactor->track("main_video");
  auto subscription = main_video.on_frame([&](const reactor::VideoFrame&) { ++frames; });
  integration::wait_until([&] { return frames.load() >= 3; }, 6.0);

  pump.check();  // surface a background push_frame failure, if any, here
  webcam.unpublish();
}
