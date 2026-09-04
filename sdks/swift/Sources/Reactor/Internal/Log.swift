import os

/// Where the SDK says something the caller did not ask to be told.
///
/// Two categories, because they answer different questions: `media` is about
/// frames the SDK dropped, which is what someone debugging "nothing renders"
/// needs; `client` is about the session. Both go through `os.Logger`, so nothing
/// reaches a console unless someone goes looking — this is a library, and a
/// library that prints is a library people wrap in a filter.
enum Log {

    /// Frames and tracks: what arrived, and what was dropped for want of a
    /// handler.
    static let media = Logger(subsystem: "inc.reactor.sdk", category: "media")

    /// Session lifecycle.
    static let client = Logger(subsystem: "inc.reactor.sdk", category: "client")
}
