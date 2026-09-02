// Two clients, one session — example 05.
//
// The first client creates the session; a second adopts it by id
// (`ConnectOptions::session_id`). Only the creator ends the session on
// disconnect — that asymmetry is the point of adoption (a joiner's process can
// exit at any moment without taking the session down), and is what this file
// actually checks, not just that adoption connects.
//
// **Both clients must share one already-minted token.** Confirmed against
// prod while building sdks/python/integration-tests/tests/
// test_multi_connection.py, which this file mirrors: constructing each client
// the convenient way (an API key, letting each `Reactor` mint its own token)
// 403s the joiner — the coordinator only accepts the token that *created* a
// session for a second connection to adopt it by id. `support::mint_jwt` mints
// one token up front and hands it to both clients via `reactor::Jwt`, the same
// fix `sdks/cpp/examples/05_multi_connection.cpp` should double check it still
// needs — it currently constructs both clients with an API key each, the
// same bit-rotted shape the Python example was found carrying.

#include <atomic>
#include <chrono>
#include <cstdint>
#include <functional>
#include <optional>
#include <thread>

#include <catch2/catch_test_macros.hpp>

#include <reactor/reactor.hpp>

#include "support.hpp"

namespace {

constexpr std::uint32_t WIDTH = 64;
constexpr std::uint32_t HEIGHT = 64;

/// A connected creator, and a callable that joins it to a fresh client sharing
/// its token. Mirrors sdks/python/integration-tests/tests/
/// test_multi_connection.py's `_shared_pair`.
struct SharedPair {
  reactor::Reactor& creator;
  std::function<reactor::Reactor&()> join;
};

SharedPair make_shared_pair(integration::ReactorFactory& factory) {
  // In local mode there's no coordinator auth to satisfy, and no API key is
  // guaranteed to exist to mint from — mirrors support::new_reactor's own
  // handling of LOCAL.
  const std::optional<reactor::Jwt> jwt =
      integration::LOCAL ? std::nullopt
                          : std::optional<reactor::Jwt>{reactor::Jwt{integration::mint_jwt()}};

  auto& creator = factory.create(jwt);
  integration::paced_connect(creator);

  return SharedPair{creator, [&factory, jwt, &creator]() -> reactor::Reactor& {
                       auto& joiner = factory.create(jwt);
                       reactor::ConnectOptions adopt;
                       adopt.session_id = creator.session_id();
                       integration::paced_connect(joiner, adopt);
                       return joiner;
                     }};
}

}  // namespace

TEST_CASE("a joiner adopts the same session") {
  integration::ReactorFactory factory;
  auto pair = make_shared_pair(factory);
  auto& joiner = pair.join();

  REQUIRE(joiner.session_id() == pair.creator.session_id());
  REQUIRE(joiner.status() == reactor::Status::Ready);
}

TEST_CASE("a joiner observes state the creator already set") {
  integration::ReactorFactory factory;
  auto pair = make_shared_pair(factory);
  auto webcam = pair.creator.track("webcam");
  webcam.publish().get();

  const auto bgra = integration::solid_bgra_frame(WIDTH, HEIGHT, 60, 150, 20);
  integration::FramePump pump{webcam, bgra, WIDTH, HEIGHT};

  pair.creator.send_command("set_effect", {{"effect", "invert"}}).get();
  pair.creator.send_command("set_intensity", {{"intensity", 1.0}}).get();

  auto& joiner = pair.join();

  // Pixel assertion disabled — REA-5931 (see README.md), same call as
  // tracks_and_frames_test.cpp's own effect coverage. What's left to check
  // honestly is that frames actually arrive on the joiner's own view of the
  // session at all.
  std::atomic<int> received{0};
  auto subscription =
      joiner.track("main_video").on_frame([&](const reactor::VideoFrame&) { ++received; });
  integration::wait_until([&] { return received.load() >= 3; }, 10.0);

  pump.check();  // surface a background push_frame failure, if any, here
  webcam.unpublish();
}

TEST_CASE("the creator disconnecting ends the session for the joiner") {
  integration::ReactorFactory factory;
  auto pair = make_shared_pair(factory);
  auto& joiner = pair.join();

  pair.creator.disconnect().get();

  integration::wait_until([&] { return joiner.status() != reactor::Status::Ready; }, 10.0);
}

TEST_CASE("the joiner disconnecting leaves the session running") {
  integration::ReactorFactory factory;
  auto pair = make_shared_pair(factory);
  auto& joiner = pair.join();

  joiner.disconnect().get();
  std::this_thread::sleep_for(std::chrono::seconds(1));

  REQUIRE(pair.creator.status() == reactor::Status::Ready);
  // The session is still alive server-side under the creator — a command
  // actually round-tripping is the proof, not just the cached status.
  pair.creator.send_command("set_effect", {{"effect", "none"}}).get();
}
