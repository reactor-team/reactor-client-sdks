import Foundation
import Testing

@testable import Reactor

@Suite("ReactorError")
struct ReactorErrorTests {

    // MARK: - Decoding what the FFI actually sends

    @Test("the documented payload decodes into every field")
    func fullPayload() throws {
        let error = ReactorError.decode(
            payload: """
                {
                  "code": "RATE_LIMITED",
                  "message": "slow down",
                  "recoverable": true,
                  "status": 429,
                  "operation": "send_command",
                  "retry_after_ms": 1500,
                  "timestamp_ms": 1712345678000
                }
                """)

        #expect(error.code == .rateLimited)
        #expect(error.message == "slow down")
        #expect(error.recoverable)
        #expect(error.status == 429)
        #expect(error.operation == "send_command")
        #expect(error.retryAfterMS == 1500)
        #expect(error.timestampMS == 1_712_345_678_000)
    }

    @Test("a code this SDK does not know survives as itself")
    func unknownCode() {
        // The platform's codes for a rejected command are open-ended. An
        // unrecognised one is a failure we cannot classify — never a parse
        // failure, and never flattened into INTERNAL_ERROR, or a caller could
        // not tell the two apart.
        let error = ReactorError.decode(payload: #"{"code":"MODEL_BUSY","message":"try later"}"#)

        #expect(error.code == ReactorError.Code(rawValue: "MODEL_BUSY"))
        #expect(error.message == "try later")
        #expect(error.recoverable == false)
    }

    @Test("a bare string payload becomes the message")
    func legacyStringPayload() {
        // What a libreactor_ffi older than this SDK sends. Guessing wrong here
        // means throwing from inside the error path.
        let error = ReactorError.decode(payload: "connection reset by peer")

        #expect(error.code == .internalError)
        #expect(error.message == "connection reset by peer")
    }

    @Test("valid JSON that is not an object becomes the message")
    func nonObjectJSON() {
        let error = ReactorError.decode(payload: #""just a string""#)

        #expect(error.code == .internalError)
        #expect(error.message == #""just a string""#)
    }

    @Test("an absent payload is still a usable error", arguments: [nil, ""] as [String?])
    func absentPayload(payload: String?) {
        let error = ReactorError.decode(payload: payload)

        #expect(error.code == .internalError)
        #expect(!error.message.isEmpty)
    }

    @Test("an empty code is not a code")
    func emptyCode() {
        let error = ReactorError.decode(payload: #"{"code":"","message":"boom"}"#)

        #expect(error.code == .internalError)
        #expect(error.message == "boom")
    }

    @Test("a payload with no message falls back to the raw text")
    func missingMessage() {
        let payload = #"{"code":"SERVER_ERROR"}"#
        let error = ReactorError.decode(payload: payload)

        #expect(error.code == .serverError)
        #expect(error.message == payload)
    }

    @Test("recoverable comes from the payload, not from the code")
    func recoverabilityIsNotDerived() {
        // The core decides this and sends it. Deriving it here a second time is
        // how two SDKs come to disagree about whether a timeout is worth
        // retrying — so the same code decodes either way, as the payload says.
        let retryable = ReactorError.decode(
            payload: #"{"code":"REQUEST_TIMEOUT","message":"x","recoverable":true}"#)
        let terminal = ReactorError.decode(
            payload: #"{"code":"REQUEST_TIMEOUT","message":"x","recoverable":false}"#)

        #expect(retryable.recoverable)
        #expect(!terminal.recoverable)
    }

    // MARK: - The catch idiom

    @Test("catching by code matches that code and no other")
    func patternMatchingByCode() {
        func failing(with code: ReactorError.Code) throws {
            throw ReactorError(code, "nope")
        }

        do {
            try failing(with: .unauthorized)
            Issue.record("expected a throw")
        } catch ReactorError.unauthorized {
            // The idiom this SDK promises, and the reason for the ~= overload.
        } catch {
            Issue.record("matched the wrong clause: \(error)")
        }

        do {
            try failing(with: .notFound)
            Issue.record("expected a throw")
        } catch ReactorError.unauthorized {
            Issue.record("NOT_FOUND must not match UNAUTHORIZED")
        } catch let error as ReactorError {
            #expect(error.code == .notFound)
        } catch {
            Issue.record("matched the wrong clause: \(error)")
        }
    }

    @Test("an unrelated error never matches a Reactor code")
    func unrelatedErrorDoesNotMatch() {
        struct Other: Error {}

        // A `catch ReactorError.someCode` clause must let everything else past,
        // or the SDK would swallow errors that are not its own.
        #expect(!(ReactorError.unauthorized ~= Other()))
        #expect(!(ReactorError.unauthorized ~= CocoaError(.fileNoSuchFile)))
    }

    // MARK: - Surface

    @Test("an SDK-raised failure carries the code and no payload fields")
    func locallyRaised() {
        let error = ReactorError(.invalidState, "publish the track first", operation: "push_frame")

        #expect(error.code == .invalidState)
        #expect(error.operation == "push_frame")
        #expect(!error.recoverable)
        #expect(error.status == nil)
        #expect(error.timestampMS == nil)
    }

    @Test("localizedDescription is the message")
    func localizedDescriptionIsMessage() {
        let error = ReactorError(.notFound, "no track named 'main_video'")

        #expect(error.localizedDescription == "no track named 'main_video'")
    }

    @Test("description names the code, and the operation and status when known")
    func descriptionIncludesContext() {
        #expect(
            ReactorError(.badRequest, "bad").description == "BAD_REQUEST: bad")
        #expect(
            ReactorError(code: .unauthorized, message: "no", status: 401, operation: "connect")
                .description == "UNAUTHORIZED: no (operation: connect, status: 401)")
        #expect(
            ReactorError(code: .serverError, message: "no", status: 500).description
                == "SERVER_ERROR: no (status: 500)")
    }
}
