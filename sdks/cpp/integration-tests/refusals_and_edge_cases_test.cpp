// Adversarial coverage of the "Refuse; do not fail quietly" table
// (.claude/skills/sdk-from-ffi/SKILL.md) and a handful of state-invariant
// edge cases none of the ported Python/JS files happen to exercise.
//
// These are not mirrored from another binding — an integration suite's job
// is to try to break the thing against the real backend, not just replay a
// known-good script. Every row of that table is a documented promise; this
// file is what actually stands on the promise against a live session instead
// of trusting the doc comment (or the unit suite's fake FFI table, which
// agrees with whatever fixture it was handed).

#include <chrono>
#include <cstdint>
#include <future>
#include <reactor/errors.hpp>
#include <reactor/reactor.hpp>
#include <string>
#include <vector>

#include <catch2/catch_test_macros.hpp>

#include "support.hpp"

namespace {
constexpr std::uint32_t WIDTH = 64;
constexpr std::uint32_t HEIGHT = 64;
}  // namespace

TEST_CASE("a track name the session never declared is refused, not silently ignored") {
  integration::ConnectedReactor reactor;
  REQUIRE_THROWS_AS(reactor->track("this_track_does_not_exist_on_echo"), reactor::NotFoundError);
}

TEST_CASE("on_frame on a sendonly track is refused") {
  integration::ConnectedReactor reactor;
  auto webcam = reactor->track("webcam");  // sendonly on reactor/echo
  REQUIRE_THROWS_AS(webcam.on_frame([](const reactor::VideoFrame&) {}), reactor::InvalidStateError);
}

TEST_CASE("publish() on a recvonly track is refused") {
  integration::ConnectedReactor reactor;
  auto main_video = reactor->track("main_video");  // recvonly on reactor/echo
  REQUIRE_THROWS_AS(main_video.publish().get(), reactor::InvalidStateError);
}

TEST_CASE("push_frame with a buffer of the wrong length is refused, naming both numbers") {
  integration::ConnectedReactor reactor;
  auto webcam = reactor->track("webcam");
  webcam.publish().get();

  const std::size_t wrong_size = (static_cast<std::size_t>(WIDTH) * HEIGHT * 4U) - 4;
  const std::vector<std::uint8_t> bgra(wrong_size, 0);

  try {
    webcam.push_frame(reactor::Bytes{bgra.data(), bgra.size()}, WIDTH, HEIGHT);
    FAIL("push_frame accepted a buffer shorter than width * height * 4");
  } catch (const reactor::BadRequestError& error) {
    // Naming both numbers is the documented contract (track.hpp's push_frame
    // docs) — checked here rather than assumed, so a message that quietly
    // stopped naming one of them would actually fail this.
    const std::string message = error.what();
    INFO("push_frame error message: " << message);
    REQUIRE(message.find(std::to_string(wrong_size)) != std::string::npos);
    REQUIRE((message.find(std::to_string(WIDTH)) != std::string::npos ||
             message.find(std::to_string(static_cast<std::size_t>(WIDTH) * HEIGHT * 4U)) !=
                 std::string::npos));
  }

  webcam.unpublish();
}

TEST_CASE("push_frame once the session has left Ready is refused") {
  integration::ConnectedReactor reactor;
  auto webcam = reactor->track("webcam");
  webcam.publish().get();
  reactor->disconnect().get();

  const auto bgra = integration::solid_bgra_frame(WIDTH, HEIGHT, 1, 2, 3);
  REQUIRE_THROWS_AS(webcam.push_frame(reactor::Bytes{bgra.data(), bgra.size()}, WIDTH, HEIGHT),
                    reactor::InvalidStateError);
}

TEST_CASE("published() clears across a reconnect, so a stale publish can't push silently") {
  // "Clear it whenever the status leaves Ready" (Track::published's own
  // docs, and the sdk-from-ffi skill's refuse-table section): a reconnect
  // resumes recvonly tracks and nothing else, so a slot published before one
  // must not still claim to be published after it.
  integration::ConnectedReactor reactor;
  auto webcam = reactor->track("webcam");
  webcam.publish().get();
  REQUIRE(webcam.published());

  reactor->reconnect().get();

  REQUIRE_FALSE(webcam.published());
  const auto bgra = integration::solid_bgra_frame(WIDTH, HEIGHT, 4, 5, 6);
  REQUIRE_THROWS_AS(webcam.push_frame(reactor::Bytes{bgra.data(), bgra.size()}, WIDTH, HEIGHT),
                    reactor::InvalidStateError);
}

