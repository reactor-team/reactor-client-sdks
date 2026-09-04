import Foundation

/// A Reactor operation failed, or an `onError` event arrived.
///
/// One type, not two: a failed call and the error event both hand you a
/// `ReactorError`. They used to disagree in the Python SDK — a 401 during
/// connect raised `UNAUTHORIZED` and not-recoverable to the caller while the
/// event called the same failure recoverable, so anything listening to the event
/// reconnected in a loop against a token that would never work. A second
/// definition of the same fields is how that happened, so there is one.
///
/// ```swift
/// do {
///     try await reactor.connect()
/// } catch ReactorError.unauthorized {
///     token = try await refresh()          // a specific, actionable failure
/// } catch let error as ReactorError where error.recoverable {
///     try await reactor.reconnect()        // a class of failures, by property
/// }
/// ```
///
/// ## Why one struct rather than sixteen types
///
/// The Python SDK models this as sixteen exception classes over a base, and
/// `except UnauthorizedError` is the idiom there. Swift has no base class to
/// catch, and sixteen error types would mean sixteen `catch` clauses to handle
/// them generically. More decisively: **the platform's code list is open-ended**
/// — a command or recording it rejects reports the platform's own code, which
/// this SDK cannot enumerate — so a closed `enum` would make every new platform
/// code either a breaking change or an unrepresentable value.
///
/// One struct with an open ``Code`` avoids both, and the `~=` overload below
/// keeps `catch ReactorError.unauthorized` reading the way the Python idiom
/// does. This is the single deliberate divergence from the Python surface.
public struct ReactorError: Error, Sendable, Hashable {

    /// The failure's stable, matchable code.
    ///
    /// Never empty. One of the constants below, or a code the platform sent for
    /// a rejected control request, command or recording. **Treat an
    /// unrecognised code as a failure you cannot classify, never as a parse
    /// failure** — the list is the core's to grow.
    public let code: Code

    /// Human-readable, and the only field always worth printing.
    public let message: String

    /// Whether the same call could succeed later.
    ///
    /// Decided by the core and carried in the payload, not computed here.
    /// Deriving it a second time in Swift is exactly how two SDKs come to
    /// disagree about whether a timeout is worth retrying. Errors this SDK
    /// raises on its own — refusing a push into an unpublished track, say —
    /// leave it `false`.
    public let recoverable: Bool

    /// The HTTP status, when the failure came from one.
    public let status: Int?

    /// Which call failed, e.g. `"connect"`, `"send_command"`.
    public let operation: String?

    /// A backoff hint in milliseconds, when the platform sent one.
    public let retryAfterMS: Double?

    /// When this happened, in Unix milliseconds.
    ///
    /// Only ever set on an `onError` event: a thrown error is already happening
    /// now.
    public let timestampMS: Double?

    /// A failure with every field, as decoded from an FFI payload.
    public init(
        code: Code,
        message: String,
        recoverable: Bool = false,
        status: Int? = nil,
        operation: String? = nil,
        retryAfterMS: Double? = nil,
        timestampMS: Double? = nil
    ) {
        self.code = code
        self.message = message
        self.recoverable = recoverable
        self.status = status
        self.operation = operation
        self.retryAfterMS = retryAfterMS
        self.timestampMS = timestampMS
    }

    /// A failure this SDK is raising itself, with no payload behind it.
    ///
    /// Every refusal in the SDK — a track name the session never declared, a
    /// push into a recvonly track — goes through here.
    public init(_ code: Code, _ message: String, operation: String? = nil) {
        self.init(code: code, message: message, operation: operation)
    }
}

extension ReactorError {

    /// A failure code.
    ///
    /// A struct rather than an `enum` because the set is open: the codes below
    /// are the ones the core defines, and the platform sends its own for a
    /// request it rejects. An unknown code round-trips through ``rawValue``
    /// unchanged rather than collapsing into a catch-all case.
    public struct Code: RawRepresentable, Sendable, Hashable, CustomStringConvertible {

        /// The code as the core spells it, e.g. `"UNAUTHORIZED"`.
        public let rawValue: String

        /// Wrap a code string, including one this SDK does not know.
        public init(rawValue: String) {
            self.rawValue = rawValue
        }

        /// The code as the core spells it.
        public var description: String { rawValue }

        // The list below is the same one as `crates/reactor-core/src/error.rs`,
        // which is where it is decided; these are the spelling, not the
        // definition.

        /// The operation is not legal in the client's current state.
        public static let invalidState = Code(rawValue: "INVALID_STATE")

        /// There is no live connection to carry the operation.
        public static let disconnected = Code(rawValue: "DISCONNECTED")

        /// The request never reached the platform.
        public static let networkError = Code(rawValue: "NETWORK_ERROR")

        /// The platform did not answer in time.
        public static let requestTimeout = Code(rawValue: "REQUEST_TIMEOUT")

        /// The WebRTC transport failed.
        public static let transportError = Code(rawValue: "TRANSPORT_ERROR")

