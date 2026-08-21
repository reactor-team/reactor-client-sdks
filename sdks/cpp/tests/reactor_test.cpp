// Client lifetime, against a fake library.
//
// Teardown is where a binding's worst bugs are, and none of them are reachable
// from a live session: they need a completion that never fires, a destroy racing
// a callback, or a handle destroyed twice. So the whole file runs against a fake
// table — which is what `detail::Ffi` exists for.
//
// **No test here fabricates a handle for the real library.** A fake handle looks
// exactly like a live pointer to `reactor_destroy`, and dereferencing it is a
// segfault — in some later, unrelated test, or after the run reports success,
// depending on when it happens. `FakeLibrary` hands out a pointer to its own
// object and its own `destroy` checks it, so the real one is never called with
// anything it did not create. Per-test care cannot enforce that; a fake that owns
// both ends can.

#include "reactor/reactor.hpp"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstring>
#include <future>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <catch2/catch_test_macros.hpp>

#include "detail/ffi.hpp"

namespace {

using namespace std::chrono_literals;

/// A stand-in for libreactor_ffi: records what it was asked, and answers on its
/// own thread, the way the real one does.
class FakeLibrary {
 public:
  FakeLibrary() { current_instance = this; }

  ~FakeLibrary() {
    join_all();
    current_instance = nullptr;
  }

  FakeLibrary(const FakeLibrary&) = delete;
  FakeLibrary& operator=(const FakeLibrary&) = delete;
  FakeLibrary(FakeLibrary&&) = delete;
  FakeLibrary& operator=(FakeLibrary&&) = delete;

  static FakeLibrary& current() { return *current_instance; }

  /// What the SDK is allowed to call, pointed at the statics below.
  reactor::detail::Ffi table() {
    reactor::detail::Ffi filled;
    filled.abi_version = &abi_version;
    filled.create_with_adm = &create_with_adm;
    filled.destroy = &destroy;
    filled.connect = &connect;
    filled.disconnect = &disconnect;
    filled.reconnect = &reconnect;
    filled.status = &status;
    filled.session_id = &session_id;
    filled.free_string = &free_string;
    filled.fetch_jwt = &fetch_jwt;
    return filled;
  }

  // ── What the SDK asked for ────────────────────────────────────────────────

  int creates = 0;
  int destroys = 0;
  /// Called from destroy(), on whatever thread the destructor happened to run on.
  std::function<void()> on_destroy;
  int adm_mode = -1;
  std::string created_with_jwt;
  std::string created_with_model;
  std::string created_with_api_url;
  std::string fetch_jwt_options;
  int connects = 0;
  std::string connect_session_id;

  /// What `destroy` reports: 0 for quiesced, -1 for "a callback is still running".
  int destroy_result = 0;

  /// How many token exchanges were asked for.
  int token_requests = 0;

  /// The completion of an unanswered exchange, and its userdata.
  reactor_completion_fn token_completion = nullptr;
  void* token_userdata = nullptr;

  /// Whether `fetch_jwt` answers at all. False is the shape that matters: its
  /// completion is not bounded by `reactor_destroy`, so a stalled exchange is a
  /// caller waiting on a future nothing else will resolve.
  bool answer_token = true;

  /// Whether a `connect` answers at all. False leaves the call outstanding, which
  /// is the shape teardown has to cope with.
  bool answer_connect = true;

  /// What a `connect` answers with. Empty means success.
  std::string connect_error;

  /// The status the fake reports, and the one it pushes as an event.
  std::string current_status = "disconnected";

  void join_all() {
    std::vector<std::thread> threads;
    {
      const std::lock_guard<std::mutex> lock(mutex_);
      threads = std::move(threads_);
      threads_.clear();
    }
    for (auto& thread : threads) {
      if (thread.joinable()) {
        thread.join();
      }
    }
  }

  /// Fire the status callback from a thread of the library's own, as the real one
  /// does — the point being that a handler must not end up on it.
  void push_status(const std::string& status) {
    current_status = status;
    if (callbacks_.on_status == nullptr) {
      return;
    }
    spawn([this, status] { callbacks_.on_status(status.c_str(), callbacks_.userdata); });
  }

  void push_error(const std::string& error_json) {
    if (callbacks_.on_error == nullptr) {
      return;
    }
    spawn([this, error_json] { callbacks_.on_error(error_json.c_str(), callbacks_.userdata); });
  }

