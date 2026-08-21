// A list of handlers for one event, with ids so a Subscription can take one back.
#pragma once

#include <cstdint>
#include <exception>
#include <functional>
#include <mutex>
#include <string>
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

  void remove(std::uint64_t id) {
    const std::lock_guard<std::mutex> lock(mutex_);
    for (auto it = handlers_.begin(); it != handlers_.end(); ++it) {
      if (it->first == id) {
        handlers_.erase(it);
        return;
      }
    }
  }

  /// Call every handler with `args`.
  ///
  /// Over a copy, so a handler is free to register or remove one — including its
  /// own subscription — without invalidating the iteration or deadlocking on the
  /// lock. The cost is a copy of a small vector of `std::function`s per event,
  /// and control events are low-rate by construction.
  template <typename... Called>
  void invoke(Called&&... args) const {
    std::vector<std::pair<std::uint64_t, Handler>> snapshot;
    {
      const std::lock_guard<std::mutex> lock(mutex_);
      snapshot = handlers_;
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
  }

  bool empty() const {
    const std::lock_guard<std::mutex> lock(mutex_);
    return handlers_.empty();
  }

 private:
  mutable std::mutex mutex_;
  std::uint64_t next_id_ = 1;
  std::vector<std::pair<std::uint64_t, Handler>> handlers_;
};

}  // namespace reactor::detail