        /// The credentials were missing, wrong, or not scoped to this model.
        public static let unauthorized = Code(rawValue: "UNAUTHORIZED")

        /// The model, session or clip does not exist.
        public static let notFound = Code(rawValue: "NOT_FOUND")

        /// The request collided with the resource's current state.
        public static let conflict = Code(rawValue: "CONFLICT")

        /// The caller is over a rate limit; see ``ReactorError/retryAfterMS``.
        public static let rateLimited = Code(rawValue: "RATE_LIMITED")

        /// The platform rejected the request as malformed.
        public static let badRequest = Code(rawValue: "BAD_REQUEST")

        /// The platform failed while handling the request.
        public static let serverError = Code(rawValue: "SERVER_ERROR")

        /// The library and the platform (or this SDK and the library) disagree
        /// about a version.
        public static let versionMismatch = Code(rawValue: "VERSION_MISMATCH")

        /// A payload could not be decoded into what it claimed to be.
        public static let decodeFailed = Code(rawValue: "DECODE_FAILED")

        /// The session ended and cannot be continued.
        public static let sessionTerminal = Code(rawValue: "SESSION_TERMINAL")

        /// The message exceeded what the data channel accepts.
        public static let messageTooLarge = Code(rawValue: "MESSAGE_TOO_LARGE")

        /// The operation was cancelled before it completed.
        public static let aborted = Code(rawValue: "ABORTED")

        /// A clip could not be produced because the session's recorder is
        /// disabled or has crashed.
        ///
        /// The one code here the platform does not send: `ClipFailed` carries
        /// free text, and the core matches known reason strings to produce this.
        /// REA-5403 replaces that with a structured field; the code stays.
        public static let recorderDisabled = Code(rawValue: "RECORDER_DISABLED")

        /// A failure with no better classification.
        public static let internalError = Code(rawValue: "INTERNAL_ERROR")
    }
}

// MARK: - Pattern matching

extension ReactorError {

    // The codes, repeated as static members of the error itself, exist for one
    // reason: `catch ReactorError.unauthorized` reads like the Python idiom it
    // replaces, and `catch ReactorError.Code.unauthorized` does not.

    /// Matches an error whose code is ``Code/invalidState``.
    public static var invalidState: Code { .invalidState }

    /// Matches an error whose code is ``Code/disconnected``.
    public static var disconnected: Code { .disconnected }

    /// Matches an error whose code is ``Code/networkError``.
    public static var networkError: Code { .networkError }

    /// Matches an error whose code is ``Code/requestTimeout``.
    public static var requestTimeout: Code { .requestTimeout }

    /// Matches an error whose code is ``Code/transportError``.
    public static var transportError: Code { .transportError }

    /// Matches an error whose code is ``Code/unauthorized``.
    public static var unauthorized: Code { .unauthorized }

    /// Matches an error whose code is ``Code/notFound``.
    public static var notFound: Code { .notFound }

    /// Matches an error whose code is ``Code/conflict``.
    public static var conflict: Code { .conflict }

    /// Matches an error whose code is ``Code/rateLimited``.
    public static var rateLimited: Code { .rateLimited }

    /// Matches an error whose code is ``Code/badRequest``.
    public static var badRequest: Code { .badRequest }

    /// Matches an error whose code is ``Code/serverError``.
    public static var serverError: Code { .serverError }

    /// Matches an error whose code is ``Code/versionMismatch``.
    public static var versionMismatch: Code { .versionMismatch }

    /// Matches an error whose code is ``Code/decodeFailed``.
    public static var decodeFailed: Code { .decodeFailed }

    /// Matches an error whose code is ``Code/sessionTerminal``.
    public static var sessionTerminal: Code { .sessionTerminal }

    /// Matches an error whose code is ``Code/messageTooLarge``.
    public static var messageTooLarge: Code { .messageTooLarge }

    /// Matches an error whose code is ``Code/aborted``.
    public static var aborted: Code { .aborted }

    /// Matches an error whose code is ``Code/recorderDisabled``.
    public static var recorderDisabled: Code { .recorderDisabled }

    /// Matches an error whose code is ``Code/internalError``.
    public static var internalError: Code { .internalError }
}

/// Matches a thrown error against a Reactor failure code.
///
/// This is what makes `catch ReactorError.unauthorized { … }` work. Anything
/// that is not a ``ReactorError`` never matches, so an unrelated error still
/// propagates.
public func ~= (code: ReactorError.Code, error: any Error) -> Bool {
    (error as? ReactorError)?.code == code
}

// MARK: - Descriptions

extension ReactorError: LocalizedError {

    /// The failure's message, which is what `localizedDescription` returns.
    public var errorDescription: String? { message }
}

extension ReactorError: CustomStringConvertible {

    /// The code and message, plus the operation and status when they are known.
    public var description: String {
        var text = "\(code): \(message)"
        if let operation { text += " (operation: \(operation)" }
        if let status {
            text += operation == nil ? " (status: \(status)" : ", status: \(status)"
        }
        if operation != nil || status != nil { text += ")" }
        return text
    }
}
