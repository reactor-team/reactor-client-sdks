import CReactorFFI
import Foundation

/// A frame handler, in the form everything is stored as: borrowing, so nothing
/// is copied on the way in and a copying handler is just one that copies.
typealias VideoSink = @Sendable (borrowing RawVideoFrame) -> Void

extension Reactor {

    // MARK: - What the session declares

    /// Every track the session declares, in declaration order.
    ///
    /// Empty until the model's capabilities arrive, shortly after
    /// ``connect(sessionID:connectionID:)`` — which is what lets a caller tell
    /// "no tracks yet" from "no track by that name".
    public var tracks: TrackList {
        TrackList(declarations().map { track(cached: $0.name) })
    }

    /// The named track.
    ///
    /// ```swift
    /// let video = try reactor.track("main_video")
    /// ```
    ///
    /// The same object comes back every time for a given name, so handlers
    /// registered on it stay registered.
    ///
    /// Before the session declares anything, any name is accepted — that is what
    /// lets handlers be registered ahead of connecting. Once the declaration
    /// arrives, an unknown name throws.
    ///
    /// - Throws: ``ReactorError`` with ``ReactorError/Code/notFound`` for a name
    ///   the session does not declare, **naming the ones it does**. A wrong name
    ///   otherwise reaches the library, finds nothing to do, and returns — which
    ///   from the outside is indistinguishable from a model that sends nothing.
    public func track(_ name: String) throws -> Track {
        let declared = declarations()
        guard declared.isEmpty || declared.contains(where: { $0.name == name }) else {
            throw ReactorError(
                .notFound,
                "no track named '\(name)' in this session. Declared: "
                    + declared.map(\.name).joined(separator: ", "),
                operation: "track")
        }
        return track(cached: name)
    }

    /// The names of the currently paused tracks.
    ///
    /// Recvonly tracks resume automatically once connected, so this is empty on a
    /// healthy session until the caller pauses something.
    public var pausedTracks: Set<String> {
        let handle = state.withLock { $0.handle }
        guard let handle,
            let json = String(takingOwnership: ffi.pausedTracks(handle), freeing: ffi.freeString),
            let data = json.data(using: .utf8),
            let names = try? JSONSerialization.jsonObject(with: data) as? [String]
        else { return [] }
        return Set(names)
    }

    // MARK: - Internals the Track façade reads

    /// What the session says about one track, or `nil` if it declares no such
    /// track (including before it has declared anything).
    func declaration(of name: String) -> TrackDeclaration? {
        declarations().first { $0.name == name }
    }

    /// The SDP media id `on_track` reported for a name.
    func mid(of name: String) -> String? {
        state.withLock { $0.mids[name] }
    }