 private:
  /// Run `work` on a thread of the fake library's own, as the real one does.
  ///
  /// The wrapper is not decoration: a callable handed to std::thread must not
  /// throw, and a Catch2 assertion failure inside one is an exception. Without
  /// this, a failing expectation on a library thread would terminate the run
  /// instead of failing the test.
  template <typename F>
  void spawn(F&& work) {
    const std::lock_guard<std::mutex> lock(mutex_);
    threads_.emplace_back([work = std::forward<F>(work)]() noexcept {
      try {
        work();
      } catch (...) {
        FAIL_CHECK("an exception escaped a fake library thread");
      }
    });
  }

  // ── The C surface ─────────────────────────────────────────────────────────

  static std::uint32_t abi_version() { return REACTOR_ABI_VERSION; }

  static ReactorHandle* create_with_adm(const char* api_url, const char* model, const char* jwt,
                                        int /*local*/, const ReactorCallbacks* callbacks,
                                        int adm_mode) {
    auto& self = current();
    ++self.creates;
    self.adm_mode = adm_mode;
    self.created_with_api_url = api_url == nullptr ? "" : api_url;
    self.created_with_model = model == nullptr ? "" : model;
    self.created_with_jwt = jwt == nullptr ? "" : jwt;
    if (callbacks != nullptr) {
      self.callbacks_ = *callbacks;
    }
    // Its own object, so the pointer the SDK holds is one this fake can verify on
    // the way back in — and one the real library never sees.
    return reinterpret_cast<ReactorHandle*>(&self.handle_marker_);
  }

  static int destroy(ReactorHandle* handle) {
    auto& self = current();
    ++self.destroys;
    REQUIRE(handle == reinterpret_cast<ReactorHandle*>(&self.handle_marker_));
    if (self.on_destroy) {
      self.on_destroy();
    }
    // Whatever is still in flight is finished before destroy is allowed to
    // report quiescence — the same promise the real library makes for 0.
    if (self.destroy_result == 0) {
      self.join_all();
    }
    return self.destroy_result;
  }

  static void connect(ReactorHandle* /*handle*/, const char* session_id,
                      const std::uint32_t* /*connection_id*/, reactor_completion_fn completion,
                      void* userdata) {
    auto& self = current();
    ++self.connects;
    self.connect_session_id = session_id == nullptr ? "" : session_id;
    if (!self.answer_connect || completion == nullptr) {
      return;
    }
    const std::string error = self.connect_error;
    self.spawn([completion, userdata, error] {
      if (error.empty()) {
        completion(1, "{}", nullptr, userdata);
      } else {
        completion(0, nullptr, error.c_str(), userdata);
      }
    });
  }

  static void disconnect(ReactorHandle* /*handle*/, reactor_completion_fn completion,
                         void* userdata) {
    auto& self = current();
    if (completion == nullptr) {
      return;
    }
    self.spawn([completion, userdata] { completion(1, "{}", nullptr, userdata); });
  }

  static void reconnect(ReactorHandle* /*handle*/, reactor_completion_fn completion,
                        void* userdata) {
    auto& self = current();
    if (completion == nullptr) {
      return;
    }
    self.spawn([completion, userdata] { completion(1, "{}", nullptr, userdata); });
  }

  static const char* status(ReactorHandle* handle) {
    if (handle == nullptr) {
      return "disconnected";
    }
    return current().current_status.c_str();
  }

  static char* session_id(ReactorHandle* handle) {
    if (handle == nullptr) {
      return nullptr;
    }
    return heap_copy("session-abc");
  }

  static void free_string(char* s) {
    std::free(s);  // NOLINT(cppcoreguidelines-no-malloc)
  }

  static void fetch_jwt(const char* /*api_url*/, const char* /*api_key*/, const char* options_json,
                        int /*local*/, reactor_completion_fn completion, void* userdata) {
    auto& self = current();
    self.fetch_jwt_options = options_json == nullptr ? "" : options_json;
    ++self.token_requests;
    if (completion == nullptr || !self.answer_token) {
      // Kept so a test can fire it by hand, after the client that asked is gone.
      self.token_completion = completion;
      self.token_userdata = userdata;
      return;
    }
    self.spawn(
        [completion, userdata] { completion(1, R"({"jwt":"minted-token"})", nullptr, userdata); });
  }

