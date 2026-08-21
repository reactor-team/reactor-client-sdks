// The JSON type the SDK speaks.
//
// JSON is the FFI's own currency: capabilities, errors, command arguments and
// replies all cross the boundary as text. Rather than invent a value type, the
// SDK uses nlohmann/json — the de facto standard for reading it in C++ — and
// names it once, here, so there is a single seam if that ever has to change.
#pragma once

#include <nlohmann/json.hpp>

namespace reactor {

/// The JSON value type in the public API: command arguments, replies, messages.
using Json = nlohmann::json;

}  // namespace reactor
