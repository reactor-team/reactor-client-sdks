// The little logging a library is allowed.
//
// The SDK is silent in normal operation. What it does report is the class of
// thing a caller cannot see for themselves and would otherwise debug blind: a
// frame arriving for a track nobody declared, for instance, which looks exactly
// like no frame arriving at all.
//
// Bounded on purpose. `log_once` keys on a string and says each thing one time,
// so a 30fps mistake produces one line rather than a scrolling wall — a library
// that floods stderr gets its output redirected to /dev/null, and then it has no
// way to say anything at all.
#pragma once

#include <string>

namespace reactor::detail {

/// Write one line to stderr, prefixed `reactor-sdk:`.
void log_warn(const std::string& message);

/// Write one line to stderr the first time this `key` is seen, and never again.
void log_warn_once(const std::string& key, const std::string& message);

}  // namespace reactor::detail