  static char* heap_copy(const char* text) {
    // NOLINTNEXTLINE(cppcoreguidelines-no-malloc)
    char* copy = static_cast<char*>(std::malloc(std::strlen(text) + 1));
    std::strcpy(copy, text);  // NOLINT(clang-analyzer-security.insecureAPI.strcpy)
    return copy;
  }

  static FakeLibrary* current_instance;

  int handle_marker_ = 0;
  ReactorCallbacks callbacks_{};
  std::mutex mutex_;
  std::vector<std::thread> threads_;
};

FakeLibrary* FakeLibrary::current_instance = nullptr;

/// A fake library installed for the duration of a test.
struct Fixture {
  Fixture() : override_(&table_) {}

  FakeLibrary library;
  reactor::detail::Ffi table_ = library.table();
  reactor::detail::FfiOverride override_;
};

/// Wait for `predicate`, or fail the test. Events cross a thread, so there is no
/// deterministic point at which to assert.
template <typename Predicate>
bool eventually(Predicate predicate, std::chrono::milliseconds limit = 2000ms) {
  const auto deadline = std::chrono::steady_clock::now() + limit;
  while (std::chrono::steady_clock::now() < deadline) {
    if (predicate()) {
      return true;
    }
    std::this_thread::sleep_for(1ms);
  }
  return predicate();
}

}  // namespace

// A client that never connected has to answer, and answer correctly: the FFI
// tolerates a null handle precisely so a binding does not need a second source of
// truth for "not connected yet".
TEST_CASE("a client reports itself disconnected before it connects") {
  const reactor::Reactor client{"reactor/helios", reactor::Jwt{"token"}};

  CHECK(client.status() == reactor::Status::Disconnected);
  CHECK_FALSE(client.session_id().has_value());
}

TEST_CASE("no handle is created until something needs one") {
  Fixture fixture;
  {
    const reactor::Reactor client{"reactor/helios", reactor::Jwt{"token"}};
  }

  CHECK(fixture.library.creates == 0);
  CHECK(fixture.library.destroys == 0);
}

// The audio invariant. `reactor_create` reads its mode from an environment
// variable, and a library whose audience is scripts and servers must never let an
// env var put a live microphone on the wire because a model happened to declare a
// sendonly audio track. Mode 0 is synthetic.
TEST_CASE("the synthetic audio module is pinned, whatever the environment says") {
  Fixture fixture;
  ::setenv("REACTOR_ADM", "platform", 1);
  ::setenv("REACTOR_AUDIO_DEVICE", "1", 1);

  {
    reactor::Reactor client{"reactor/helios", reactor::Jwt{"token"}};
    client.connect().get();
  }

  CHECK(fixture.library.adm_mode == 0);
  ::unsetenv("REACTOR_ADM");
  ::unsetenv("REACTOR_AUDIO_DEVICE");
}

TEST_CASE("connect passes the model, the coordinator and the token through") {
  Fixture fixture;
  reactor::Options options;
  options.api_url = "https://api.example.test";

  {
    reactor::Reactor client{"xmax/x2", reactor::Jwt{"a-token"}, options};
    client.connect().get();
  }

  CHECK(fixture.library.created_with_model == "xmax/x2");
  CHECK(fixture.library.created_with_api_url == "https://api.example.test");
  CHECK(fixture.library.created_with_jwt == "a-token");
  CHECK(fixture.library.connects == 1);
}

// An API key is exchanged for a token before the handle exists, and the token is
// scoped to this model — so a leak is worth a handful of sessions on one model
// rather than everything the key can reach.
TEST_CASE("an API key is exchanged for a token scoped to the model") {
  Fixture fixture;

  {
    reactor::Reactor client{"reactor/helios", reactor::ApiKey{"key-123"}};
    client.connect().get();
  }

  CHECK(fixture.library.created_with_jwt == "minted-token");
  CHECK(fixture.library.fetch_jwt_options.find("reactor/helios") != std::string::npos);
  CHECK(fixture.library.fetch_jwt_options.find("models") != std::string::npos);
}

TEST_CASE("adopting a session passes its id to connect") {
  Fixture fixture;

  {
    reactor::Reactor client{"reactor/helios", reactor::Jwt{"token"}};
    reactor::ConnectOptions options;
    options.session_id = "existing-session";
    client.connect(std::move(options)).get();
  }

  CHECK(fixture.library.connect_session_id == "existing-session");
}

