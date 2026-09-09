#include <memory>

#include "detail/ffi.hpp"
#include "reactor/reactor.hpp"

namespace reactor {
namespace {

// This call has no client to own it. The FFI promises exactly one completion,
// which owns the promise even if the caller has already discarded the future.
extern "C" void jwt_completion(int ok, const char* result_json, const char* error_json,
                               void* userdata) noexcept {
  const std::unique_ptr<std::promise<std::string>> promise{
      static_cast<std::promise<std::string>*>(userdata)};
  try {
    if (ok != 1) {
      error_from_payload(error_json, "fetch_jwt")->rethrow();
    }
    const Json result = Json::parse(result_json == nullptr ? "" : result_json, nullptr, false);
    if (!result.is_object() || !result.contains("jwt") || !result["jwt"].is_string() ||
        result["jwt"].get_ref<const std::string&>().empty()) {
      throw DecodeError{ErrorDetails{std::string{codes::DECODE_FAILED},
                                     "the coordinator returned no valid JWT",
                                     false,
                                     {},
                                     "fetch_jwt",
                                     {},
                                     {}}};
    }
    promise->set_value(result["jwt"].get<std::string>());
  } catch (...) {
    try {
      promise->set_exception(std::current_exception());
    } catch (...) {  // NOLINT(bugprone-empty-catch)
    }
  }
}

}  // namespace

std::future<std::string> fetch_jwt(const ApiKey& key, const FetchJwtOptions& options) {
  auto promise = std::make_unique<std::promise<std::string>>();
  auto future = promise->get_future();
  try {
    Json request = Json::object();
    if (options.models) {
      request["models"] = *options.models;
    }
    if (options.max_sessions) {
      request["max_sessions"] = *options.max_sessions;
    }
    if (options.max_session_duration_seconds) {
      request["max_session_duration_seconds"] = *options.max_session_duration_seconds;
    }
    if (options.expires_after) {
      request["expires_after"] = *options.expires_after;
    }
    const std::string json = request.dump();
    const auto exchange = detail::ffi().fetch_jwt;
    // All arguments are copied by the FFI before returning. Release before the
    // call because a completion may run synchronously on an invalid request.
    exchange(options.api_url.c_str(), key.value.c_str(), json.c_str(), options.local ? 1 : 0,
             &jwt_completion, promise.release());
  } catch (...) {
    if (promise) {
      promise->set_exception(std::current_exception());
    }
  }
  return future;
}

}  // namespace reactor
