// A list of handlers for one event, with ids so a Subscription can take one back.
#pragma once

#include <condition_variable>
#include <cstdint>
#include <exception>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

#include "detail/log.hpp"

namespace reactor::detail {

/// Cross-instance bookkeeping for `Handlers<...>::remove()`'s wait, so two
/// removals on two different `Handlers` instances (different `Args...`
/// included — this is deliberately not a class template member) can tell
/// whether waiting for each other would deadlock.
///
/// `remove()` records, for the calling thread, exactly which *other* threads'
/// in-flight `invoke()` calls it is about to wait for. Before doing so, it
/// checks whether any of those threads is transitively waiting (through this
/// same map) on the calling thread — if so, waiting for it here would close a
/// cycle neither side can ever break, so that one thread is left out of the
/// wait instead. See `remove()`'s own comment for what that costs.
inline std::mutex& blocking_registry_mutex() {
  static std::mutex mutex;
  return mutex;
}

inline std::unordered_map<std::thread::id, std::unordered_set<std::thread::id>>&
blocked_on_threads() {
  static std::unordered_map<std::thread::id, std::unordered_set<std::thread::id>> map;
  return map;
}

/// Whether `target` is reachable from `start` by following "this thread is
/// presently waiting on that thread" edges recorded in `blocked_on_threads()`
/// — including `start == target` itself, a degenerate one-node cycle (the
/// shape a thread waiting on its own in-flight invocation takes). Caller must
/// already hold `blocking_registry_mutex()`.
inline bool reaches(std::thread::id start, std::thread::id target) {
  if (start == target) {
    return true;
  }
  std::unordered_set<std::thread::id> visited;
  std::vector<std::thread::id> stack{start};
  while (!stack.empty()) {
    const std::thread::id current = stack.back();
    stack.pop_back();
    if (!visited.insert(current).second) {
      continue;
    }
    if (current == target) {
      return true;
    }
    const auto it = blocked_on_threads().find(current);
    if (it == blocked_on_threads().end()) {
      continue;
    }
    for (const std::thread::id next : it->second) {
      stack.push_back(next);
    }
  }
  return false;
}

template <typename... Args>
class Handlers {
 public:
  using Handler = std::function<void(Args...)>;

  /// Register, and return the id that removes it again.
  std::uint64_t add(Handler handler) {
    const std::lock_guard<std::mutex> lock(mutex_);
    const std::uint64_t id = next_id_++;
    handlers_.emplace_back(id, std::move(handler));
    return id;
  }

  /// Remove the handler, and block until it is safe for the caller to destroy
  /// whatever it captured.
  ///
  /// `invoke()` below calls handlers from a *copy* of the list, taken once and
  /// then run without the lock. Erasing `id` here stops it from appearing in
  /// any *future* copy, but does nothing about a copy `invoke()` already took
  /// on another thread a moment ago — that copy still holds this handler, and
  /// will call it regardless. A caller that erases here and then destroys the
  /// handler's captures (the ordinary shape: a `Subscription` member going out
  /// of scope right before the object it belongs to) would be destroying them
  /// out from under a delivery already on its way in. So this also waits for
  /// every `invoke()` call already in flight — on any *other* thread — to
  /// finish before returning, one thread at a time: `started_by_thread_` is
  /// snapshotted here, and the wait is over exactly those threads' counts
  /// reaching their snapshotted value in `finished_by_thread_`, so an
  /// invocation that starts afterward (on any thread, including this one)
  /// never has to be waited for.
  ///
  /// This thread's own outstanding count is always excluded from that
  /// snapshot before waiting — the self-removal case `invoke()`'s own comment
  /// describes (a handler dropping its own subscription) would otherwise wait
  /// for this thread to finish what it is presently doing, forever.
  ///
  /// The same reasoning extends across threads: two threads, each mid-callback
  /// on (possibly different) `Handlers` instances, each removing a
  /// subscription the *other*'s already-taken copy holds, would otherwise each
  /// wait for the other's `invoke()` to finish — and neither can, because each
  /// is itself the thing blocking the other. `blocked_on_threads()` (shared
  /// across every instance and instantiation, for the same reason
  /// `invoke_nesting_depth()` used to be) catches exactly this: before
  /// waiting on a thread, this checks whether that thread is already,
  /// transitively, waiting on this one — and if so, leaves it out.
  ///
  /// The tradeoff that leaves: in that specific cyclic shape, one of the two
  /// removals — whichever loses the race to register first — returns without
  /// having waited for the other's in-flight copy. That is narrower than it
  /// sounds: it takes two threads genuinely blocked on each other to trigger,
  /// and the *other* side of the pair still waits for real, so the cycle is
  /// broken rather than either side's guarantee being dropped wholesale. Any
  /// invocation on a thread that is not itself waiting on this one — the
  /// overwhelmingly common case, including an unrelated thread just delivering
  /// normally — is always waited for.
  void remove(std::uint64_t id) {
    const std::thread::id self = std::this_thread::get_id();
    std::unique_lock<std::mutex> lock(mutex_);
    for (auto it = handlers_.begin(); it != handlers_.end(); ++it) {
      if (it->first == id) {
        handlers_.erase(it);
        break;
      }
    }
    std::unordered_map<std::thread::id, std::uint64_t> target = started_by_thread_;
    lock.unlock();

    {
      const std::lock_guard<std::mutex> registry_lock(blocking_registry_mutex());
      for (auto it = target.begin(); it != target.end();) {
        if (reaches(it->first, self)) {
          it = target.erase(it);
        } else {
          ++it;
        }
      }
      if (target.empty()) {
        return;
      }
      std::unordered_set<std::thread::id> waiting_for;
      waiting_for.reserve(target.size());
      for (const auto& [thread_id, count] : target) {
        (void)count;
        waiting_for.insert(thread_id);
      }
      blocked_on_threads()[self] = std::move(waiting_for);
    }

    lock.lock();
    idle_.wait(lock, [this, &target] {
      for (const auto& [thread_id, count] : target) {
        const auto it = finished_by_thread_.find(thread_id);
        const std::uint64_t finished = it == finished_by_thread_.end() ? 0 : it->second;
        if (finished < count) {
          return false;
        }
      }
      return true;
    });
    lock.unlock();

    const std::lock_guard<std::mutex> registry_lock(blocking_registry_mutex());
    blocked_on_threads().erase(self);
  }