TEST_CASE("a failed connect throws the typed error") {
  Fixture fixture;
  fixture.library.connect_error =
      R"({"code":"UNAUTHORIZED","message":"token expired","status":401,"operation":"connect"})";

  reactor::Reactor client{"reactor/helios", reactor::Jwt{"stale"}};
  auto future = client.connect();

  try {
    future.get();
    FAIL("a rejected connect must throw");
  } catch (const reactor::UnauthorizedError& error) {
    CHECK(error.status() == 401);
    CHECK(error.operation() == "connect");
    CHECK_FALSE(error.recoverable());
  }
}

// The failure mode this exists to prevent: a caller blocked in .get() on a client
// that has been destroyed, waiting for a completion that is never coming.
TEST_CASE("destroying a client settles the calls still in flight") {
  Fixture fixture;
  fixture.library.answer_connect = false;

  std::future<void> future;
  {
    reactor::Reactor client{"reactor/helios", reactor::Jwt{"token"}};
    future = client.connect();
    CHECK(future.wait_for(50ms) == std::future_status::timeout);
  }

  REQUIRE(future.wait_for(2000ms) == std::future_status::ready);
  try {
    future.get();
    FAIL("the call did not complete, so it must not report success");
  } catch (const reactor::AbortedError& error) {
    CHECK(error.operation() == "connect");
  }
}

// -1 means a callback could not be waited for, so every pointer it might touch
// has to stay valid: the callback context and the outstanding calls are leaked on
// purpose. What a test can check is that teardown still finishes and the caller
// still gets an answer.
TEST_CASE("teardown finishes even when the library cannot quiesce") {
  Fixture fixture;
  fixture.library.answer_connect = false;
  fixture.library.destroy_result = -1;

  std::future<void> future;
  {
    reactor::Reactor client{"reactor/helios", reactor::Jwt{"token"}};
    future = client.connect();
  }

  CHECK(fixture.library.destroys == 1);
  REQUIRE(future.wait_for(2000ms) == std::future_status::ready);
  CHECK_THROWS_AS(future.get(), reactor::AbortedError);
}

// Moving a client moves the ownership, not the session: the handle must be
// destroyed once, by whichever object ends up holding it.
TEST_CASE("a moved client destroys its handle exactly once") {
  Fixture fixture;

  {
    reactor::Reactor first{"reactor/helios", reactor::Jwt{"token"}};
    first.connect().get();

    reactor::Reactor second{std::move(first)};
    CHECK(second.status() == reactor::Status::Disconnected);  // the fake's default
  }

  CHECK(fixture.library.creates == 1);
  CHECK(fixture.library.destroys == 1);
}

// Control events must not run on the thread the FFI called on: that thread is the
// library's, and a handler there would be touching the host's primitives from a
// foreign thread. They must also not run concurrently with each other.
TEST_CASE("status handlers run off the library's thread, one at a time") {
  Fixture fixture;
  reactor::Reactor client{"reactor/helios", reactor::Jwt{"token"}};
  client.connect().get();

  std::mutex mutex;
  std::vector<std::thread::id> handler_threads;
  std::atomic<int> concurrent{0};
  std::atomic<int> max_concurrent{0};
  std::atomic<int> seen{0};

  auto subscription = client.on_status([&](reactor::Status status) {
    (void)status;
    const int now = ++concurrent;
    int previous = max_concurrent.load();
    while (now > previous && !max_concurrent.compare_exchange_weak(previous, now)) {
    }
    {
      const std::lock_guard<std::mutex> lock(mutex);
      handler_threads.push_back(std::this_thread::get_id());
    }
    std::this_thread::sleep_for(5ms);
    --concurrent;
    ++seen;
  });

  fixture.library.push_status("connecting");
  fixture.library.push_status("waiting");
  fixture.library.push_status("ready");

  REQUIRE(eventually([&] { return seen.load() == 3; }));

  const std::lock_guard<std::mutex> lock(mutex);
  for (const auto& id : handler_threads) {
    CHECK(id != std::this_thread::get_id());
  }
  // One dispatcher thread, so every handler ran on the same one.
  CHECK(std::adjacent_find(handler_threads.begin(), handler_threads.end(), std::not_equal_to<>()) ==
        handler_threads.end());
  CHECK(max_concurrent.load() == 1);
}

