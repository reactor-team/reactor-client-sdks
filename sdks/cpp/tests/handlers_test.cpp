// White-box tests for detail::Handlers, reaching past <reactor/…> on purpose
// (see this directory's CMakeLists.txt on why that's the point of this suite).
#include "detail/handlers.hpp"

#include <condition_variable>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <catch2/catch_test_macros.hpp>

using reactor::detail::Handlers;

// remove() used to only erase a handler from the list that future invoke()
// calls copy from — it did nothing about a copy invoke() had already taken on
// another thread a moment earlier, which would go on to call the handler
// regardless of the removal. A caller whose ordinary shape is "a Subscription
// member goes out of scope right before the object owning it does" would then
// have that stale copy call into memory that had, by then, stopped existing.
//
// Deterministic on purpose, not timing-based: a first handler blocks
// invoke()'s iteration open on the dispatching thread until this test has had
// a chance to call remove() concurrently, so this reproduces the race every
// run rather than one run in a thousand.
TEST_CASE("remove() waits out a delivery already in flight on another thread") {
  Handlers<int> handlers;

  std::mutex gate;
  std::condition_variable gate_cv;
  bool blocker_entered = false;
  bool release_blocker = false;

  // Holds invoke()'s for-loop open on the dispatching thread until this test
  // releases it below — long enough to call remove() while the snapshot
  // (with the handler under test still in it) is still being delivered.
  handlers.add([&](int) {
    {
      const std::lock_guard<std::mutex> lock(gate);
      blocker_entered = true;
    }
    gate_cv.notify_all();
    std::unique_lock<std::mutex> lock(gate);
    gate_cv.wait(lock, [&] { return release_blocker; });
  });

  // The handler under test: captures state local to this test, the same
  // shape as a real caller's Subscription member and the object it belongs
  // to going out of scope together.
  std::vector<std::string> names;
  const std::uint64_t id = handlers.add([&](int) { names.push_back("delivered"); });

  std::thread dispatcher([&] { handlers.invoke(1); });

  // Wait for invoke() to have copied the handler list (id included) and
  // entered the blocking handler above — not a fixed sleep, so this cannot
  // flake by running on a slow machine.
  {
    std::unique_lock<std::mutex> lock(gate);
    gate_cv.wait(lock, [&] { return blocker_entered; });
  }

  // Let the dispatching thread's loop proceed, then race it: remove() the
  // handler under test right away, from this thread — exactly the shape of
  // the real bug, a Subscription destructing on one thread while invoke()'s
  // already-taken copy is still being delivered on another.
  {
    const std::lock_guard<std::mutex> lock(gate);
    release_blocker = true;
  }
  gate_cv.notify_all();

  // The fix under test: this must not return before the stale copy's call to
  // `id`'s handler has already completed — so that by the time it does, it is
  // safe to destroy `names` and go home. Before the fix, this returned
  // immediately (a plain erase, no wait), almost always well before the
  // dispatching thread — just woken from a condition wait — got anywhere
  // near calling the handler.
  handlers.remove(id);

  CHECK(names.size() == 1);
  CHECK(names.front() == "delivered");

  dispatcher.join();
}
