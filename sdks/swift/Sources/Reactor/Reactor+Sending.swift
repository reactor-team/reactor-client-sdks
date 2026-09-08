import CReactorFFI
import Foundation

extension Reactor {

    /// The engine's monotonic clock, in microseconds — the epoch a capture time
    /// is read in.
    ///
    /// Read it **once per unit of produced media** and stamp every track with
    /// that one value: tracks are synchronised by sharing a capture time, not by
    /// reaching the encoder at the same moment.
    ///
    /// Unrelated to `time(2)`'s epoch. A UNIX timestamp is not a substitute.
    public static func timeMicros() -> Int64 {
        FFI.system.timeMicros()
    }

    // MARK: - Publishing state, which only this binding knows

    /// What this client believes about a track's send slot.
    func publishState(of name: String) -> PublishState {
        state.withLock { $0.publishStates[name] ?? .unpublished }
    }

    func setPublishState(_ value: PublishState, for name: String) {
        state.withLock { $0.publishStates[name] = value }
    }

    /// The current publish generation, to be handed back to
    /// ``setPublishState(_:for:ifGeneration:)`` once an `await` returns.
    var publishGeneration: UInt64 {
        state.withLock { $0.publishGeneration }
    }

    /// Write a publish state, unless the session moved on while the caller was
    /// awaiting. Answers whether the write happened.
    ///
    /// A publish is an `await`: the library answers on its own thread and the
    /// awaiting task resumes later, so a transport drop can land in between.
    /// Written unconditionally, the resumed task put `.published` back over the
    /// state ``clearPublishStates()`` had just dropped — and the client then
    /// accepted pushes into a slot the new connection has no sender for, and
    /// dropped every one of them silently.
    @discardableResult
    func setPublishState(
        _ value: PublishState, for name: String, ifGeneration generation: UInt64
    ) -> Bool {
        state.withLock { state in
            guard state.publishGeneration == generation else { return false }
            state.publishStates[name] = value
            return true
        }
    }

    /// Forget every publish, because the session left `ready`.
    ///
    /// A reconnect resumes recvonly tracks and **nothing else**: a slot published
    /// before one is not published after it. Remembering otherwise is exactly the
    /// silent failure this SDK refuses — a caller pushing at 30fps into a slot
    /// with no sender behind it.
    func clearPublishStates() {
        let dropped: Int = state.withLock { state in
            let count = state.publishStates.count
            state.publishStates = [:]
            // Bumped even when nothing was published: a publish that has not yet
            // recorded anything is exactly the one that must not record it now.
            state.publishGeneration &+= 1
            return count
        }
        if dropped > 0 {
            Log.client.debug("status left ready; \(dropped) published track(s) forgotten")
        }
    }

    // MARK: - Pushing

    /// Push BGRA pixels into a sendonly video track.
    func push(
        track name: String,
        pixels: UnsafeRawBufferPointer,
        width: UInt32,
        height: UInt32,
        userData: UnsafeRawBufferPointer?,
        captureTimeUs: Int64
    ) throws {
        let handle = try requirePushable(track: name, kind: .video, method: "pushFrame")

        // Checked rather than `Int(width) * Int(height) * 4`: both are UInt32 and
        // the caller picks them, so dimensions near UInt32.max overflow Int and
        // trap — turning a malformed frame into a process crash instead of the
        // refusal this guard exists to make.
        let pixelCount = Int(width).multipliedReportingOverflow(by: Int(height))
        let byteCount =
            pixelCount.overflow
            ? (partialValue: 0, overflow: true)
            : pixelCount.partialValue.multipliedReportingOverflow(by: 4)

        guard !byteCount.overflow else {
            throw ReactorError(
                .badRequest,
                "pushFrame on '\(name)' was given \(width)x\(height), whose BGRA byte count "
                    + "does not fit in an Int — no buffer can be that large.",
                operation: "pushFrame")
        }

        let expected = byteCount.partialValue
        guard pixels.count >= expected else {
            // The FFI reads width * height * 4 bytes whatever it was handed, so
            // a short buffer is a read past the end rather than a smaller frame.
            throw ReactorError(
                .badRequest,
                "pushFrame on '\(name)' was given \(pixels.count) bytes for a "
                    + "\(width)x\(height) BGRA frame, which needs \(expected).",
                operation: "pushFrame")
        }

        let base = pixels.bindMemory(to: UInt8.self).baseAddress
        if let userData, !userData.isEmpty {
            let tag = userData.bindMemory(to: UInt8.self).baseAddress
            ffi.pushVideoFrame(
                handle, name, base, width, height, tag, UInt32(userData.count), captureTimeUs)
        } else {
            ffi.pushVideoFrame(handle, name, base, width, height, nil, 0, captureTimeUs)
        }
    }