// "On disconnected, throw the client away" is an ordinary thing for a handler to
// write, and it used to take the process with it. The dispatched work holds a
// strong reference while it runs, so the caller's release leaves the destructor to
// the end of that work — on the dispatcher's own thread. Joining that thread from
// inside it is a deadlock the standard reports by throwing, out of a noexcept
// destructor, which is std::terminate.
TEST_CASE("a handler may drop the last reference to its own client") {
  Fixture fixture;
  auto client = std::make_unique<reactor::Reactor>("reactor/helios", reactor::Jwt{"token"});
  client->connect().get();

  std::promise<void> destroyed;
  fixture.library.on_destroy = [&destroyed] { destroyed.set_value(); };

  std::atomic<bool> handled{false};
  auto subscription = client->on_status([&](reactor::Status) {
    // The caller's reference goes here; the work still holds one, so the
    // destructor runs when this handler returns, on this thread.
    client.reset();
    handled = true;
  });

  fixture.library.push_status("disconnected");

  REQUIRE(eventually([&] { return handled.load(); }));
  // The handle was destroyed from the dispatcher thread, and there is still a
  // process here to assert it in.
  REQUIRE(destroyed.get_future().wait_for(2s) == std::future_status::ready);
}

// A handler is host code, and host code has bugs. An exception from one used to
// leave `work()` with nowhere to go: the dispatcher's thread function would exit
// through an uncaught exception, which is std::terminate — one throwing status
// handler ending a process that was otherwise healthy.
TEST_CASE("a handler that throws costs its own event and nothing else") {
  Fixture fixture;
  reactor::Reactor client{"reactor/helios", reactor::Jwt{"token"}};
  client.connect().get();

  std::atomic<int> threw{0};
  std::atomic<int> after{0};
  auto first = client.on_status([&](reactor::Status) {
    ++threw;
    throw std::runtime_error("a bug in the caller's handler");
  });
  // Registered second, and it still runs: one handler's exception does not
  // silence the others listening for the same event.
  auto second = client.on_status([&](reactor::Status) { ++after; });

  fixture.library.push_status("connecting");
  REQUIRE(eventually([&] { return threw.load() == 1 && after.load() == 1; }));

  // And the thread is still there for the next one.
  fixture.library.push_status("ready");
  REQUIRE(eventually([&] { return threw.load() == 2 && after.load() == 2; }));
}

// The exchange that reactor_destroy cannot reach. `reactor_fetch_jwt` takes no
// handle, so destroying the client neither cancels the request nor waits for its
// completion — and the phase used to be owned by that completion alone. If it
// never came, the connect() future stayed unresolved for the life of the process
// and the exchange was never freed.
TEST_CASE("destroying a client settles a connect stuck in the token exchange") {
  Fixture fixture;
  fixture.library.answer_token = false;

  auto client = std::make_unique<reactor::Reactor>("reactor/helios", reactor::ApiKey{"key"});
  auto future = client->connect();
  REQUIRE(eventually([&] { return fixture.library.token_requests == 1; }));

  client.reset();

  REQUIRE(future.wait_for(2s) == std::future_status::ready);
  CHECK_THROWS_AS(future.get(), reactor::AbortedError);
  // Nothing was created: the exchange never got far enough to need a handle.
  CHECK(fixture.library.creates == 0);
}

// And the ordinary path still works: the token arrives, the connect follows it.
TEST_CASE("a completion that arrives after teardown touches nothing") {
  Fixture fixture;
  fixture.library.answer_token = false;

  auto client = std::make_unique<reactor::Reactor>("reactor/helios", reactor::ApiKey{"key"});
  auto future = client->connect();
  REQUIRE(eventually([&] { return fixture.library.token_requests == 1; }));

  // Kept, so the completion can be fired by hand after the client is gone.
  auto* completion = fixture.library.token_completion;
  void* userdata = fixture.library.token_userdata;
  REQUIRE(completion != nullptr);

  client.reset();
  REQUIRE(future.wait_for(2s) == std::future_status::ready);
  CHECK_THROWS_AS(future.get(), reactor::AbortedError);

  // The late completion: it must find nothing to touch, and free its own ticket.
  completion(1, R"({"jwt":"minted-token"})", nullptr, userdata);
  CHECK(fixture.library.creates == 0);
}

TEST_CASE("a removed subscription stops receiving") {
  Fixture fixture;
  reactor::Reactor client{"reactor/helios", reactor::Jwt{"token"}};
  client.connect().get();

  std::atomic<int> received{0};
  {
    auto subscription = client.on_status([&](reactor::Status) { ++received; });
    fixture.library.push_status("ready");
    REQUIRE(eventually([&] { return received.load() == 1; }));
  }  // unsubscribed here

  fixture.library.push_status("waiting");
  fixture.library.join_all();
  // Nothing more can arrive; a short wait would only prove the race is slow.
  CHECK(eventually([&] { return received.load() == 1; }, 100ms));
  CHECK(received.load() == 1);
}

