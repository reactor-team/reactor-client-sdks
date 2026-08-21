// The boundary itself: string ownership, the ABI guard, and the table that lets
// every later test hand the SDK a fake library.

#include "detail/ffi.hpp"

#include <cstdlib>
#include <cstring>
#include <string>
#include <type_traits>
#include <vector>

#include <catch2/catch_test_macros.hpp>

#include "detail/strings.hpp"
#include "reactor/errors.hpp"

namespace {

/// What a fake library was asked to free, and how many times.
///
/// A real `char*` from `std::malloc`, not a string literal: the free path is the
/// thing under test, and handing it something it must not free would only prove
/// the test was careful.
std::vector<std::string> g_freed;
std::uint32_t g_reported_abi = REACTOR_ABI_VERSION;

extern "C" {

void fake_free_string(char* s) {
  g_freed.emplace_back(s == nullptr ? "<null>" : s);
  std::free(s);  // NOLINT(cppcoreguidelines-no-malloc) — pairs with the strdup below
}

std::uint32_t fake_abi_version() { return g_reported_abi; }

}  // extern "C"

/// A table with only what these tests call. Everything else stays null, which is
/// exactly the point: a test that reaches an unimplemented call crashes on a null
/// pointer at the call site rather than quietly doing something plausible.
reactor::detail::Ffi fake_table() {
  reactor::detail::Ffi table;
  table.free_string = &fake_free_string;
  table.abi_version = &fake_abi_version;
  return table;
}

char* heap_copy(const char* text) {
  // NOLINTNEXTLINE(cppcoreguidelines-no-malloc) — the FFI hands over malloc'd
  // memory, and fake_free_string frees it the same way.
  char* copy = static_cast<char*>(std::malloc(std::strlen(text) + 1));
  std::strcpy(copy, text);  // NOLINT(clang-analyzer-security.insecureAPI.strcpy)
  return copy;
}

}  // namespace

TEST_CASE("an owned string is freed exactly once") {
  g_freed.clear();
  const auto table = fake_table();
  const reactor::detail::FfiOverride override{&table};

  {
    const reactor::detail::OwnedString owned{heap_copy("session-123")};
    CHECK(owned.has_value());
    CHECK(owned.view() == "session-123");
    CHECK(owned.to_string() == "session-123");
    CHECK(g_freed.empty());  // not before the scope ends
  }

  REQUIRE(g_freed.size() == 1);
  CHECK(g_freed.front() == "session-123");
}

TEST_CASE("a moved-from owned string does not free") {
  g_freed.clear();
  const auto table = fake_table();
  const reactor::detail::FfiOverride override{&table};

  {
    reactor::detail::OwnedString first{heap_copy("tracks")};
    const reactor::detail::OwnedString second{std::move(first)};

    CHECK_FALSE(first.has_value());
    CHECK(second.has_value());
    // Both go out of scope below; only one of them owns anything.
  }

  CHECK(g_freed.size() == 1);
}

TEST_CASE("move-assignment frees what it displaces, and never itself") {
  g_freed.clear();
  const auto table = fake_table();
  const reactor::detail::FfiOverride override{&table};

  {
    reactor::detail::OwnedString target{heap_copy("old")};
    reactor::detail::OwnedString source{heap_copy("new")};
    target = std::move(source);

    REQUIRE(g_freed.size() == 1);
    CHECK(g_freed.front() == "old");
    CHECK(target.view() == "new");
  }

  REQUIRE(g_freed.size() == 2);
  CHECK(g_freed.back() == "new");
}

TEST_CASE("a null return is a value, not a failure") {
  g_freed.clear();
  const auto table = fake_table();
  const reactor::detail::FfiOverride override{&table};

  {
    // What reactor_session_id returns with no session, and every getter returns
    // for a null handle. Documented, ordinary, and not something to free.
    const reactor::detail::OwnedString absent{nullptr};
    CHECK_FALSE(absent.has_value());
    CHECK(absent.view().empty());
    CHECK(absent.to_string().empty());
  }

  CHECK(g_freed.empty());
}

TEST_CASE("reset is idempotent") {
  g_freed.clear();
  const auto table = fake_table();
  const reactor::detail::FfiOverride override{&table};

  reactor::detail::OwnedString owned{heap_copy("x")};
  owned.reset();
  owned.reset();
  owned.reset();

  CHECK(g_freed.size() == 1);
}

