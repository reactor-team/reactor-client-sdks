import Foundation

/// A recording the platform is assembling, or has assembled.
///
/// Reactor does not host clips: the playlist names the fragments, and fetching
/// and assembling them is ``Reactor/download(_:to:readyTimeout:progress:)``'s
/// job — which is the library's job, so every binding gets the same three rules
/// right without learning them again.
public struct Clip: Sendable, Hashable {

    /// The HLS playlist to download.
    public let playlistURL: String

    /// The session this clip came from.
    public let sessionID: String?

    /// What kind of recording this is, as the platform names it.
    public let kind: String?

    /// The runtime's own guess at when the clip will be ready, in Unix
    /// milliseconds, or `nil` when it offered none.
    ///
    /// **A wall clock plus media seconds**, so it is only right for a model
    /// generating at real time: a model at a tenth of that reaches the boundary
    /// ten times later. It is a starting point for a grace period, never a
    /// deadline.
    public let predictedReadyAtMS: Double?

    /// Everything the platform sent, for fields this SDK does not name.
    public let raw: JSONValue

    /// Read a clip from the platform's reply.
    init(payload: JSONValue) throws {
        guard let playlistURL = payload["playlist_url"]?.stringValue else {
            throw ReactorError(
                .decodeFailed,
                "the recording reply carries no playlist_url, so there is nothing to download",
                operation: "request_clip")
        }
        self.playlistURL = playlistURL
        self.sessionID = payload["session_id"]?.stringValue
        self.kind = payload["kind"]?.stringValue
        self.predictedReadyAtMS = payload["predicted_ready_at_ms"]?.doubleValue
        self.raw = payload
    }
}

/// What a finished download produced.
public struct DownloadResult: Sendable, Hashable {

    /// Where the file was written.
    public let path: URL

    /// How many bytes it holds.
    public let bytes: Int

    /// How many HLS segments went into it.
    public let segments: Int
}

/// How far along a download is.
public struct DownloadProgress: Sendable, Hashable {

    /// Segments written so far.
    public let done: Int

    /// Segments the clip has in total.
    public let total: Int

    /// `done` over `total`, or 0 when the total is not known yet.
    public var fraction: Double {
        total > 0 ? Double(done) / Double(total) : 0
    }
}