  /// Call every handler with `args`.
  ///
  /// Over a copy, so a handler is free to register or remove one — including its
  /// own subscription — without invalidating the iteration or deadlocking on the
  /// lock. The cost is a copy of a small vector of `std::function`s per event,
  /// and control events are low-rate by construction.
  ///
  /// `args` is deliberately never forwarded, forwarding reference and all:
  /// forwarding it would let the *first* handler in the loop below move from it
  /// (whenever `Called` deduces to an rvalue reference), leaving every handler
  /// after it reading a moved-from value. One delivery calling N handlers must
  /// give each of them the same `args`, which plain, repeatable-by-value passing
  /// does and a single forward cannot.
  template <typename... Called>
  void invoke(Called&&... args) const {  // NOLINT(cppcoreguidelines-missing-std-forward)
    const std::thread::id self = std::this_thread::get_id();
    std::vector<std::pair<std::uint64_t, Handler>> snapshot;
    {
      const std::lock_guard<std::mutex> lock(mutex_);
      snapshot = handlers_;
      // Marks this copy as started *before* the lock that guards `handlers_`
      // is released, so a `remove()` that acquires that lock next reads a
      // `started_by_thread_` that already counts this copy — the ordering
      // `remove()`'s own comment relies on.
      ++started_by_thread_[self];
    }
    for (const auto& [id, handler] : snapshot) {
      (void)id;
      // Per handler, so one caller's bug does not silence the others registered
      // for the same event — and so the exception never reaches the thread that
      // called us, where it would be an uncaught exception rather than a mistake
      // in one callback.
      try {
        handler(args...);
      } catch (const std::exception& error) {
        log_warn_once(std::string{"handler-threw:"} + error.what(),
                      std::string{"an event handler threw and the event was delivered to the "
                                  "remaining handlers anyway: "} +
                          error.what());
      } catch (...) {
        log_warn_once("handler-threw:unknown",
                      "an event handler threw something that is not a std::exception, and the "
                      "event was delivered to the remaining handlers anyway");
      }
    }
    {
      const std::lock_guard<std::mutex> lock(mutex_);
      ++finished_by_thread_[self];
    }
    // Outside the lock: a `remove()` waiting in `idle_.wait` re-acquires it
    // itself, and there is nothing left for it to see here.
    idle_.notify_all();
  }

  bool empty() const {
    const std::lock_guard<std::mutex> lock(mutex_);
    return handlers_.empty();
  }

 private:
  mutable std::mutex mutex_;
  mutable std::condition_variable idle_;
  /// Per thread, how many `invoke()` copies it has taken, and how many of its
  /// own it has finished calling every handler in — see `remove()`'s own
  /// comment on why `remove()` only ever waits for a given thread's
  /// `finished_by_thread_` to catch up to a `started_by_thread_` captured at
  /// erase time, not to reach whatever that thread's count is by the time it
  /// is checked, and why its own thread's count is never waited for at all.
  mutable std::unordered_map<std::thread::id, std::uint64_t> started_by_thread_;
  mutable std::unordered_map<std::thread::id, std::uint64_t> finished_by_thread_;
  std::uint64_t next_id_ = 1;
  std::vector<std::pair<std::uint64_t, Handler>> handlers_;
};

}  // namespace reactor::detail