    /// Push interleaved i16 PCM into a sendonly audio track.
    func push(
        track name: String,
        samples: [Int16],
        sampleRate: UInt32,
        channels: UInt32
    ) throws {
        let handle = try requirePushable(track: name, kind: .audio, method: "pushAudioFrame")

        guard channels > 0, samples.count % Int(channels) == 0 else {
            throw ReactorError(
                .badRequest,
                "pushAudioFrame on '\(name)' was given \(samples.count) samples for "
                    + "\(channels) channel(s), which does not divide evenly.",
                operation: "pushAudioFrame")
        }

        let perChannel = UInt32(samples.count / Int(channels))
        samples.withUnsafeBufferPointer { buffer in
            ffi.pushAudioFrame(handle, name, buffer.baseAddress, perChannel, sampleRate, channels)
        }
    }

    /// Everything that has to be true before a push can reach the wire.
    ///
    /// Each of these is a case the FFI accepts and then does nothing about: it
    /// finds no sender, or the wrong kind of one, and returns. From the outside
    /// that is a loop pushing at 30fps into a model that receives nothing.
    private func requirePushable(
        track name: String,
        kind: TrackKind,
        method: String
    ) throws -> OpaquePointer {
        guard let handle = state.withLock({ $0.closed ? nil : $0.handle }) else {
            throw ReactorError(
                .invalidState,
                "the client is closed, so \(method) on '\(name)' cannot run.",
                operation: method)
        }

        if let declaration = declaration(of: name) {
            guard declaration.direction == .sendonly else {
                throw ReactorError(
                    .invalidState,
                    "\(method) on '\(name)' goes nowhere: the session declares it "
                        + "\(declaration.direction.rawValue), so the model sends on it and this "
                        + "client receives. Use onFrame instead.",
                    operation: method)
            }
            guard declaration.kind == kind else {
                throw ReactorError(
                    .invalidState,
                    "\(method) on '\(name)' goes nowhere: the session declares it "
                        + "\(declaration.kind.rawValue).",
                    operation: method)
            }
        }

        switch publishState(of: name) {
        case .published:
            return handle
        case .publishing:
            throw ReactorError(
                .invalidState,
                "'\(name)' is being published and has no sender behind it yet, so this frame "
                    + "would be dropped. Await publish() before pushing.",
                operation: method)
        case .unpublished:
            throw ReactorError(
                .invalidState,
                "'\(name)' is not published, so this frame would be dropped. Call publish() "
                    + "first — and again after any reconnect, which resumes recvonly tracks "
                    + "and nothing else.",
                operation: method)
        }
    }
}

extension Track {

    /// Whether this client has published this track's send slot.
    ///
    /// Kept by the binding, because the session records none of it: publishing is
    /// a request and unpublishing a notification, and neither leaves anything to
    /// query. It is forgotten whenever the status leaves `ready`.
    public var published: Bool {
        client?.publishState(of: name) == .published
    }

    /// Activate this track's send slot.
    ///
    /// Publishing is what puts a sender behind the slot; attaching media is
    /// separate, and a push before this completes is dropped by the library.
    ///
    /// **A publish does not survive the session leaving `ready`.** A reconnect
    /// resumes recvonly tracks and nothing else, so publish again after one.
    public func publish() async throws {
        guard let client else { return }
        try requireSendable(method: "publish")

        // Captured before the call and checked after it: everything below has to
        // be about the connection this publish was asked on.
        let generation = client.publishGeneration
        client.setPublishState(.publishing, for: name, ifGeneration: generation)
        do {
            _ = try await client.perform("publish_track") { handle, completion, userdata in
                self.name.withCString { namePointer in
                    client.ffi.publishTrack(handle, namePointer, completion, userdata)
                }
            }
        } catch {
            // Back to where it was: a failed publish that left the state at
            // `publishing` would refuse every later push with "await the
            // publish" for a publish that is never coming.
            client.setPublishState(.unpublished, for: name, ifGeneration: generation)
            throw error
        }

        guard client.setPublishState(.published, for: name, ifGeneration: generation) else {
            // The session left `ready` while this was in flight. The library
            // answered about a connection that is gone, and the one that came
            // back has no sender behind this slot — so reporting success here
            // would hand the caller a track that silently drops every push.
            throw ReactorError(
                .disconnected,
                "publish of '\(name)' was answered, but the session left ready before it "
                    + "completed, so the track is not published. Publish again — a reconnect "
                    + "resumes recvonly tracks and nothing else.",
                operation: "publish")
        }
    }

