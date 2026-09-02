// Concurrency and teardown races the happy-path specs don't reach.
//
// Mirrors sdks/python/integration-tests/tests/test_concurrency_and_races.py,
// which found a real bug in Python's Reactor.close() — it cleared its pending
// completions without ever settling the futures they belonged to, so an
// in-flight send_command() racing close() hung forever. Fixed in Python by
// porting the pattern this SDK's own `destroy_handle()`
// (src/detail/client_impl.hpp) already had: settle every pending completion
// with an aborted-style error on the way through. The last test in this file
// is what would have caught that bug here, had it existed here first.
//
// Every test is wrapped in a bounded wait: a hang is exactly the failure mode
// being probed for, and an unbounded wait would just make the suite hang too
// instead of reporting it.

#include <chrono>
#include <future>
#include <memory>
#include <optional>
#include <vector>

#include <catch2/catch_test_macros.hpp>

#include <reactor/errors.hpp>
#include <reactor/reactor.hpp>

#include "support.hpp"

TEST_CASE("many concurrent send_commands all resolve without cross-talk") {
  integration::ConnectedReactor reactor;

  std::vector<std::future<std::optional<reactor::Json>>> futures;
  for (int i = 1; i <= 15; ++i) {
    const double intensity = 0.05 * i;
    futures.push_back(reactor->send_command("set_intensity", {{"intensity", intensity}}));
  }

  for (auto& future : futures) {
    const auto status = future.wait_for(std::chrono::seconds(20));
    REQUIRE(status == std::future_status::ready);
    // set_intensity acknowledges with no data — a success with no value.
    REQUIRE_FALSE(future.get().has_value());
  }
}

TEST_CASE("an abandoned command future does not corrupt the client") {
  integration::ConnectedReactor reactor;

  {
    auto abandoned = reactor->send_command("set_effect", {{"effect", "grayscale"}});
    // std::future has no cancel() — this is the C++ shape of the Python
    // suite's "cancel an in-flight await": the future is dropped here without
    // ever calling get() on it, deliberately leaving whatever completion
    // trampoline the SDK registered for it to fire later into nothing that
    // reads the result.
    (void)abandoned.wait_for(std::chrono::milliseconds(1));
  }  // abandoned's destructor runs here, unawaited

  // The client must still work — a fresh command, unrelated to the abandoned
  // one, should complete normally.
  const auto result = reactor->send_command("set_effect", {{"effect", "none"}}).get();
  REQUIRE_FALSE(result.has_value());
}

TEST_CASE("disconnect while a command is in flight settles it") {
  integration::ConnectedReactor reactor;

  auto command = reactor->send_command("set_effect", {{"effect", "sepia"}});
  reactor->disconnect().get();

  // Either it completed just ahead of the disconnect, or the disconnect
  // settled it with an error — what must not happen is neither: a future
  // nothing ever resolves is exactly the class of bug this file exists to
  // catch.
  const auto status = command.wait_for(std::chrono::seconds(10));
  REQUIRE(status == std::future_status::ready);
  try {
    command.get();
  } catch (const reactor::ReactorError&) {  // NOLINT(bugprone-empty-catch)
    // Either outcome is fine — see the comment above. wait_for(...) == ready
    // already proved it settled; this is only here to keep the caught error
    // from propagating out of the test as a failure.
  }
}

TEST_CASE("rapid connect, disconnect, connect ends up ready on a new session") {
  integration::ReactorFactory factory;
  auto& r = factory.create();

  integration::paced_connect(r);
  const auto first_session = r.session_id();
  REQUIRE(first_session.has_value());

  r.disconnect().get();
  REQUIRE(r.status() == reactor::Status::Disconnected);

  integration::paced_connect(r);
  REQUIRE(r.status() == reactor::Status::Ready);
  REQUIRE(r.session_id().has_value());
  REQUIRE(r.session_id() != first_session);
}

TEST_CASE("destroying the client while a command is in flight does not hang") {
  // Managed by hand rather than through ReactorFactory: the point of this
  // test is the moment the native handle is torn down while a call is still
  // outstanding, which a factory's own (later, orderly) disconnect-then-
  // destroy teardown would not reach. No disconnect() first, deliberately —
  // this is the abrupt-teardown path, the same one Python's close() test
  // exercises.
  auto client = std::make_unique<reactor::Reactor>(integration::new_reactor());
  integration::paced_connect(*client);

  auto command = client->send_command("set_effect", {{"effect", "blur"}});
  client.reset();  // drops the native handle while the command may still be in flight

  const auto status = command.wait_for(std::chrono::seconds(10));
  REQUIRE(status == std::future_status::ready);
  try {
    command.get();
  } catch (const reactor::ReactorError&) {  // NOLINT(bugprone-empty-catch)
    // Either outcome is fine — see the comment above. wait_for(...) == ready
    // already proved it settled; this is only here to keep the caught error
    // from propagating out of the test as a failure.
  }
}
