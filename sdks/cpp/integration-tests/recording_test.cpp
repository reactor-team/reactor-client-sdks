// Request a clip, download it — example 06.
//
// Readiness is in media time, not wall clock (recording.hpp's
// `predicted_ready_at_ms` note, and `Clip::download`'s own docstring): the
// manifest appears once the recording passes the end of the chunk holding the
// window. Confirmed empirically the hard way while building
// sdks/python/integration-tests/tests/test_recording.py, which this file
// mirrors: generation has to keep running *past* the moment the clip is
// requested, not just up to it — a "snap" clip's boundary chunk always ends at
// *now*, so it never closes once nothing is left to read.

#include <chrono>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <reactor/reactor.hpp>
#include <thread>
#include <vector>

#include <catch2/catch_test_macros.hpp>

#include "fixtures.hpp"

namespace {

constexpr std::uint32_t WIDTH = 64;
constexpr std::uint32_t HEIGHT = 64;
constexpr double CLIP_SECONDS = 3.0;

}  // namespace

TEST_CASE("request_clip + download produces a playable file") {
  integration::ConnectedReactor reactor;
  auto webcam = reactor->track("webcam");
  webcam.publish().get();

  const auto bgra = integration::solid_bgra_frame(WIDTH, HEIGHT, 80, 40, 200);
  integration::FramePump pump{webcam, bgra, WIDTH, HEIGHT};

  // Generate past the window this test will ask for before asking, so the
  // window itself is already fully generated — but keep pumping (the pump
  // above stays alive through download() below) so the boundary chunk still
  // has something to close.
  std::this_thread::sleep_for(std::chrono::duration<double>(CLIP_SECONDS + 2.0));

  const auto clip = reactor->request_clip(CLIP_SECONDS).get();
  REQUIRE(clip.session_id() == reactor->session_id());
  REQUIRE_FALSE(clip.playlist_url().empty());

  const auto path = std::filesystem::temp_directory_path() / "reactor-cpp-integration-clip.mp4";
  // No ready_timeout: the session is still connected and generating, which is
  // the only thing that can make this clip ready.
  clip.download(path.string()).get();

  pump.check();  // surface a background push_frame failure, if any, here
  webcam.unpublish();

  const auto size = std::filesystem::file_size(path);
  REQUIRE(size > 0);

  std::ifstream in(path, std::ios::binary);
  std::vector<char> header(256);
  in.read(header.data(), static_cast<std::streamsize>(header.size()));
  const std::string head(header.begin(), header.begin() + in.gcount());
  std::filesystem::remove(path);

  // Fragmented MP4: the init segment (ftyp/moov) goes in first.
  REQUIRE(head.find("ftyp") != std::string::npos);
}

TEST_CASE("request_recording covers the whole session") {
  integration::ConnectedReactor reactor;
  auto webcam = reactor->track("webcam");
  webcam.publish().get();

  const auto bgra = integration::solid_bgra_frame(WIDTH, HEIGHT, 80, 40, 200);
  integration::FramePump pump{webcam, bgra, WIDTH, HEIGHT};

  std::this_thread::sleep_for(std::chrono::duration<double>(CLIP_SECONDS));

  const auto clip = reactor->request_recording().get();
  REQUIRE(clip.start_marker() == 0.0);
  REQUIRE(clip.session_id() == reactor->session_id());

  const auto path =
      std::filesystem::temp_directory_path() / "reactor-cpp-integration-recording.mp4";
  clip.download(path.string()).get();

  pump.check();  // surface a background push_frame failure, if any, here
  webcam.unpublish();

  const auto size = std::filesystem::file_size(path);
  std::filesystem::remove(path);
  REQUIRE(size > 0);
}
