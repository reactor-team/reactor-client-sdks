// The caller's extra HTTP headers as the JSON the FFI takes.
//
// Shared by the client and `fetch_jwt`, so both encode the same way.
#pragma once

#include <map>
#include <string>

#include "reactor/json.hpp"

namespace reactor::detail {

/// The headers as the JSON object the FFI takes, or "" for none (passed as NULL).
/// Invalid UTF-8 is replaced rather than thrown on.
inline std::string extra_headers_to_json(const std::map<std::string, std::string>& headers) {
  if (headers.empty()) {
    return {};
  }
  return Json(headers).dump(-1, ' ', false, Json::error_handler_t::replace);
}

}  // namespace reactor::detail
