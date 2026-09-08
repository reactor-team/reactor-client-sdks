import Foundation

extension ReactorError {

    /// Build the error for a payload the FFI reported.
    ///
    /// Three shapes have to work, and only one of them is the documented one:
    ///
    /// - the JSON object the header describes — `{ code, message, recoverable,
    ///   status?, operation?, retry_after_ms?, timestamp_ms? }`;
    /// - **nothing at all**, when a completion failed without saying why;
    /// - **a bare human-readable string**, which is what a `libreactor_ffi`
    ///   older than this SDK sends. An SDK is not always paired with the exact
    ///   library it shipped with, and the failure mode of guessing wrong here is
    ///   throwing from inside the error path — the one place a throw has nowhere
    ///   to go.
    ///
    /// So this never fails. Anything it cannot understand becomes the message.
    static func decode(payload: String?) -> ReactorError {
        guard let payload, !payload.isEmpty else {
            return ReactorError(.internalError, "unknown error")
        }

        guard let object = jsonObject(from: payload) else {
            // Valid JSON that is not an object (a bare string, a number), or not
            // JSON at all. Either way the text is the most useful thing we have.
            return ReactorError(.internalError, payload)
        }

        // An empty or missing code is not a code. The header promises one is
        // always present; this is what happens when that promise is broken,
        // rather than an error whose code is "".
        let rawCode = (object["code"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        let message = (object["message"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? payload

        return ReactorError(
            code: rawCode.map(Code.init(rawValue:)) ?? .internalError,
            message: message,
            recoverable: object["recoverable"] as? Bool ?? false,
            status: object["status"] as? Int,
            operation: object["operation"] as? String,
            retryAfterMS: object["retry_after_ms"] as? Double,
            timestampMS: object["timestamp_ms"] as? Double
        )
    }

    /// Parse `text` as a JSON object, or `nil` if it is anything else.
    private static func jsonObject(from text: String) -> [String: Any]? {
        guard let data = text.data(using: .utf8) else { return nil }
        // .fragmentsAllowed so a bare `"boom"` parses and is then rejected by
        // the cast below, rather than being reported as a JSON syntax error and
        // taking the same path for a different reason.
        let parsed = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
        return parsed as? [String: Any]
    }
}