TEST_CASE("disconnecting a client that never connected is not a failure") {
  // Costs no session-creation quota — never connects at all — so this needs
  // no paced_connect.
  auto client = integration::new_reactor();
  REQUIRE(client.status() == reactor::Status::Disconnected);
  client.disconnect().get();
  REQUIRE(client.status() == reactor::Status::Disconnected);
}

TEST_CASE("TrackList::one() refuses rather than guessing when a filter matches nothing") {
  integration::ConnectedReactor reactor;
  // A track cannot be both kinds at once, so this is guaranteed empty
  // regardless of what reactor/echo happens to declare — no dependence on the
  // model's actual track list.
  const auto none =
      reactor->tracks().with_kind(reactor::TrackKind::Video).with_kind(reactor::TrackKind::Audio);
  REQUIRE(none.empty());
  REQUIRE_THROWS_AS(none.one(), reactor::NotFoundError);
}

TEST_CASE("an unknown command name fails cleanly rather than hanging") {
  integration::ConnectedReactor reactor;
  auto future = reactor->send_command("this_command_does_not_exist_on_echo");

  const auto status = future.wait_for(std::chrono::seconds(15));
  REQUIRE(status == std::future_status::ready);
  REQUIRE_THROWS_AS(future.get(), reactor::ReactorError);
}

TEST_CASE("uploading a file that does not exist is refused, naming the path") {
  integration::ConnectedReactor reactor;
  const std::string path = "/definitely/does/not/exist/reactor-cpp-integration.png";

  try {
    reactor->upload_file(path).get();
    FAIL("upload_file resolved for a path that does not exist");
  } catch (const reactor::NotFoundError& error) {
    const std::string message = error.what();
    INFO("upload_file error message: " << message);
    REQUIRE(message.find(path) != std::string::npos);
  }
}

TEST_CASE("unpublish racing an in-flight publish does not corrupt the track") {
  // "In flight is its own state" (sdk-from-ffi skill, refuse-table section):
  // a publish asked for and not yet answered is not published, and folding
  // it into either published or not-published is how the state gets
  // corrupted.
  //
  // Run against a live session, this refuses rather than doing anything:
  // unpublish() here throws (its local `published_` flag isn't set yet, so
  // there's nothing it can locally clear or notify the server about), which
  // is the "counting it as nothing" half of the skill's guidance — and the
  // finding worth recording is what that implies for a caller: the original
  // publish() is still in flight and still lands, so the track ends up
  // genuinely published server-side despite the unpublish() call appearing
  // to fail silently to the caller who didn't check. There is no way to
  // cancel an in-flight publish() by racing unpublish() against it — a
  // caller who wants that has to await publish() first.
  integration::ConnectedReactor reactor;
  auto webcam = reactor->track("webcam");

  auto publish_future = webcam.publish();
  try {
    webcam.unpublish();
  } catch (const reactor::ReactorError&) {  // NOLINT(bugprone-empty-catch)
    // Either outcome is fine here — see the comment above.
  }

  const auto status = publish_future.wait_for(std::chrono::seconds(10));
  REQUIRE(status == std::future_status::ready);
  try {
    publish_future.get();
  } catch (const reactor::ReactorError&) {  // NOLINT(bugprone-empty-catch)
  }

  // Normalize before the recoverability check below: the race may have left
  // the track genuinely published (see above), and a fresh publish() on top
  // of that would fail with "track already published" — a bug in this test,
  // not in the SDK, the first time it was written without this line.
  try {
    webcam.unpublish();
  } catch (const reactor::ReactorError&) {  // NOLINT(bugprone-empty-catch)
  }

  // Whatever the race resolved to, a fresh, unambiguous cycle must still
  // work: the real assertion is that the client survived the race at all.
  webcam.publish().get();
  REQUIRE(webcam.published());
  webcam.unpublish();
  REQUIRE_FALSE(webcam.published());
}
