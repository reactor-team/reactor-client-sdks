import Foundation

/// What a track carries.
public enum TrackKind: String, Sendable, CaseIterable {

    /// BGRA video frames.
    case video

    /// Interleaved 16-bit PCM audio.
    case audio
}

/// Which way a track flows, from this client's point of view.
public enum TrackDirection: String, Sendable, CaseIterable {

    /// This client sends; the model receives.
    case sendonly

    /// The model sends; this client receives.
    case recvonly
}

/// A named media slot the model declares.
///
/// One type for all four combinations of kind and direction, because the
/// operations are the same operations. Ask for one by name, the way an app that
/// knows its model does:
///
/// ```swift
/// let video = try reactor.track("main_video")
/// let frames = try video.onFrame { frame in render(frame) }
/// ```
///
/// ``Reactor/tracks`` and its filters are for *discovering* what a session
/// declares, not for using it.
///
/// ## Everything here is read live
///
/// ``kind``, ``direction``, ``mid`` and ``paused`` ask the session each time.
/// Nothing is cached, and that is the design rather than an omission: a cached
/// `paused` would go on claiming `true` after a reconnect silently resumed a
/// recvonly track, and a cached declaration would survive a session that
/// declared something else. The session declares a handful of tracks, so the
/// read is a small JSON parse — cheaper than the class of bug caching invites.
///
/// The same object comes back for a given name, so handlers registered on it
/// stay registered, including across a reconnect.
///
/// `@unchecked Sendable` rather than `Sendable`, for one stored property: the
/// weak reference back to the client. Swift's weak references are atomic, and
/// nothing else here is mutable — the alternative is a box whose only job is to
/// satisfy the checker.
public final class Track: @unchecked Sendable {

    /// The declared name of this track. Never changes.
    public let name: String

    // Weak, for the reason every reference back to the client is weak here: a
    // Track parked in a view model must not be what keeps a session open.
    private weak var client: Reactor?

    init(name: String, client: Reactor) {
        self.name = name
        self.client = client
    }

    /// `video` or `audio`, or `nil` before the session declares its tracks.
    public var kind: TrackKind? {
        client?.declaration(of: name)?.kind
    }

    /// `sendonly` or `recvonly`, or `nil` before the session declares its tracks.
    public var direction: TrackDirection? {
        client?.declaration(of: name)?.direction
    }

    /// The SDP media id, once the track has been received; `nil` until then.
    ///
    /// Reported by the library as tracks arrive, and renegotiated on a reconnect.
    public var mid: String? {
        client?.mid(of: name)
    }

    /// Whether this track is currently paused.
    ///
    /// Recvonly tracks resume automatically once connected, so this is `false` on
    /// a healthy session until something pauses.
    public var paused: Bool {
        client?.pausedTracks.contains(name) ?? false
    }

    // MARK: - Receiving

    /// Receive decoded frames on this track, copied so they can be kept.
    ///
    /// The handler runs **inline on the library's delivery thread**, and that is
    /// deliberate: while it runs, the library keeps only the newest video frame
    /// and drops what arrives in between. Blocking there *is* the backpressure.
    /// Handing frames to a queue instead trades a bounded drop for unbounded
    /// latency and memory.
    ///
    /// - Throws: ``ReactorError`` with ``ReactorError/Code/invalidState`` when
    ///   this track sends rather than receives, or carries audio rather than
    ///   video. Both would otherwise be a handler that never fires — the silent
    ///   failure this SDK exists to refuse.
    public func onFrame(_ handler: @escaping @Sendable (VideoFrame) -> Void) throws -> Subscription
    {
        try requireReceivable(.video, method: "onFrame")
        guard let client else { return Subscription {} }
        return client.addVideoHandler(track: name) { raw in
            handler(
                VideoFrame(
                    trackName: raw.trackName,
                    pixels: Data(raw.pixels),
                    width: raw.width,
                    height: raw.height,
                    frameID: raw.frameID,
                    captureTimeUs: raw.captureTimeUs,
                    userData: raw.userData.map { Data($0) }))
        }
    }

    /// Receive frames without copying them.
    ///
    /// Same arguments, same thread, same backpressure as ``onFrame(_:)`` — but
    /// the buffers belong to the library and are **gone when the handler
    /// returns**. For a renderer that uploads straight to a texture, this is the
    /// version that does no work it does not need to.
    public func onRawFrame(
        _ handler: @escaping @Sendable (borrowing RawVideoFrame) -> Void
    ) throws -> Subscription {
        try requireReceivable(.video, method: "onRawFrame")
        guard let client else { return Subscription {} }
        return client.addVideoHandler(track: name, handler)
    }

    /// Receive decoded audio on this track.
    public func onAudio(_ handler: @escaping @Sendable (AudioFrame) -> Void) throws -> Subscription
    {
        try requireReceivable(.audio, method: "onAudio")
        guard let client else { return Subscription {} }
        return client.addAudioHandler(track: name, handler)
    }

    /// Refuse a handler that could never fire.
    private func requireReceivable(_ wanted: TrackKind, method: String) throws {
        guard let declaration = client?.declaration(of: name) else {
            // Nothing declared yet. Registering ahead of connect is allowed on
            // purpose — the check happens when the declaration arrives, and a
            // frame for a handler on the wrong track never reaches it anyway.
            return
        }

        if declaration.direction == .sendonly {
            throw ReactorError(
                .invalidState,
                "\(method) on '\(name)' would never fire: the session declares it sendonly, "
                    + "so this client sends on it rather than receiving. Use pushFrame instead.",
                operation: method)
        }

        if declaration.kind != wanted {
            let other = wanted == .video ? "onAudio" : "onFrame"
            throw ReactorError(
                .invalidState,
                "\(method) on '\(name)' would never fire: the session declares it "
                    + "\(declaration.kind.rawValue). Use \(other).",
                operation: method)
        }
    }
}

extension Track: CustomStringConvertible {

    /// The name, and what the session says about it.
    public var description: String {
        let kind = self.kind?.rawValue ?? "?"
        let direction = self.direction?.rawValue ?? "undeclared"
        return "Track(\(name), \(kind), \(direction))"
    }
}

/// What the session declares about one track.
struct TrackDeclaration: Sendable, Hashable {
    let name: String
    let kind: TrackKind
    let direction: TrackDirection
}
