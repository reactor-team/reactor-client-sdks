// Strings that cross the FFI boundary, and who frees them.
//
// The header states this per function, and it is not guessable from a name.
// Three cases:
//
//   * **Yours, must free.** `reactor_session_id`, `reactor_tracks`,
//     `reactor_paused_tracks`, and the error object a *failed*
//     `reactor_unpublish_track` returns. These are `OwnedString`.
//   * **Static, never free.** `reactor_status` returns a pointer to a literal.
//     That is `StaticString`, a type with no way to free anything.
//   * **Borrowed, never free.** Every string handed *to* a callback. The FFI
//     frees them once the callback returns, so they are copied into a
//     `std::string` at the trampoline and never wrapped in either type.
//
// The asymmetry is easy to get backwards in all three directions, and each way
// is its own bug: freeing the static one corrupts the heap, freeing a borrowed
// one is a double free, and not freeing an owned one leaks on every property
// read. Hence two types that cannot be confused for one another.
#pragma once

#include <string>
#include <string_view>
#include <utility>

#include "detail/ffi.hpp"

namespace reactor::detail {

/// A heap string the FFI handed over, freed exactly once.
class OwnedString {
 public:
  OwnedString() noexcept = default;
  explicit OwnedString(char* raw) noexcept : raw_(raw) {}

  ~OwnedString() { reset(); }

  OwnedString(OwnedString&& other) noexcept : raw_(std::exchange(other.raw_, nullptr)) {}

  OwnedString& operator=(OwnedString&& other) noexcept {
    if (this != &other) {
      reset();
      raw_ = std::exchange(other.raw_, nullptr);
    }
    return *this;
  }

  // Not copyable: two owners means two frees, and the second is the bug.
  OwnedString(const OwnedString&) = delete;
  OwnedString& operator=(const OwnedString&) = delete;

  /// Whether the FFI returned anything. Null is a documented answer for several
  /// of these — no session yet, or a null handle — and is not a failure.
  bool has_value() const noexcept { return raw_ != nullptr; }
  explicit operator bool() const noexcept { return has_value(); }

  /// A view of the string, valid while this object is. Empty when null.
  std::string_view view() const noexcept {
    return raw_ == nullptr ? std::string_view{} : std::string_view{raw_};
  }

  /// A copy that outlives this object.
  std::string to_string() const { return std::string{view()}; }

  void reset() noexcept {
    if (raw_ != nullptr) {
      // Through the table rather than the symbol, so a test's fake sees the
      // free and can prove it happened exactly once.
      ffi().free_string(raw_);
      raw_ = nullptr;
    }
  }

 private:
  char* raw_ = nullptr;
};

/// A string the library owns forever — `reactor_status`'s literals.
///
/// A distinct type with no destructor, so there is no path from "a string from
/// the FFI" to `reactor_free_string` on one of these. Freeing a literal corrupts
/// the heap, and the compiler is a better place to prevent that than a comment.
class StaticString {
 public:
  StaticString() noexcept = default;
  explicit StaticString(const char* raw) noexcept : raw_(raw) {}

  std::string_view view() const noexcept {
    return raw_ == nullptr ? std::string_view{} : std::string_view{raw_};
  }
  std::string to_string() const { return std::string{view()}; }

 private:
  const char* raw_ = nullptr;
};

}  // namespace reactor::detail
