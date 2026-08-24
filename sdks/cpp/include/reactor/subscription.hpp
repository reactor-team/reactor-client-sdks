// What an event handler registration hands back.
#pragma once

#include <functional>
#include <utility>

namespace reactor {

/// Cancels one event handler registration.
///
/// The one place this SDK's surface differs from the Python one, which offers
/// `off(event, handler)`. Two `std::function`s cannot be compared, so a C++
/// binding has no way to find "the handler you passed earlier" — a token is the
/// only honest answer, and it doubles as RAII:
///
///     {
///       auto sub = reactor.on_status([](auto s) { … });
///       …                                  // handler live
///     }                                    // and unregistered here
///
/// Discarding the return value therefore unregisters immediately, which is
/// usually a bug and always a quiet one — so hold it, or call `detach()` to say
/// the handler should live as long as the client does.
class Subscription {
 public:
  Subscription() noexcept = default;

  /// Wraps the removal the event source handed over.
  explicit Subscription(std::function<void()> remove) noexcept : remove_(std::move(remove)) {}

  ~Subscription() { remove(); }

  Subscription(Subscription&& other) noexcept : remove_(std::exchange(other.remove_, nullptr)) {}

  Subscription& operator=(Subscription&& other) noexcept {
    if (this != &other) {
      remove();
      remove_ = std::exchange(other.remove_, nullptr);
    }
    return *this;
  }

  Subscription(const Subscription&) = delete;
  Subscription& operator=(const Subscription&) = delete;

  /// Unregister now. Idempotent, and safe after the client is gone.
  void remove() noexcept {
    if (remove_) {
      const auto remover = std::exchange(remove_, nullptr);
      // This runs from a destructor, and a throwing destructor takes the process
      // with it. The removal is best-effort by nature: what it removes from may
      // already be gone, which is the case it is written to tolerate.
      try {
        remover();
      } catch (...) {  // NOLINT(bugprone-empty-catch)
        return;
      }
    }
  }

  /// Keep the handler registered and stop tracking it here.
  ///
  /// For a handler meant to live as long as the client — a logger, say — where
  /// storing the token would be bookkeeping with no reader.
  void detach() noexcept { remove_ = nullptr; }

  /// Whether this still holds a registration.
  bool active() const noexcept { return static_cast<bool>(remove_); }

 private:
  std::function<void()> remove_;
};

}  // namespace reactor
