// The error contract: one type for a thrown failure and for an `on_error`
// payload, the right subclass per code, and — the part that matters most — an
// unknown code surviving as itself rather than being flattened or rejected.

#include "reactor/errors.hpp"

#include <string>

#include <catch2/catch_test_macros.hpp>

namespace {

const std::string PAYLOAD_TEMPLATE = R"({"code":"%CODE%","message":"it went wrong"})";

std::string payload_for(const char* code) {
  std::string text = PAYLOAD_TEMPLATE;
  text.replace(text.find("%CODE%"), 6, code);
  return text;
}

/// Whether parsing `json` produces exactly `T`, not a base or a sibling.
template <typename T>
bool parses_as(const std::string& json) {
  const auto error = reactor::error_from_payload(json.c_str());
  return dynamic_cast<const T*>(error.get()) != nullptr;
}

}  // namespace

// Written out one by one rather than generated from REACTOR_ERROR_CLASSES: a
// test driven by the same macro as the code would assert the pairing against
// itself, and a wrong code in the header would pass.
TEST_CASE("each documented code parses into its own class") {
  CHECK(parses_as<reactor::NetworkError>(payload_for("NETWORK_ERROR")));
  CHECK(parses_as<reactor::UnauthorizedError>(payload_for("UNAUTHORIZED")));
  CHECK(parses_as<reactor::NotFoundError>(payload_for("NOT_FOUND")));
  CHECK(parses_as<reactor::DisconnectedError>(payload_for("DISCONNECTED")));
  CHECK(parses_as<reactor::ConflictError>(payload_for("CONFLICT")));
  CHECK(parses_as<reactor::RateLimitedError>(payload_for("RATE_LIMITED")));
  CHECK(parses_as<reactor::BadRequestError>(payload_for("BAD_REQUEST")));
  CHECK(parses_as<reactor::ServerError>(payload_for("SERVER_ERROR")));
  CHECK(parses_as<reactor::VersionMismatchError>(payload_for("VERSION_MISMATCH")));
  CHECK(parses_as<reactor::DecodeError>(payload_for("DECODE_FAILED")));
  CHECK(parses_as<reactor::InvalidStateError>(payload_for("INVALID_STATE")));
  CHECK(parses_as<reactor::SessionTerminalError>(payload_for("SESSION_TERMINAL")));
  CHECK(parses_as<reactor::MessageTooLargeError>(payload_for("MESSAGE_TOO_LARGE")));
  CHECK(parses_as<reactor::TransportError>(payload_for("TRANSPORT_ERROR")));
  CHECK(parses_as<reactor::RequestTimeoutError>(payload_for("REQUEST_TIMEOUT")));
  CHECK(parses_as<reactor::AbortedError>(payload_for("ABORTED")));
}

TEST_CASE("catching the base class catches every one of them") {
  const auto error = reactor::error_from_payload(payload_for("UNAUTHORIZED").c_str());
  CHECK(dynamic_cast<const reactor::ReactorError*>(error.get()) != nullptr);
}

// A control request, command or recording the platform refuses reports the
// platform's own code, which this SDK cannot enumerate. Relabelling it as
// INTERNAL_ERROR — or refusing to parse — would hide the only thing specific
// enough to act on.
TEST_CASE("a code this SDK does not know survives as itself") {
  const auto error =
      reactor::error_from_payload(R"({"code":"MODEL_REFUSED_PROMPT","message":"no"})");

  CHECK(error->code() == "MODEL_REFUSED_PROMPT");
  CHECK(error->message() == "no");
  // The base class, because there is nothing more specific to be.
  CHECK(dynamic_cast<const reactor::NetworkError*>(error.get()) == nullptr);
}

TEST_CASE("every field the payload carries reaches the error") {
  const auto error = reactor::error_from_payload(R"({
    "code": "RATE_LIMITED",
    "message": "slow down",
    "recoverable": true,
    "status": 429,
    "operation": "send_command",
    "retry_after_ms": 1500.0,
    "timestamp_ms": 1700000000000.0
  })");

  CHECK(error->code() == "RATE_LIMITED");
  CHECK(error->recoverable());
  CHECK(error->status() == 429);
  CHECK(error->operation() == "send_command");
  CHECK(error->retry_after_ms() == 1500.0);
  CHECK(error->timestamp_ms() == 1700000000000.0);
}

// Recoverability is the core's answer, not one computed here: deriving it a
// second time in C++ is how two SDKs come to disagree about whether a timeout is
// worth retrying.
TEST_CASE("recoverability comes from the payload, not from a second opinion") {
  CHECK(reactor::error_from_payload(R"({"code":"REQUEST_TIMEOUT","message":"","recoverable":true})")
            ->recoverable());
  CHECK_FALSE(
      reactor::error_from_payload(R"({"code":"REQUEST_TIMEOUT","message":"","recoverable":false})")
          ->recoverable());
  // Absent means false rather than a guess from the code.
  CHECK_FALSE(
      reactor::error_from_payload(R"({"code":"REQUEST_TIMEOUT","message":""})")->recoverable());
}

