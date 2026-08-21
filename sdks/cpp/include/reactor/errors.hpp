// Failures, and the payload an `on_error` event delivers — the same type either
// way.
//
// Every failure used to arrive as one error carrying a sentence, which left
// callers two options: catch everything and treat a 401 like a dropped socket,
// or match on message text and break the next time the wording changed. The
// failure has always been more specific than that — the core distinguishes a
// network error from a rejected request from a timeout, and the platform sends
// its own codes for a command it refuses.
//
//     try {
//       reactor.connect().get();
//     } catch (const reactor::UnauthorizedError&) {
//       token = refresh();                 // a specific, actionable failure
//     } catch (const reactor::ReactorError& error) {
//       if (error.recoverable()) {         // a class of failures, by property
//         reactor.reconnect().get();
//       }
//     }
//
// `ReactorError` is the base of all of them, so catching it still catches
// everything.
//
// There is **one class**, not two: the `on_error` event and a failed call both
// hand you a `ReactorError`. They used to disagree in the Python SDK — a 401
// during connect raised UNAUTHORIZED and not-recoverable to the caller while
// the event called it CONNECTION_FAILED and recoverable, so anything listening
// to the event reconnected in a loop against a token that would never work. A
// second definition of the same fields is how that happened, so there is one.
//
// There is no `component` field. Which tier of the platform failed is not
// something a caller can act on, and splitting the codes by it is what produced
// two names for one failure.
//
// **Codes are open-ended.** A control request, command or recording the platform
// rejects reports the platform's own code, which this SDK cannot enumerate.
// Those arrive as `ReactorError` itself with `code()` set to whatever came — so
// match on `code()` for anything not listed here, and never assume an
// unrecognised code means the payload was malformed.
#pragma once

#include <memory>
#include <optional>
#include <stdexcept>
#include <string>
#include <string_view>

namespace reactor {

/// The codes this SDK has a class for.
///
/// The same list as `crates/reactor-core/src/error.rs`, which is where it is
/// decided; these are the spelling, not the definition.
namespace codes {

inline constexpr std::string_view INVALID_STATE = "INVALID_STATE";
inline constexpr std::string_view DISCONNECTED = "DISCONNECTED";
inline constexpr std::string_view NETWORK_ERROR = "NETWORK_ERROR";
inline constexpr std::string_view REQUEST_TIMEOUT = "REQUEST_TIMEOUT";
inline constexpr std::string_view TRANSPORT_ERROR = "TRANSPORT_ERROR";
inline constexpr std::string_view UNAUTHORIZED = "UNAUTHORIZED";
inline constexpr std::string_view NOT_FOUND = "NOT_FOUND";
inline constexpr std::string_view CONFLICT = "CONFLICT";
inline constexpr std::string_view RATE_LIMITED = "RATE_LIMITED";
inline constexpr std::string_view BAD_REQUEST = "BAD_REQUEST";
inline constexpr std::string_view SERVER_ERROR = "SERVER_ERROR";
inline constexpr std::string_view VERSION_MISMATCH = "VERSION_MISMATCH";
inline constexpr std::string_view DECODE_FAILED = "DECODE_FAILED";
inline constexpr std::string_view SESSION_TERMINAL = "SESSION_TERMINAL";
inline constexpr std::string_view MESSAGE_TOO_LARGE = "MESSAGE_TOO_LARGE";
inline constexpr std::string_view ABORTED = "ABORTED";
inline constexpr std::string_view INTERNAL_ERROR = "INTERNAL_ERROR";

}  // namespace codes

/// Everything a failure carries.
///
/// `recoverable` is **not** computed here. The core decides it from the code and
/// sends it, so two SDKs cannot disagree about whether a timeout is worth
/// retrying — deriving it a second time in C++ would be exactly the second
/// definition this type exists to avoid. Errors the SDK raises on its own —
/// refusing a push into an unpublished track, say — leave it false, which is
/// what the core says about every code the SDK raises locally.
struct ErrorDetails {
  /// One of [`codes`], or a code the platform sent. Never empty.
  std::string code{codes::INTERNAL_ERROR};
  /// Human-readable, and the only field guaranteed to be worth printing.
  std::string message;
  /// Whether the same call could succeed later. True is about the moment — a
  /// timeout, a 5xx, a transport that dropped. False is about the request.
  bool recoverable = false;
  /// The HTTP status, when the failure came from one.
  std::optional<int> status;
  /// Which call failed, e.g. "connect", "send_command".
  std::optional<std::string> operation;
  /// A backoff hint, when the platform sent one.
  std::optional<double> retry_after_ms;
  /// When this happened. Only ever set on the `on_error` event — a thrown
  /// exception is already happening now.
  std::optional<double> timestamp_ms;
};

/// A Reactor operation failed, or an `on_error` event arrived.
class ReactorError : public std::runtime_error {
 public:
  explicit ReactorError(ErrorDetails details);