    /// Deactivate this track's send slot.
    ///
    /// Synchronous, because the library's own call is: a local status change plus
    /// a fire-and-forget notification, with no round trip to wait for.
    public func unpublish() throws {
        guard let client else { return }
        let handle = try client.requireOpenHandle(method: "unpublish", track: name)

        let failure = name.withCString { namePointer in
            String(
                takingOwnership: client.ffi.unpublishTrack(handle, namePointer),
                freeing: client.ffi.freeString)
        }

        if let failure {
            // Only a *successful* unpublish clears the state. Clearing it on a
            // failure makes the failure unretryable: the next unpublish would be
            // refused for a track this client still believes it published.
            throw ReactorError.decode(payload: failure)
        }
        client.setPublishState(.unpublished, for: name)
    }

    /// Pause this track's transceiver.
    ///
    /// Nothing is generated while paused, which from the outside looks like a
    /// frozen frame rather than an error.
    public func pause() async throws {
        guard let client else { return }
        _ = try await client.perform("pause_track") { handle, completion, userdata in
            self.name.withCString { namePointer in
                client.ffi.pauseTrack(handle, namePointer, completion, userdata)
            }
        }
    }

    /// Resume this track's transceiver.
    public func resume() async throws {
        guard let client else { return }
        _ = try await client.perform("resume_track") { handle, completion, userdata in
            self.name.withCString { namePointer in
                client.ffi.resumeTrack(handle, namePointer, completion, userdata)
            }
        }
    }

    // MARK: - Pushing media

    /// Push a BGRA frame into this track.
    ///
    /// - Parameters:
    ///   - pixels: `width * height * 4` bytes, blue-green-red-alpha.
    ///   - userData: bytes to tag the frame with. Their meaning is between the
    ///     caller and the model. A tag is dropped unless the far end declared
    ///     that it reads them, so tagging is safe whatever the far end supports.
    ///   - captureTimeUs: when this frame was captured, read from
    ///     ``Reactor/timeMicros()``. Pass the **same** value for every track of
    ///     one capture and the far end reads them as the one moment they are.
    ///     Without it the frame is stamped as it is pushed, so several tracks
    ///     capturing one moment arrive stamped microseconds apart.
    public func pushFrame(
        _ pixels: Data,
        width: UInt32,
        height: UInt32,
        userData: Data? = nil,
        captureTimeUs: Int64? = nil
    ) throws {
        guard let client else { return }
        try pixels.withUnsafeBytes { pixelBytes in
            if let userData {
                try userData.withUnsafeBytes { tagBytes in
                    try client.push(
                        track: name, pixels: pixelBytes, width: width, height: height,
                        userData: tagBytes, captureTimeUs: captureTimeUs ?? 0)
                }
            } else {
                try client.push(
                    track: name, pixels: pixelBytes, width: width, height: height,
                    userData: nil, captureTimeUs: captureTimeUs ?? 0)
            }
        }
    }

    /// Push a BGRA frame the caller already holds as a buffer, copying nothing.
    public func pushFrame(
        _ pixels: UnsafeRawBufferPointer,
        width: UInt32,
        height: UInt32,
        userData: UnsafeRawBufferPointer? = nil,
        captureTimeUs: Int64? = nil
    ) throws {
        guard let client else { return }
        try client.push(
            track: name, pixels: pixels, width: width, height: height, userData: userData,
            captureTimeUs: captureTimeUs ?? 0)
    }

    /// Push interleaved 16-bit PCM into this track.
    ///
    /// The rate and channel count must match what the source declared — 48 kHz
    /// mono for every model today.
    public func pushAudioFrame(
        _ samples: [Int16],
        sampleRate: UInt32 = 48000,
        channels: UInt32 = 1
    ) throws {
        guard let client else { return }
        try client.push(
            track: name, samples: samples, sampleRate: sampleRate, channels: channels)
    }

    /// Refuse an operation that needs a track this client can send on.
    private func requireSendable(method: String) throws {
        guard let declaration = client?.declaration(of: name) else { return }
        guard declaration.direction == .sendonly else {
            throw ReactorError(
                .invalidState,
                "\(method) on '\(name)' is not something this client can do: the session "
                    + "declares it \(declaration.direction.rawValue), so the model sends on it.",
                operation: method)
        }
    }
}