// reactor_status returns a pointer to a static literal. Freeing it corrupts the
// heap, so the type carrying it has no way to free anything — asserted here
// rather than left to a comment, because the comment is what failed last time.
TEST_CASE("a static string cannot free") {
  static_assert(std::is_trivially_destructible_v<reactor::detail::StaticString>,
                "StaticString must not run code when it goes out of scope: a "
                "destructor is the only place a free could hide");

  g_freed.clear();
  const auto table = fake_table();
  const reactor::detail::FfiOverride override{&table};

  {
    const reactor::detail::StaticString status{"ready"};
    CHECK(status.view() == "ready");
    CHECK(status.to_string() == "ready");
  }

  CHECK(g_freed.empty());
}

// The one check with no substitute. A function that gained a parameter still
// links and still resolves, then corrupts the stack at the call — which looks
// like a hang, not a version error.
TEST_CASE("a library speaking a different ABI is refused, loudly") {
  auto table = fake_table();
  g_reported_abi = REACTOR_ABI_VERSION + 7;
  const reactor::detail::FfiOverride override{&table};

  try {
    reactor::detail::require_supported_abi(table);
    FAIL("a mismatched ABI version must be refused");
  } catch (const reactor::VersionMismatchError& error) {
    const std::string message = error.what();
    // Both numbers, or the message sends the reader to the wrong place.
    CHECK(message.find(std::to_string(REACTOR_ABI_VERSION + 7)) != std::string::npos);
    CHECK(message.find(std::to_string(REACTOR_ABI_VERSION)) != std::string::npos);
    // And what to do about it.
    CHECK(message.find("cargo build -p reactor-ffi --release") != std::string::npos);
  }

  g_reported_abi = REACTOR_ABI_VERSION;
}

TEST_CASE("a matching ABI passes without comment") {
  const auto table = fake_table();
  g_reported_abi = REACTOR_ABI_VERSION;
  CHECK_NOTHROW(reactor::detail::require_supported_abi(table));
}

// The real library agrees with its own header: this is the assertion that the
// version macro and the compiled constant have not drifted, made from the side
// that actually loads the library.
TEST_CASE("the linked library speaks the ABI these headers describe") {
  CHECK(reactor::detail::ffi().abi_version() == REACTOR_ABI_VERSION);
}

// Four functions in the table share one signature — publish, unpublish's
// neighbours, pause and resume — so a positional mistake would compile and a
// binding would pause a track when asked to publish it. The X-macro makes that
// impossible by construction; these spot-checks are what prove the construction.
TEST_CASE("the table points each name at its own symbol") {
  const auto& table = reactor::detail::ffi();

  CHECK(table.publish_track == &reactor_publish_track);
  CHECK(table.pause_track == &reactor_pause_track);
  CHECK(table.resume_track == &reactor_resume_track);
  CHECK(table.connect == &reactor_connect);
  CHECK(table.disconnect == &reactor_disconnect);
  CHECK(table.reconnect == &reactor_reconnect);
  CHECK(table.status == &reactor_status);
  CHECK(table.session_id == &reactor_session_id);
  CHECK(table.tracks == &reactor_tracks);
  CHECK(table.paused_tracks == &reactor_paused_tracks);
  CHECK(table.free_string == &reactor_free_string);
  CHECK(table.create_with_adm == &reactor_create_with_adm);
}

TEST_CASE("an override restores the previous table, even nested") {
  const auto outer = fake_table();
  const auto inner = fake_table();

  const reactor::detail::Ffi* before = &reactor::detail::ffi();
  {
    const reactor::detail::FfiOverride first{&outer};
    CHECK(&reactor::detail::ffi() == &outer);
    {
      const reactor::detail::FfiOverride second{&inner};
      CHECK(&reactor::detail::ffi() == &inner);
    }
    CHECK(&reactor::detail::ffi() == &outer);
  }
  CHECK(&reactor::detail::ffi() == before);
}

// Reading through the real library, with a real handle-less call: the getters
// accept null and say so, which is what lets a client report "disconnected"
// before it has ever connected.
TEST_CASE("the real library's getters answer for a null handle") {
  const auto& table = reactor::detail::ffi();

  const reactor::detail::StaticString status{table.status(nullptr)};
  CHECK(status.view() == "disconnected");

  const reactor::detail::OwnedString session{table.session_id(nullptr)};
  CHECK_FALSE(session.has_value());

  const reactor::detail::OwnedString tracks{table.tracks(nullptr)};
  CHECK_FALSE(tracks.has_value());
}
