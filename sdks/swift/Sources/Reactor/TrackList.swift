/// The tracks a session declares, **in declaration order**.
///
/// The order is part of the contract: `tracks[0]` is the first track the session
/// declared, and every SDK promises the same. Collecting them into a
/// name-keyed dictionary would sort them alphabetically and silently renumber
/// what that index means for every caller, which is why this keeps the sequence
/// and looks names up by scanning it — a session declares a handful of tracks,
/// not a dictionary's worth.
///
/// Filters chain, in either order:
///
/// ```swift
/// let incoming = try reactor.tracks.withDirection(.recvonly).withKind(.video).one()
/// ```
///
/// That is for discovery. An app that knows its model asks by name:
/// `try reactor.track("main_video")`.
public struct TrackList: Sendable, RandomAccessCollection {

    private let tracks: [Track]

    init(_ tracks: [Track]) {
        self.tracks = tracks
    }

    /// The first index. Always 0 — this is an array underneath.
    public var startIndex: Int { tracks.startIndex }

    /// One past the last index.
    public var endIndex: Int { tracks.endIndex }

    /// The track at `position`, counting from the order the session declared.
    public subscript(position: Int) -> Track { tracks[position] }

    /// Only the tracks carrying `kind`.
    public func withKind(_ kind: TrackKind) -> TrackList {
        TrackList(tracks.filter { $0.kind == kind })
    }

    /// Only the tracks flowing `direction`.
    public func withDirection(_ direction: TrackDirection) -> TrackList {
        TrackList(tracks.filter { $0.direction == direction })
    }

    /// The single track in this list.
    ///
    /// For the common shape — "the model's video output", "the microphone slot" —
    /// where the caller means one track and a second or a missing one is a
    /// mistake worth hearing about rather than an index that happens to work.
    ///
    /// - Throws: ``ReactorError`` naming what is actually here.
    public func one() throws -> Track {
        if tracks.count == 1, let only = tracks.first {
            return only
        }
        if tracks.isEmpty {
            throw ReactorError(
                .notFound,
                "no track matches, so there is no one() to take",
                operation: "one")
        }
        throw ReactorError(
            .conflict,
            "one() wants a single track and \(tracks.count) match: "
                + tracks.map(\.name).joined(separator: ", ")
                + ". Narrow the filter, or pick from the list.",
            operation: "one")
    }
}
