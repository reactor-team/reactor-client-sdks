import CReactorFFI
import Foundation

extension Reactor {

    /// What a minted token is allowed to do.
    ///
    /// The three keys the platform accepts, and no others: the header is explicit
    /// that an **unrecognised key in this object is an error**, precisely so a
    /// misspelt `models` cannot be dropped in silence and mint exactly the
    /// unscoped token the caller was avoiding. A struct with three fields makes
    /// that impossible to get wrong.
    public struct TokenOptions: Sendable, Hashable, Encodable {

        /// The models this token may reach, as `owner/name`.
        ///
        /// Left empty, the token carries everything the key's roles allow — fine
        /// server-to-server, wrong to hand to a client you do not control.
        public var models: [String]?

        /// How many sessions the token may open. Scoped tokens only.
        public var maxSessions: Int?

        /// How long the token should live, in seconds. The server clamps it.
        public var expiresAfter: Int?

        /// The wire spelling, which is the platform's rather than Swift's.
        private enum CodingKeys: String, CodingKey {
            case models
            case maxSessions = "max_sessions"
            case expiresAfter = "expires_after"
        }

        /// Scope a token.
        public init(models: [String]? = nil, maxSessions: Int? = nil, expiresAfter: Int? = nil) {
            self.models = models
            self.maxSessions = maxSessions
            self.expiresAfter = expiresAfter
        }
    }

    /// Exchange an API key for a JWT.
    ///
    /// ```swift
    /// let token = try await Reactor.fetchJWT(
    ///     apiKey: key, options: .init(models: ["reactor/helios"]))
    /// ```
    ///
    /// This is one POST, and it lives in the library rather than here so a
    /// binding does not have to take on a TLS stack to make it.
    ///
    /// - Note: unlike everything else in this SDK, `reactor_fetch_jwt` takes no
    ///   handle — so nothing bounds its completion, and a null key *completes
    ///   with an error* rather than returning without completing. The context is
    ///   released from inside the completion, which is the only place that can
    ///   know that is safe.
    public static func fetchJWT(
        apiKey: String,
        apiURL: String = Reactor.defaultAPIURL,
        options: TokenOptions? = nil,
        local: Bool = false
    ) async throws -> String {
        try await fetchJWT(
            apiKey: apiKey, apiURL: apiURL, options: options, local: local, ffi: .system)
    }

    /// The injectable form, for tests.
    static func fetchJWT(
        apiKey: String,
        apiURL: String,
        options: TokenOptions?,
        local: Bool,
        ffi: FFI
    ) async throws -> String {
        let optionsJSON = try options.map { value -> String in
            let data = try JSON.encoder().encode(value)
            guard let text = String(data: data, encoding: .utf8) else {
                throw ReactorError(
                    .badRequest, "token options are not valid UTF-8", operation: "fetch_jwt")
            }
            return text
        }

        // The same resolution the client initialiser makes, and for the same
        // reason: `local: true` without an explicit URL means the local
        // coordinator. Resolved here rather than left to the caller, so the token
        // and the client it is for can never come from different coordinators.
        let resolvedURL = Reactor.resolveAPIURL(apiURL, local: local)

        let request = TokenRequest()
        // The continuation's type is written out rather than inferred: the reply
        // may legitimately be absent, and letting it infer `String` here makes
        // "no payload" a trap instead of the decode failure below.
        let payload: String? = try await withCheckedThrowingContinuation {
            (continuation: CheckedContinuation<String?, any Error>) in
            request.attach(continuation)
            // Retained here and released by the completion — the only boundary
            // there is, since there is no handle whose destroy could bound it.
            let userdata = Unmanaged.passRetained(request).toOpaque()
            resolvedURL.withCString { urlPointer in
                apiKey.withCString { keyPointer in
                    withOptionalCString(optionsJSON) { optionsPointer in
                        ffi.fetchJWT(
                            urlPointer, keyPointer, optionsPointer, local ? 1 : 0,
                            tokenCompletionTrampoline, userdata)
                    }
                }
            }
        }

        // Decoded after the continuation has settled, which is safe here and only
        // here: this is ordinary code on the caller's own task rather than a
        // callback, so a throw reaches the caller instead of nowhere.
        guard let payload, let data = payload.data(using: .utf8),
            let value = try? JSON.decoder().decode(JSONValue.self, from: data),
            let token = value["jwt"]?.stringValue, !token.isEmpty
        else {
            throw ReactorError(
                .decodeFailed,
                "the coordinator accepted the key but its reply carried no jwt",
                operation: "fetch_jwt")
        }
        return token
    }

    /// Create a client straight from an API key.
    ///
    /// Exchanges the key for a token and then creates the client, so a first
    /// script is two lines rather than four.
    ///
    /// Prefer ``fetchJWT(apiKey:apiURL:options:local:)`` and the token
    /// initialiser when one token serves several clients, or when the token is
    /// minted somewhere else entirely — which is what a real app does, because an
    /// API key does not belong on a device.
    public convenience init(
        model: String,
        apiKey: String,
        apiURL: String = Reactor.defaultAPIURL,
        options: TokenOptions? = nil,
        local: Bool = false,
        eventQueue: DispatchQueue? = nil
    ) async throws {
        let token = try await Reactor.fetchJWT(
            apiKey: apiKey, apiURL: apiURL, options: options, local: local)
        try self.init(
            model: model, jwt: token, apiURL: apiURL, local: local, eventQueue: eventQueue)
    }
}

/// One token exchange waiting on its completion.
///
/// The same settle-once discipline as ``PendingCompletion``, without an owner:
/// there is no client here, so nothing but the completion can settle it.
final class TokenRequest: @unchecked Sendable {

    private let state = Locked<CheckedContinuation<String?, any Error>?>(nil)

    func attach(_ continuation: CheckedContinuation<String?, any Error>) {
        state.withLock { $0 = continuation }
    }

    func complete(ok: Int32, resultJSON: UnsafePointer<CChar>?, errorJSON: UnsafePointer<CChar>?) {
        let outcome: Result<String?, any Error> =
            ok == 1
            ? .success(String(borrowing: resultJSON))
            : .failure(ReactorError.decode(payload: String(borrowing: errorJSON)))

        let continuation = state.withLock { stored -> CheckedContinuation<String?, any Error>? in
            defer { stored = nil }
            return stored
        }
        continuation?.resume(with: outcome)
    }
}

/// The C function `reactor_fetch_jwt` calls back into.
let tokenCompletionTrampoline: reactor_completion_fn = { ok, resultJSON, errorJSON, userdata in
    guard let userdata else { return }
    // The completion fires exactly once, and this is where the context's life
    // ends — released from inside it, as the header says to.
    let request = Unmanaged<TokenRequest>.fromOpaque(userdata).takeRetainedValue()
    request.complete(ok: ok, resultJSON: resultJSON, errorJSON: errorJSON)
}
