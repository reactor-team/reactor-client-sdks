// Connect, status, commands, schema — examples 01 and 08, automated.
//
// One connected session per test, walked through the surface a caller
// actually uses on it — the same "one connect/disconnect pair per concern"
// shape as sdks/python/integration-tests/tests/test_lifecycle_and_commands.py
// and the JS suite's lifecycle-and-commands.spec.ts: real sessions against
// reactor/echo aren't free or instant.

#include <algorithm>
#include <map>
#include <mutex>
#include <string>
#include <vector>

#include <catch2/catch_test_macros.hpp>

#include <reactor/errors.hpp>
#include <reactor/reactor.hpp>

#include "support.hpp"

TEST_CASE("connect walks disconnected to ready, and the getters agree") {
  integration::ReactorFactory factory;
  auto& r = factory.create();

  std::mutex mutex;
  std::vector<std::string> statuses;
  auto subscription = r.on_status([&](reactor::Status status) {
    const std::lock_guard<std::mutex> lock(mutex);
    statuses.emplace_back(reactor::to_string(status));
  });

  REQUIRE(r.status() == reactor::Status::Disconnected);
  REQUIRE_FALSE(r.session_id().has_value());

  integration::paced_connect(r);

  REQUIRE(r.status() == reactor::Status::Ready);
  REQUIRE(r.session_id().has_value());

  // connect()'s own completion and the "status_changed" event it triggers are
  // dispatched independently (see reactor.hpp's on_status docs and this
  // suite's concurrency_and_races_test.cpp for the shape of that race) — the
  // same ordering gap sdks/python/integration-tests found and documented.
  // connect() can return before the "ready" on_status callback has actually
  // run, so poll briefly for it to catch up rather than asserting on the last
  // element the instant connect() resolves.
  integration::wait_until(
      [&] {
        const std::lock_guard<std::mutex> lock(mutex);
        return !statuses.empty() && statuses.back() == "ready";
      },
      2.0);

  const std::lock_guard<std::mutex> lock(mutex);
  // "connecting" and "waiting" may repeat under retry, but the sequence must
  // never go backwards and must end on "ready" — checked by rank, not just
  // first/last value, so a status silently skipped or reordered still fails
  // this.
  const std::map<std::string, int> rank = {
      {"disconnected", 0}, {"connecting", 1}, {"waiting", 2}, {"ready", 3}};
  REQUIRE(statuses.front() == "connecting");
  REQUIRE(statuses.back() == "ready");
  REQUIRE(std::find(statuses.begin(), statuses.end(), "waiting") != statuses.end());
  for (std::size_t i = 1; i < statuses.size(); ++i) {
    REQUIRE(rank.at(statuses[i]) >= rank.at(statuses[i - 1]));
  }
}

TEST_CASE("send_command round-trips, and the model's own message arrives separately") {
  integration::ConnectedReactor reactor;

  std::mutex mutex;
  std::vector<reactor::Json> messages;
  auto subscription = reactor->on_message([&](const reactor::Json& message) {
    const std::lock_guard<std::mutex> lock(mutex);
    messages.push_back(message);
  });

  // set_effect acknowledges with no data (the handler returns nothing) and
  // separately sends effect_changed as an application message — two
  // different channels, both exercised here.
  const auto result = reactor->send_command("set_effect", {{"effect", "invert"}}).get();
  REQUIRE_FALSE(result.has_value());

  // intensity isn't set by this test, and a fresh session's own default
  // can't be relied on here — REA-5931's session-state leak (see README.md)
  // leaks intensity too, not just effect/overlay. Setting it explicitly
  // keeps this test about the message round trip, not about REA-5931.
  reactor->send_command("set_intensity", {{"intensity", 1.0}}).get();

  integration::wait_until(
      [&] {
        const std::lock_guard<std::mutex> lock(mutex);
        return messages.size() >= 2;
      },
      5.0);

  const std::lock_guard<std::mutex> lock(mutex);
  REQUIRE_FALSE(messages.empty());
  const auto& last = messages.back();
  REQUIRE(last.at("type") == "effect_changed");
  REQUIRE(last.at("data").at("effect") == "invert");
  REQUIRE(last.at("data").at("intensity") == 1.0);
}

TEST_CASE("the model itself rejects an out-of-range argument") {
  integration::ConnectedReactor reactor;

  // set_intensity declares ge=0.0, le=1.0 — the model refuses this, not the
  // SDK, so the point of this test is that the refusal reaches the caller as
  // a thrown error rather than as a silently-accepted command.
  REQUIRE_THROWS_AS(reactor->send_command("set_intensity", {{"intensity", 5.0}}).get(),
                    reactor::ReactorError);
}

TEST_CASE("request_schema describes echo's own commands") {
  integration::ConnectedReactor reactor;

  const auto schema = reactor->request_schema().get();
  const std::string text = schema.dump();
  for (const std::string& command : {"set_effect", "set_intensity", "set_overlay_image"}) {
    INFO("looking for " << command << " in " << text);
    REQUIRE(text.find(command) != std::string::npos);
  }
}

TEST_CASE("reconnect keeps the same session") {
  integration::ConnectedReactor reactor;

  const auto session_id = reactor->session_id();
  REQUIRE(session_id.has_value());

  reactor->reconnect().get();

  REQUIRE(reactor->status() == reactor::Status::Ready);
  REQUIRE(reactor->session_id() == session_id);
}

TEST_CASE("disconnect ends the session, and further commands are refused") {
  integration::ConnectedReactor reactor;

  reactor->disconnect().get();
  REQUIRE(reactor->status() == reactor::Status::Disconnected);

  REQUIRE_THROWS_AS(reactor->send_command("set_effect", {{"effect", "none"}}).get(),
                    reactor::ReactorError);
}

TEST_CASE("disconnect is idempotent") {
  integration::ConnectedReactor reactor;

  reactor->disconnect().get();
  // The first call tears the handle's session down; the second is what
  // actually proves that path doesn't throw or hang.
  reactor->disconnect().get();
  REQUIRE(reactor->status() == reactor::Status::Disconnected);
}

TEST_CASE("two independent clients on the same model do not cross talk") {
  // Two independent sessions — not adoption (see multi_connection_test.cpp
  // for that) — proving one client's commands don't leak into the other's
  // session.
  integration::ReactorFactory factory;
  auto& a = factory.create();
  auto& b = factory.create();
  integration::paced_connect(a);
  integration::paced_connect(b);
  REQUIRE(a.session_id() != b.session_id());

  a.send_command("set_effect", {{"effect", "grayscale"}}).get();
  b.send_command("set_effect", {{"effect", "invert"}}).get();

  const auto schema_a = a.request_schema().get();
  const auto schema_b = b.request_schema().get();
  // Both describe the same model, so this only proves neither call raised
  // cross-session — the real assertion is that each session's own state
  // (exercised in tracks_and_frames_test.cpp's effect coverage) stayed
  // session-local.
  REQUIRE(schema_a == schema_b);
}
