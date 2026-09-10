// A list of handlers for one event, with ids so a Subscription can take one back.
#pragma once

#include <condition_variable>
#include <cstdint>
#include <exception>
#include <functional>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

#include "detail/log.hpp"

namespace reactor::detail {

/// How many `Handlers<...>::invoke()` calls — of any specialization, not just
/// one — the calling thread is presently nested inside.
///
/// A function-local `static thread_local`, not a namespace-scope variable:
/// still exactly one instance per thread, shared across every translation
/// unit that includes this header (inline function, C++17), but without
/// exposing a global for anything else to reach into.
///
/// Shared across every `Handlers<...>` instantiation on purpose: two different
/// handler lists (say, the video handlers for one track and the status
/// handlers) can each have a thread inside a callback that destroys a
/// subscription belonging to the *other* list. Per-instance bookkeeping alone
/// cannot see that — each side would look, to its own `remove()`, like an
/// unrelated, safe-to-wait-for-outsider thread — and the two would wait on
/// each other forever. This is checked instead: a thread already inside any
/// callback never blocks in `remove()`, full stop — see `remove()`'s comment.
inline int& invoke_nesting_depth() {
  static thread_local int depth = 0;
  return depth;
}

/// RAII around `invoke_nesting_depth()`, so a handler that throws still
/// leaves it correct. (`invoke()` itself never lets a handler's exception
/// escape it — see its own comment — but this does not depend on that.)
class InvokeNestingGuard {
 public:
  InvokeNestingGuard() { ++invoke_nesting_depth(); }
  ~InvokeNestingGuard() { --invoke_nesting_depth(); }
  InvokeNestingGuard(const InvokeNestingGuard&) = delete;
  InvokeNestingGuard& operator=(const InvokeNestingGuard&) = delete;
  InvokeNestingGuard(InvokeNestingGuard&&) = delete;
  InvokeNestingGuard& operator=(InvokeNestingGuard&&) = delete;
};

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
  /// every `invoke()` call already in flight on another thread to finish
  /// before returning — after that, no copy containing this handler still
  /// exists anywhere.
  ///
  /// Not waited for: a thread already inside some callback when it calls
  /// this.
  ///
  /// The self-removal case `invoke()`'s own comment describes — a handler
  /// dropping its own subscription — is one instance of this: waiting here
  /// would wait for this thread to finish what it is presently doing,
  /// forever. But it is not the only one a per-thread exemption would need to
  /// cover: two threads each inside a callback, each destroying a
  /// subscription the *other*'s already-taken copy still holds, would
  /// otherwise each wait for the other's `invoke()` to finish — and neither
  /// can, because each is blocked on this very wait. Skipping the wait
  /// whenever the caller is nested inside *any* callback, not just checking
  /// whether it is this list's own, avoids that cycle: a thread that is not
  /// itself running a callback can never be the other half of one.
  ///
  /// The tradeoff this leaves: a callback that removes a *different*
  /// callback's subscription and destroys its captures immediately after
  /// keeps the original race for that one case. That is narrower than it
  /// sounds — it requires two callbacks in flight on two different threads at
  /// once, which most callers of this class never do — and it is the price of
  /// not deadlocking the far more common shape this fix targets: a
  /// `Subscription` member going out of scope on a thread that is not itself
  /// mid-callback, ordinary destructor teardown included.
  void remove(std::uint64_t id) {
    std::unique_lock<std::mutex> lock(mutex_);
    for (auto it = handlers_.begin(); it != handlers_.end(); ++it) {
      if (it->first == id) {
        handlers_.erase(it);
        break;
      }
    }
    if (invoke_nesting_depth() > 0) {
      return;
    }
    // Only invocations that had already taken their snapshot as of this exact
    // point can possibly still hold the handler just erased above — not any
    // that start afterward, which read `handlers_` after the erase and so
    // never see it. `started_` at this instant is that boundary: waiting for
    // `finished_` to reach it (not for it to reach whatever `started_` climbs
    // to later) is what keeps this from blocking forever under continuous or
    // overlapping delivery, which keeps incrementing `started_` the whole time
    // this waits.
    const std::uint64_t target = started_;
    idle_.wait(lock, [this, target] { return finished_ >= target; });
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
    std::vector<std::pair<std::uint64_t, Handler>> snapshot;
    {
      const std::lock_guard<std::mutex> lock(mutex_);
      snapshot = handlers_;
      // Marks this copy as started *before* the lock that guards `handlers_`
      // is released, so a `remove()` that acquires that lock next reads a
      // `started_` that already counts this copy — the ordering `remove()`'s
      // own comment relies on.
      ++started_;
    }
    const InvokeNestingGuard nesting_guard;
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
      ++finished_;
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
  /// How many `invoke()` copies have been taken, and how many have finished
  /// calling every handler in theirs — see `remove()`'s own comment on why
  /// `remove()` only ever waits for `finished_` to catch up to a `started_`
  /// captured at erase time, not to reach whatever `started_` is by the time
  /// it is checked.
  mutable std::uint64_t started_ = 0;
  mutable std::uint64_t finished_ = 0;
  std::uint64_t next_id_ = 1;
  std::vector<std::pair<std::uint64_t, Handler>> handlers_;
};

}  // namespace reactor::detail