// Three shapes, all of which happen. The last one is the one that bites: an SDK
// is not always paired with the exact libreactor_ffi it shipped with, and a
// library built before the structured payload sends a bare sentence.
TEST_CASE("a payload that is not the documented object still produces an error") {
  SECTION("absent") {
    const auto error = reactor::error_from_payload(nullptr);
    CHECK(error->code() == "INTERNAL_ERROR");
    CHECK(error->message() == "unknown error");
  }

  SECTION("empty") {
    const auto error = reactor::error_from_payload("");
    CHECK(error->message() == "unknown error");
  }

  SECTION("a bare string from an older library") {
    const auto error = reactor::error_from_payload("connection refused");
    CHECK(error->code() == "INTERNAL_ERROR");
    CHECK(error->message() == "connection refused");
  }

  SECTION("valid JSON that is not an object") {
    const auto error = reactor::error_from_payload("[1, 2, 3]");
    CHECK(error->message() == "[1, 2, 3]");
  }

  SECTION("an object with no code") {
    const auto error = reactor::error_from_payload(R"({"message":"something"})");
    CHECK(error->code() == "INTERNAL_ERROR");
    CHECK(error->message() == "something");
  }

  SECTION("a null where a number was expected") {
    const auto error =
        reactor::error_from_payload(R"({"code":"SERVER_ERROR","message":"x","status":null})");
    CHECK_FALSE(error->status().has_value());
  }

  SECTION("a string where a number was expected") {
    const auto error =
        reactor::error_from_payload(R"({"code":"SERVER_ERROR","message":"x","status":"429"})");
    CHECK_FALSE(error->status().has_value());
  }
}

// The payload names the call that actually failed; the caller only knows the one
// it made. When both are present the payload wins.
TEST_CASE("the operation comes from the payload when it has one") {
  CHECK(reactor::error_from_payload(R"({"code":"NOT_FOUND","message":"x"})", "connect")
            ->operation() == "connect");
  CHECK(reactor::error_from_payload(R"({"code":"NOT_FOUND","message":"x","operation":"upload"})",
                                    "connect")
            ->operation() == "upload");
}

TEST_CASE("what() names the operation, the code and the message") {
  const auto error = reactor::error_from_payload(
      R"({"code":"UNAUTHORIZED","message":"token expired","operation":"connect"})");
  CHECK(std::string{error->what()} == "connect: [UNAUTHORIZED] token expired");

  const auto bare = reactor::error_from_payload(R"({"code":"ABORTED","message":"gone"})");
  CHECK(std::string{bare->what()} == "[ABORTED] gone");
}

// The reason `rethrow` exists. An error travels as a value — through a future,
// or to an event handler — and `throw *base_pointer` would slice it down to
// ReactorError, so a caller catching the specific type would never run.
TEST_CASE("an error thrown through a std::exception_ptr keeps its own type") {
  const auto pointer = reactor::as_exception_ptr(reactor::ErrorDetails{
      std::string{reactor::codes::UNAUTHORIZED}, "token expired", false, 401, "connect", {}, {}});

  bool caught_specific = false;
  try {
    std::rethrow_exception(pointer);
  } catch (const reactor::UnauthorizedError& error) {
    caught_specific = true;
    CHECK(error.status() == 401);
    CHECK(error.operation() == "connect");
  } catch (const reactor::ReactorError&) {
    FAIL(
        "the error arrived as the base class: rethrow() sliced it, and a caller "
        "catching UnauthorizedError would never run");
  }
  CHECK(caught_specific);
}

TEST_CASE("an error the SDK raises itself carries its code and nothing invented") {
  const reactor::InvalidStateError error{"publish() the track first"};

  CHECK(error.code() == "INVALID_STATE");
  CHECK(error.message() == "publish() the track first");
  // Nothing local is recoverable, and nothing local pretends to know a status.
  CHECK_FALSE(error.recoverable());
  CHECK_FALSE(error.status().has_value());
  CHECK_FALSE(error.operation().has_value());
}

TEST_CASE("an empty code is replaced rather than carried") {
  // A code that is the empty string matches nothing a caller can write down, so
  // it is worse than the generic one.
  const auto error = reactor::error_from_payload(R"({"code":"","message":"unhelpful"})");
  CHECK(error->code() == "INTERNAL_ERROR");
}

TEST_CASE("a subclass carries its own code, whatever the details said") {
  // The invariant a caller relies on: catching UnauthorizedError is only worth
  // doing if code() agrees with the catch. Nothing in this SDK builds one from a
  // payload that disagrees — make_error picks the class *from* the code — so this
  // is about what the type makes possible, and inheriting the base constructors
  // used to make this contradiction expressible.
  const reactor::UnauthorizedError error{
      reactor::ErrorDetails{std::string{"NOT_FOUND"}, "token expired", false, 401,
                            std::string{"connect"}, std::nullopt, std::nullopt}};

  CHECK(error.code() == "UNAUTHORIZED");
  CHECK(error.code() == reactor::UnauthorizedError::CODE);
  // Everything else the payload carried survives untouched.
  CHECK(error.message() == "token expired");
  CHECK(error.status() == 401);
  CHECK(error.operation() == "connect");
}
