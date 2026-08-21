#include "reactor/errors.hpp"

#include <string>
#include <utility>

#include "reactor/json.hpp"

namespace reactor {
namespace {

/// `operation: [CODE] message`, matching what the Python SDK's `__str__` prints.
///
/// Built once, at construction, because `std::runtime_error` owns the string it
/// was given and `what()` must not allocate.
std::string format_what(const ErrorDetails& details) {
  std::string text;
  if (details.operation && !details.operation->empty()) {
    text += *details.operation;
    text += ": ";
  }
  text += "[";
  text += details.code.empty() ? std::string{codes::INTERNAL_ERROR} : details.code;
  text += "] ";
  text += details.message;
  return text;
}

/// Read an optional field, ignoring a null and anything of the wrong type.
///
/// A payload with `"status": null` is ordinary — the core omits what it does not
/// know — and so is one from a platform that sent a string where a number was
/// expected. Neither is worth failing the error path over.
template <typename T>
std::optional<T> optional_field(const Json& object, const char* key) {
  const auto found = object.find(key);
  if (found == object.end() || found->is_null()) {
    return std::nullopt;
  }
  T value{};
  try {
    value = found->get<T>();
  } catch (const Json::exception&) {
    return std::nullopt;
  }
  return value;
}

}  // namespace

ReactorError::ReactorError(ErrorDetails details)
    : std::runtime_error(format_what(details)), details_(std::move(details)) {
  if (details_.code.empty()) {
    // A code that is the empty string is worse than a generic one: it matches
    // nothing a caller can write down.
    details_.code = std::string{codes::INTERNAL_ERROR};
  }
}

ReactorError::ReactorError(std::string message, std::string_view code)
    : ReactorError(ErrorDetails{std::string{code}, std::move(message), false, {}, {}, {}, {}}) {}

void ReactorError::rethrow() const { throw *this; }

std::unique_ptr<ReactorError> make_error(ErrorDetails details) {
#define REACTOR_ERROR_BY_CODE(Name, Code)              \
  if (details.code == codes::Code) {                   \
    return std::make_unique<Name>(std::move(details)); \
  }
  REACTOR_ERROR_CLASSES(REACTOR_ERROR_BY_CODE)
#undef REACTOR_ERROR_BY_CODE

  // Not a code this SDK knows, which is not an error in itself: a control
  // request, command or recording the platform refuses reports the platform's
  // own code. It travels through untouched.
  return std::make_unique<ReactorError>(std::move(details));
}

std::exception_ptr as_exception_ptr(ErrorDetails details) {
  const auto error = make_error(std::move(details));
  try {
    // Through the error's own `rethrow`, so the pointer carries the concrete
    // type. `std::make_exception_ptr(*error)` would copy the base and a caller's
    // `catch (const UnauthorizedError&)` would never match.
    error->rethrow();
  } catch (...) {
    return std::current_exception();
  }
  // Unreachable: rethrow() is [[noreturn]].
  return std::make_exception_ptr(ReactorError{"error could not be rethrown"});
}

std::unique_ptr<ReactorError> error_from_payload(const char* json_or_null,
                                                 std::string_view fallback_operation) {
  ErrorDetails details;
  if (!fallback_operation.empty()) {
    details.operation = std::string{fallback_operation};
  }

  if (json_or_null == nullptr || *json_or_null == '\0') {
    details.message = "unknown error";
    return make_error(std::move(details));
  }

  const std::string text{json_or_null};
  const Json payload = Json::parse(text, nullptr, /*allow_exceptions=*/false);
  if (!payload.is_object()) {
    // A bare string, or something unparseable. Either way the text is the only
    // information there is, so it becomes the message rather than being dropped
    // in favour of a complaint about its shape.
    details.message = text;
    return make_error(std::move(details));
  }

  if (const auto code = optional_field<std::string>(payload, "code"); code && !code->empty()) {
    details.code = *code;
  }
  const auto message = optional_field<std::string>(payload, "message");
  details.message = (message && !message->empty()) ? *message : text;
  details.recoverable = optional_field<bool>(payload, "recoverable").value_or(false);
  details.status = optional_field<int>(payload, "status");
  details.retry_after_ms = optional_field<double>(payload, "retry_after_ms");
  details.timestamp_ms = optional_field<double>(payload, "timestamp_ms");
  if (auto operation = optional_field<std::string>(payload, "operation");
      operation && !operation->empty()) {
    // The payload knows better than the caller's guess: it names the call that
    // actually failed, which is not always the one that was made.
    details.operation = std::move(operation);
  }

  return make_error(std::move(details));
}

}  // namespace reactor