// A host with a loop of its own takes delivery instead of the SDK's thread. What
// must not change is the future: it is settled on the FFI's own thread, so a
// caller that blocks its loop in .get() cannot deadlock against the executor.
TEST_CASE("an executor takes control events, and never the futures") {
  Fixture fixture;

  std::mutex mutex;
  std::vector<std::function<void()>> queued;

  reactor::Options options;
  options.executor = [&](std::function<void()> work) {
    const std::lock_guard<std::mutex> lock(mutex);
    queued.push_back(std::move(work));
  };

  reactor::Reactor client{"reactor/helios", reactor::Jwt{"token"}, std::move(options)};

  // Nothing pumps the executor here. If the future needed it, this would hang.
  client.connect().get();

  std::atomic<int> received{0};
  auto subscription = client.on_status([&](reactor::Status) { ++received; });
  fixture.library.push_status("ready");

  REQUIRE(eventually([&] {
    const std::lock_guard<std::mutex> lock(mutex);
    return !queued.empty();
  }));
  CHECK(received.load() == 0);  // not until the host runs it

  std::vector<std::function<void()>> to_run;
  {
    const std::lock_guard<std::mutex> lock(mutex);
    to_run = std::move(queued);
    queued.clear();
  }
  for (auto& work : to_run) {
    work();
  }
  CHECK(received.load() == 1);
}

// The on_error payload and a failed call are the same type, so an error listener
// can branch on exactly what a caller catching an exception would.
TEST_CASE("an error event arrives as the typed error") {
  Fixture fixture;
  reactor::Reactor client{"reactor/helios", reactor::Jwt{"token"}};
  client.connect().get();

  std::mutex mutex;
  std::vector<std::string> codes;
  std::atomic<bool> recoverable{false};
  auto subscription = client.on_error([&](const reactor::ReactorError& error) {
    const std::lock_guard<std::mutex> lock(mutex);
    codes.push_back(error.code());
    recoverable = error.recoverable();
    CHECK(dynamic_cast<const reactor::TransportError*>(&error) != nullptr);
  });

  fixture.library.push_error(R"({"code":"TRANSPORT_ERROR","message":"the peer connection dropped",)"
                             R"("recoverable":true,"timestamp_ms":1700000000000})");

  REQUIRE(eventually([&] {
    const std::lock_guard<std::mutex> lock(mutex);
    return !codes.empty();
  }));
  const std::lock_guard<std::mutex> lock(mutex);
  CHECK(codes.front() == "TRANSPORT_ERROR");
  CHECK(recoverable.load());
}

TEST_CASE("reconnect without a session says so, rather than asking the library") {
  Fixture fixture;
  reactor::Reactor client{"reactor/helios", reactor::Jwt{"token"}};

  try {
    client.reconnect().get();
    FAIL("reconnect with no session must fail");
  } catch (const reactor::InvalidStateError& error) {
    CHECK(std::string{error.what()}.find("connect() first") != std::string::npos);
  }
  CHECK(fixture.library.creates == 0);
}

TEST_CASE("disconnecting a client that never connected is not a failure") {
  Fixture fixture;
  reactor::Reactor client{"reactor/helios", reactor::Jwt{"token"}};

  CHECK_NOTHROW(client.disconnect().get());
  CHECK(fixture.library.creates == 0);
}

TEST_CASE("a session id is read through the library and freed") {
  Fixture fixture;
  reactor::Reactor client{"reactor/helios", reactor::Jwt{"token"}};
  client.connect().get();

  const auto id = client.session_id();
  REQUIRE(id.has_value());
  CHECK(id.value_or("") == "session-abc");
}

TEST_CASE("the status enum round-trips through its wire spelling") {
  for (const auto status : {reactor::Status::Disconnected, reactor::Status::Connecting,
                            reactor::Status::Waiting, reactor::Status::Ready}) {
    CHECK(reactor::status_from_string(reactor::to_string(status)) == status);
  }
  // Anything unrecognised reads as the least capable state, never the most.
  CHECK(reactor::status_from_string("teleporting") == reactor::Status::Disconnected);
  CHECK(reactor::status_from_string("") == reactor::Status::Disconnected);
}