    /// The declared tracks, read from the library every time.
    ///
    /// Not cached, deliberately. A cache here would need invalidating on every
    /// event that can change the answer, and a read that raced one of those
    /// events would put the older answer back with nothing left to invalidate it
    /// — a newly declared track staying invisible. The list is a handful of
    /// entries and the parse is small; correctness is worth more than the parse.
    ///
    /// The order is the order the library reported, which is the order the
    /// session declared. Keeping it is part of the contract ``TrackList``
    /// documents.
    func declarations() -> [TrackDeclaration] {
        let handle = state.withLock { $0.handle }
        guard let handle,
            let json = String(takingOwnership: ffi.tracks(handle), freeing: ffi.freeString),
            let data = json.data(using: .utf8),
            let entries = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]]
        else { return [] }

        return entries.compactMap { entry in
            guard let name = entry["name"] as? String,
                let kind = (entry["kind"] as? String).flatMap(TrackKind.init(rawValue:)),
                let direction = (entry["direction"] as? String)
                    .flatMap(TrackDirection.init(rawValue:))
            else {
                // A declaration this SDK cannot read is dropped rather than
                // guessed at: a track whose kind we invented would accept the
                // wrong handlers and refuse the right ones.
                Log.media.error("dropping a track declaration this SDK cannot parse")
                return nil
            }
            return TrackDeclaration(name: name, kind: kind, direction: direction)
        }
    }

    /// The one `Track` object for `name`, created on first ask.
    func track(cached name: String) -> Track {
        state.withLock { state in
            if let existing = state.tracks[name] { return existing }
            let track = Track(name: name, client: self)
            state.tracks[name] = track
            return track
        }
    }

    // MARK: - Registering media handlers

    func addVideoHandler(track name: String, _ handler: @escaping VideoSink) -> Subscription {
        let id = UUID()
        state.withLock { $0.videoHandlers[name, default: [:]][id] = handler }
        return Subscription { [weak self] in
            self?.state.withLock { $0.videoHandlers[name]?[id] = nil }
        }
    }

    func addAudioHandler(
        track name: String,
        _ handler: @escaping @Sendable (AudioFrame) -> Void
    ) -> Subscription {
        let id = UUID()
        state.withLock { $0.audioHandlers[name, default: [:]][id] = handler }
        return Subscription { [weak self] in
            self?.state.withLock { $0.audioHandlers[name]?[id] = nil }
        }
    }

    // MARK: - Delivering media, inline

    /// Hand a frame to this track's handlers, on the thread the library called
    /// on.
    ///
    /// Nothing is queued and nothing is copied. While these handlers run the
    /// library keeps only the newest frame — blocking here is the backpressure,
    /// and a queue would trade a bounded drop for unbounded latency and memory.
    func deliver(frame: borrowing RawVideoFrame) {
        // Copied out of the borrowed frame before anything else: os.Logger's
        // interpolation is an autoclosure, and a borrowed parameter cannot be
        // captured by one.
        let name = frame.trackName
        let handlers = state.withLock { Array(($0.videoHandlers[name] ?? [:]).values) }
        guard !handlers.isEmpty else {
            // A frame for a track nobody is listening to — or one the transceiver
            // could not match to a declaration, which arrives with an empty name.
            // Dropped, and said out loud, because silence here is the failure
            // mode this SDK exists to avoid.
            Log.media.debug("dropping a frame for unhandled track '\(name)'")
            return
        }
        for handler in handlers { handler(frame) }
    }

    /// Hand an audio frame to this track's handlers, on the library's thread.
    func deliver(audio frame: AudioFrame) {
        let handlers = state.withLock { Array(($0.audioHandlers[frame.trackName] ?? [:]).values) }
        guard !handlers.isEmpty else {
            Log.media.debug("dropping audio for unhandled track '\(frame.trackName)'")
            return
        }
        for handler in handlers { handler(frame) }
    }

    /// Record the media id `on_track` reported.
    func record(mid: String?, for name: String) {
        state.withLock { $0.mids[name] = mid }
    }
}

// MARK: - Trampolines

let trackTrampoline: reactor_on_track_fn = { name, midOrNull, userdata in
    guard let name = String(borrowing: name) else { return }
    CallbackContext.from(userdata)?.client?
        .record(mid: String(borrowing: midOrNull), for: name)
}

let frameTrampoline: reactor_on_frame_fn = {
    trackName, data, width, height, frameID, timestampUS, userData, userDataLen, userdata in

    guard let client = CallbackContext.from(userdata)?.client, let data else { return }

    // Every buffer here belongs to the library until this returns.
    let pixels = UnsafeRawBufferPointer(start: data, count: Int(width) * Int(height) * 4)
    let tag: UnsafeRawBufferPointer? =
        userData.flatMap { pointer in
            userDataLen > 0 ? UnsafeRawBufferPointer(start: pointer, count: Int(userDataLen)) : nil
        }

    let frame = RawVideoFrame(
        trackName: String(borrowing: trackName) ?? "",
        pixels: pixels,
        width: width,
        height: height,
        frameID: frameID,
        captureTimeUS: timestampUS,
        userData: tag)

    client.deliver(frame: frame)
}

let audioTrampoline: reactor_on_audio_fn = {
    trackName, samples, numSamples, sampleRate, channels, userdata in

    guard let client = CallbackContext.from(userdata)?.client, let samples else { return }

    // Copied on the way in: audio is small, keeping it is the common case, and a
    // borrowing form would buy little for the complexity it costs.
    let buffer = UnsafeBufferPointer(start: samples, count: Int(numSamples))

    client.deliver(
        audio: AudioFrame(
            trackName: String(borrowing: trackName) ?? "",
            samples: Array(buffer),
            sampleRate: sampleRate,
            channels: channels))
}
