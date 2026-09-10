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
#include <utility>
#include <vector>

#include "detail/log.hpp"

namespace reactor::detail {

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
  /// Exempted: an `invoke()` this very thread is already inside. That is the
  /// self-removal case `invoke()`'s own comment describes — a handler dropping
  /// its own subscription — and waiting for it here would be waiting for this
  /// thread to finish what it is presently doing, forever.
  void remove(std::uint64_t id) {
    std::unique_lock<std::mutex> lock(mutex_);
    for (auto it = handlers_.begin(); it != handlers_.end(); ++it) {
      if (it->first == id) {
        handlers_.erase(it);
        break;
      }
    }
    const auto own = [this] {
      const auto found = in_flight_.find(std::this_thread::get_id());
      return found == in_flight_.end() ? std::size_t{0} : found->second;
    }();
    idle_.wait(lock, [this, own] { return total_in_flight() <= own; });
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
    const auto this_thread = std::this_thread::get_id();
    {
      const std::lock_guard<std::mutex> lock(mutex_);
      snapshot = handlers_;
      // Marks this copy as outstanding *before* the lock that guards
      // `handlers_` is released, so a `remove()` that acquires that lock next
      // is guaranteed to see it and wait — the ordering `remove()`'s own
      // comment relies on.
      ++in_flight_[this_thread];
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
      const auto found = in_flight_.find(this_thread);
      if (--found->second == 0) {
        in_flight_.erase(found);
      }
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
  /// Callers hold `mutex_` already; this only reads what it protects.
  std::size_t total_in_flight() const {
    std::size_t total = 0;
    for (const auto& [thread_id, count] : in_flight_) {
      (void)thread_id;
      total += count;
    }
    return total;
  }

  mutable std::mutex mutex_;
  mutable std::condition_variable idle_;
  /// In-flight `invoke()` copies, counted per thread so `remove()` can exclude
  /// the copy it is itself running inside of — see `remove()`'s own comment.
  mutable std::unordered_map<std::thread::id, std::size_t> in_flight_;
  std::uint64_t next_id_ = 1;
  std::vector<std::pair<std::uint64_t, Handler>> handlers_;
};

}  // namespace reactor::detail
