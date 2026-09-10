// White-box tests for detail::Handlers, reaching past <reactor/…> on purpose
// (see this directory's CMakeLists.txt on why that's the point of this suite).
#include "detail/handlers.hpp"

#include <atomic>
#include <chrono>
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

// A per-thread exemption in remove() — "don't wait for an invoke() this
// thread is already inside" — is enough to fix the case above, but not
// enough on its own: two threads, each mid-callback on a *different*
// Handlers instance, each removing a subscription the *other*'s callback
// belongs to, would each look like a safe-to-wait-for outsider to the
// other's remove(). Both would then wait for the other's invoke() to finish,
// and neither can, because each is itself the thing blocking the other.
//
// remove() closes this by never waiting at all when the calling thread is
// nested inside any callback, not just this list's own — see its own
// comment. This test exercises exactly the cycle above; before that fix, it
// deadlocks instead of finishing.
//
// Plain std::thread, not std::async/std::future: a future from
// std::launch::async blocks in its *destructor* until the task finishes, so
// if the two threads really were deadlocked, unwinding past a timed-out
// REQUIRE would hang the test process anyway, just a few seconds later than
// without the bound at all. Polling an atomic flag and detaching on timeout
// is what actually lets a regression here fail the assertion and move on.
TEST_CASE(
    "remove() does not deadlock when two threads are each mid-callback removing the "
    "other's subscription") {
  Handlers<int> a;
  Handlers<int> b;

  const std::uint64_t target_in_a = a.add([](int) {});
  const std::uint64_t target_in_b = b.add([](int) {});

  std::mutex gate;
  std::condition_variable gate_cv;
  bool a_is_mid_callback = false;
  bool b_is_mid_callback = false;

  a.add([&](int) {
    {
      const std::lock_guard<std::mutex> lock(gate);
      a_is_mid_callback = true;
    }
    gate_cv.notify_all();
    {
      std::unique_lock<std::mutex> lock(gate);
      gate_cv.wait(lock, [&] { return b_is_mid_callback; });
    }
    b.remove(target_in_b);
  });

  b.add([&](int) {
    {
      const std::lock_guard<std::mutex> lock(gate);
      b_is_mid_callback = true;
    }
    gate_cv.notify_all();
    {
      std::unique_lock<std::mutex> lock(gate);
      gate_cv.wait(lock, [&] { return a_is_mid_callback; });
    }
    a.remove(target_in_a);
  });

  std::atomic<bool> a_done{false};
  std::atomic<bool> b_done{false};
  std::thread thread_a([&] {
    a.invoke(1);
    a_done = true;
  });
  std::thread thread_b([&] {
    b.invoke(1);
    b_done = true;
  });

  const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
  while ((!a_done || !b_done) && std::chrono::steady_clock::now() < deadline) {
    std::this_thread::sleep_for(std::chrono::milliseconds(5));
  }
  const bool finished_in_time = a_done && b_done;

  if (finished_in_time) {
    thread_a.join();
    thread_b.join();
  } else {
    // Deliberately abandoned, not joined: a regression here means these two
    // are genuinely deadlocked forever, and joining (or letting a
    // std::jthread-style destructor join) would just hang this test process
    // instead of failing it.
    thread_a.detach();
    thread_b.detach();
  }

  REQUIRE(finished_in_time);
}

// The wait remove() added is only correct if it waits for exactly the
// invoke() copies already taken at erase time — not however many more start
// afterward. A first version of the fix waited for a live in-flight count to
// hit zero instead, which is a different, broader condition: a second
// invoke() call, on another thread, whose snapshot is taken *after* the
// handler was already erased (and so cannot possibly contain it) still counts
// toward that live total. Deterministic, not flooding-based: that second call
// is deliberately never released, so if remove() waits for it too, it hangs
// forever rather than just occasionally running long.
TEST_CASE("remove() does not wait for invoke() copies that start after it erases the handler") {
  Handlers<int> handlers;

  std::mutex gate_one;
  std::condition_variable gate_one_cv;
  bool call_one_entered = false;
  bool release_call_one = false;
  std::atomic<bool> call_one_handler_consumed{false};

  // Call #1's snapshot includes `id`, taken before it is erased below — the
  // one remove() legitimately has to wait for. Blocks so that wait has
  // something real to do, rather than remove() finding nothing in flight at
  // all.
  //
  // Guarded by `call_one_handler_consumed`, not called unconditionally: this
  // handler stays registered (nothing here ever removes it) for the rest of
  // the test, so call #2 below has it in its own snapshot too. Only the
  // first invocation — call #1's — is meant to block; a second one calling
  // in must return immediately, or call #2 would never reach its own handler
  // to signal `call_two_entered`.
  handlers.add([&](int) {
    if (call_one_handler_consumed.exchange(true)) {
      return;
    }
    {
      const std::lock_guard<std::mutex> lock(gate_one);
      call_one_entered = true;
    }
    gate_one_cv.notify_all();
    std::unique_lock<std::mutex> lock(gate_one);
    gate_one_cv.wait(lock, [&] { return release_call_one; });
  });

  const std::uint64_t id = handlers.add([](int) {});

  std::thread call_one([&] { handlers.invoke(1); });
  {
    std::unique_lock<std::mutex> lock(gate_one);
    gate_one_cv.wait(lock, [&] { return call_one_entered; });
  }

  std::atomic<bool> remove_done{false};
  std::thread remover([&] {
    handlers.remove(id);
    remove_done = true;
  });

  // Lets remove() erase `id` and enter its wait before call #2 exists below —
  // both a handful of instructions under a lock, several orders of magnitude
  // faster than this pause, the same reasoning every "wait for a flag" in
  // this file relies on to not itself be a race.
  std::this_thread::sleep_for(std::chrono::milliseconds(50));

  // Call #2: its snapshot is taken strictly after `id` was erased, so it
  // cannot contain it. Never released — remove() must not be waiting for
  // this one at all, so whether it ever finishes must not matter.
  std::mutex gate_two;
  std::condition_variable gate_two_cv;
  bool call_two_entered = false;
  handlers.add([&](int) {
    {
      const std::lock_guard<std::mutex> lock(gate_two);
      call_two_entered = true;
    }
    gate_two_cv.notify_all();
    std::this_thread::sleep_for(std::chrono::hours(1));
  });
  std::thread call_two([&] { handlers.invoke(1); });
  {
    std::unique_lock<std::mutex> lock(gate_two);
    gate_two_cv.wait(lock, [&] { return call_two_entered; });
  }

  // Release call #1. The fix under test: remover must finish promptly once
  // this alone is done, regardless of call #2 still being (deliberately)
  // stuck.
  {
    const std::lock_guard<std::mutex> lock(gate_one);
    release_call_one = true;
  }
  gate_one_cv.notify_all();

  const auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
  while (!remove_done && std::chrono::steady_clock::now() < deadline) {
    std::this_thread::sleep_for(std::chrono::milliseconds(5));
  }

  call_one.join();
  if (remove_done) {
    remover.join();
  } else {
    // Deliberately abandoned: a regression here means remove() really is
    // waiting on call #2, which never finishes by design, so joining would
    // hang this test process instead of failing it.
    remover.detach();
  }
  call_two.detach();  // Never finishes by design; nothing here waits for it.

  REQUIRE(remove_done);
}