  /// A failure this SDK is raising itself, with no payload behind it.
  explicit ReactorError(std::string message, std::string_view code = codes::INTERNAL_ERROR);

  const ErrorDetails& details() const noexcept { return details_; }

  const std::string& code() const noexcept { return details_.code; }
  const std::string& message() const noexcept { return details_.message; }
  bool recoverable() const noexcept { return details_.recoverable; }
  const std::optional<int>& status() const noexcept { return details_.status; }
  const std::optional<std::string>& operation() const noexcept { return details_.operation; }
  const std::optional<double>& retry_after_ms() const noexcept { return details_.retry_after_ms; }
  const std::optional<double>& timestamp_ms() const noexcept { return details_.timestamp_ms; }

  /// Throw this error with its own dynamic type.
  ///
  /// `throw *base_reference` would slice a `NotFoundError` down to a
  /// `ReactorError` and a caller's `catch (const NotFoundError&)` would never
  /// run. Every subclass overrides this, which is what lets an error travel as a
  /// value — through a `std::future`, or to an event handler — and still arrive
  /// as the type it is.
  [[noreturn]] virtual void rethrow() const;

 private:
  ErrorDetails details_;
};

/// Declare a subclass.
///
/// Sixteen of these differ only in name and code, so they are generated from one
/// list: a hand-written set is sixteen chances to paste the wrong code in, or to
/// forget the `rethrow` override that keeps the type from being sliced away.
#define REACTOR_ERROR_CLASSES(X)                                                      \
  X(NetworkError, NETWORK_ERROR)             /* the request never got a reply */      \
  X(UnauthorizedError, UNAUTHORIZED)         /* 401/403: token missing or unscoped */ \
  X(NotFoundError, NOT_FOUND)                /* no such model, session or track */    \
  X(DisconnectedError, DISCONNECTED)         /* the connection went away */           \
  X(ConflictError, CONFLICT)                 /* 409, usually an orphaned session */   \
  X(RateLimitedError, RATE_LIMITED)          /* 429 */                                \
  X(BadRequestError, BAD_REQUEST)            /* the request itself was wrong */       \
  X(ServerError, SERVER_ERROR)               /* 5xx; may work later */                \
  X(VersionMismatchError, VERSION_MISMATCH)  /* client and platform disagree */       \
  X(DecodeError, DECODE_FAILED)              /* a reply arrived, unintelligible */    \
  X(InvalidStateError, INVALID_STATE)        /* not possible in this state */         \
  X(SessionTerminalError, SESSION_TERMINAL)  /* start a new session */                \
  X(MessageTooLargeError, MESSAGE_TOO_LARGE) /* over what the channel accepts */      \
  X(TransportError, TRANSPORT_ERROR)         /* the media transport failed */         \
  X(RequestTimeoutError, REQUEST_TIMEOUT)    /* sent, nothing came back in time */    \
  X(AbortedError, ABORTED)                   /* abandoned before it finished */

#define REACTOR_DECLARE_ERROR(Name, Code)                                          \
  class Name final : public ReactorError {                                         \
   public:                                                                         \
    static constexpr std::string_view CODE = codes::Code;                          \
    using ReactorError::ReactorError;                                              \
    explicit Name(std::string message) : ReactorError(std::move(message), CODE) {} \
    [[noreturn]] void rethrow() const override { throw *this; }                    \
  };

REACTOR_ERROR_CLASSES(REACTOR_DECLARE_ERROR)

#undef REACTOR_DECLARE_ERROR

/// The error `details` describes, as its own concrete type.
///
/// An unrecognised code — the platform's own, for a request it refused — becomes
/// a `ReactorError` carrying that code unchanged. It is never relabelled as
/// `INTERNAL_ERROR`, and never treated as a parse failure.
std::unique_ptr<ReactorError> make_error(ErrorDetails details);

/// The same error as something a `std::future` can carry.
std::exception_ptr as_exception_ptr(ErrorDetails details);

/// Throw what an FFI error payload describes, as its own concrete type.
///
/// For the synchronous calls, which report failure by returning the payload
/// rather than through a completion.
[[noreturn]] void throw_error_payload(std::string_view json, std::string_view operation = {});

/// Parse the JSON object the FFI reports for a failure.
///
/// Three shapes have to work, because all three happen:
///
///  * the documented object — `{code, message, recoverable, …}`;
///  * nothing at all, when a completion failed without saying why;
///  * a bare string, which is what a library built before the structured payload
///    sends. An SDK is not always paired with the exact `libreactor_ffi` it
///    shipped with, and the failure mode of guessing wrong here is an exception
///    thrown from inside the error path.
///
/// `fallback_operation` names the call, for a payload that did not.
std::unique_ptr<ReactorError> error_from_payload(const char* json_or_null,
                                                 std::string_view fallback_operation = {});

}  // namespace reactor
