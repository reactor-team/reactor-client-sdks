// The four states a session moves through.
#pragma once

#include <cstdint>
#include <string_view>

namespace reactor {

/// Where a client is.
///
/// The same four the FFI reports as strings, and in the same order they happen.
enum class Status : std::uint8_t {
  /// No session, or the last one ended.
  Disconnected,
  /// The session is being created or adopted.
  Connecting,
  /// The session exists; waiting for the runtime and negotiating transport.
  Waiting,
  /// Transport is up: commands can be sent and tracks flow.
  Ready,
};

/// The wire spelling, as `reactor_status` reports it.
std::string_view to_string(Status status) noexcept;

/// The status a wire string names.
///
/// An unrecognised one reads as `Disconnected`. A client that cannot understand
/// what the library is telling it should behave as though it has no session,
/// rather than assume the most capable state it knows.
Status status_from_string(std::string_view text) noexcept;

}  // namespace reactor
