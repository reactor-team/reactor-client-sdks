import Foundation

/// How this SDK encodes and decodes JSON — the policy in one place, the
/// **instances never shared**.
///
/// `JSONEncoder` and `JSONDecoder` are classes with mutable state and are not
/// safe to use from two threads at once. Sharing one instance here segfaulted the
/// test suite (`signal code 11`) the moment several suites decoded in parallel,
/// and it would have done the same in an app: this SDK decodes an event on the
/// library's control thread while the caller's own task decodes a command reply.
///
/// So these are factories. An allocation per call is nothing next to the parse,
/// and it is the difference between a crash and no crash.
enum JSON {

    /// An encoder for everything this SDK sends.
    ///
    /// `.withoutEscapingSlashes` because Foundation escapes `/` as `\/` by
    /// default. It is legal JSON and the platform reads it either way — but a
    /// model name is `owner/name` and a mime type is `image/jpeg`, so the default
    /// turns every log line into `reactor\/helios`.
    static func encoder() -> JSONEncoder {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.withoutEscapingSlashes]
        return encoder
    }

    /// A decoder for everything this SDK reads.
    static func decoder() -> JSONDecoder {
        JSONDecoder()
    }
}
