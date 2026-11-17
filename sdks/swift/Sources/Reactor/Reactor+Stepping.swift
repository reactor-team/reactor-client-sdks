import CReactorFFI
import Foundation

extension Reactor {

    // MARK: - Stepped generation

    /// Run one step of a stepped session and return what it produced.
    ///
    /// The runtime applies `state` (each key as the model's `set_<key>`
    /// command), then `commands` in order, then any input frames the step
    /// carries, and then generates. In a stepped session
    /// ``Track/pushFrame(_:width:height:userData:captureTimeUs:)`` sends a frame
    /// to the runtime at once, ahead of the next step, once the session is ready
    /// and the runtime takes pushed frames, and buffers it for the next step
    /// otherwise. The runtime checks the whole request before it applies
    /// any of it, so a refused step changes nothing. Past that check each part
    /// is its own call into the model: a handler that fails stops the step, and
    /// the calls before it stay applied.
    ///
    /// Every argument is optional. `step()` with nothing set and nothing pushed
    /// runs on the model's current state — which commands sent outside a step,
    /// through ``sendCommand(_:_:uploads:)``, can change in between.
    ///
    /// A file goes in as it does for any command: upload it with
    /// ``uploadFile(at:)`` or ``uploadData(_:name:mimeType:)`` first, then
    /// put the ``FileRef`` in a command's arguments
    /// (``StepCommand/init(_:arguments:)``) or, as `JSONValue(fileRef)`,
    /// in the state value of a field that takes an upload.
    ///
    /// ```swift
    /// let reactor = try await Reactor(model: "owner/model", apiKey: key, generation: .stepped)
    /// try await reactor.connect()
    ///
    /// let camera = try reactor.track("camera")
    /// try await camera.publish()
    /// try camera.pushFrame(pixels, width: 640, height: 360)   // reaches the runtime before the step
    ///
    /// let result = try await reactor.step(
    ///     state: ["prompt": "a red fox"],
    ///     commands: [StepCommand("advance", ["frames": 4])])
    /// for frame in try result.track("main_video").frames {
    ///     show(frame.data, shape: frame.shape)                 // (height, width, 3) uint8 RGB
    /// }
    /// ```
    ///
    /// `media` gives the step frames of its own, by input track name, after the
    /// ones pushed to that track since the previous step: raw frames as
    /// ``Track/pushFrame(_:width:height:userData:captureTimeUs:)`` and
    /// ``Track/pushFrame(_:sampleRate:channels:)`` take them, or
    /// ``EncodedFrame``s, which go as they are and are decoded by the runtime
    /// before the step runs:
    ///
    /// ```swift
    /// let frames = window.map { EncodedFrame($0, width: 1280, height: 720) }
    /// let result = try await reactor.step(media: ["webcam": frames.map(StepMediaFrame.encoded)])
    /// ```
    ///
    /// It is the same as pushing them just before the step, so each track must
    /// be published first. They are pushed only once `state` and `commands` are
    /// known to be well formed and every frame passes its push's checks, so a
    /// bad track or frame anywhere in `media` throws its push's error before any
    /// of it is pushed: the buffer is left as it was, and the step is not sent.
    ///
    /// Video step frames may travel encoded between this client and the
    /// runtime — see ``setCodecPreference(_:track:)`` and
    /// ``stepFrameEncodings`` — but the result's video frames are RGB either
    /// way, decoded here.
    ///
    /// - Parameters:
    ///   - state: the model's state fields to set before the commands run, or
    ///     `nil` to leave the state as it is. The model keeps it until changed.
    ///   - commands: commands to run after the state, in order.
    ///   - media: frames for this step, by input track name, each track's in
    ///     order. Tracks are pushed in the order of their names.
    ///   - requestKeyframe: ask the runtime to start every encoded output track
    ///     of this step's result at a keyframe — for a caller that drops what
    ///     it decoded so far. The client already asks on its own when one of
    ///     its decoders loses its state, and `false` never stops a keyframe the
    ///     runtime sends anyway. No effect on a track that travels raw.
    ///
    /// - Throws: ``ReactorError``:
    ///   - ``ReactorError/Code/stepRefused`` — the runtime or the model turned
    ///     the step down; nothing it carried was applied.
    ///     ``ReactorError/entry`` names the part at fault, when one is.
    ///   - ``ReactorError/Code/stepNotReady`` — the session cannot take a step
    ///     yet. The step did not run.
    ///   - ``ReactorError/Code/stepResultTooLarge`` — the step ran, but its
    ///     output does not fit in one message. Ask for less per step.
    ///   - ``ReactorError/Code/stepCommandFailed`` — the handler of one of the
    ///     step's state keys or commands failed. The calls before it stay
    ///     applied, the ones after it were skipped, and the step did not run.
    ///   - ``ReactorError/Code/stepSessionEnded`` — the session ended before
    ///     the step's result could be sent; whether the step ran is unknown.
    ///   - ``ReactorError/Code/messageTooLarge`` — the request does not fit.
    ///     Nothing was sent.
    ///   - ``ReactorError/Code/unsupportedFrameEncoding`` — the result carries a
    ///     frame this library cannot decode.
    ///   - ``ReactorError/Code/requestTimeout`` — the step took longer than the
    ///     client's `stepTimeout`.
    ///   - ``ReactorError/Code/invalidState`` — not a stepped client, or not a
    ///     ready session.
    ///   - ``ReactorError/Code/badRequest`` — a command with an empty name.
    ///   - ``ReactorError/Code/notFound`` — `media` names a track the session
    ///     does not declare.
    ///   - Any error of the push one of `media`'s frames goes through.
    ///
    ///   A step that ran but that the model reported as failed does not throw:
    ///   it returns a result whose ``StepResult/error`` says why.
    ///
    ///   A step refused before it goes out (``ReactorError/Code/invalidState``,
    ///   ``ReactorError/Code/badRequest``) keeps the buffered frames for the
    ///   next step. Past that they belong to the step whatever its outcome:
    ///   one that could not be sent drops them too, and nothing is resent.
    public func step(
        state: [String: JSONValue]? = nil,
        commands: [StepCommand] = [],
        media: [String: [StepMediaFrame]] = [:],
        requestKeyframe: Bool = false
    ) async throws -> StepResult {
        guard generation == .stepped else {
            throw ReactorError(
                .invalidState,
                "step() needs a stepped session: create the client with generation: .stepped",
                operation: "step")
        }
        if let unnamed = commands.firstIndex(where: { $0.command.isEmpty }) {
            throw ReactorError(
                .badRequest, "commands[\(unnamed)] has an empty name", operation: "step")
        }

        let stateJSON = try state.map { try encodeJSON(JSONValue.object($0), operation: "step") }
        let commandsJSON = try encodeJSON(commands, operation: "step")
        // Checked only once the state and commands are known to be well formed, so
        // a step refused for those leaves the buffer as the caller left it.
        let pushes = try prepareStepMedia(media)

        return try await perform(
            PendingStep(owner: self, ffi: ffi), completion: stepCompletionTrampoline
        ) { handle, completion, userdata in
            // Pushed here, with the live handle and under the same barrier as the
            // step that takes them: a close() cannot destroy the handle mid-batch,
            // and no other step's pushes can land between these and reactor_step.
            for push in pushes {
                push(handle)
            }
            withOptionalCString(stateJSON) { statePointer in
                commandsJSON.withCString { commandsPointer in
                    // Only a step that asks for something goes through the
                    // options entry; every other step is the plain one it was.
                    guard requestKeyframe else {
                        self.ffi.step(handle, statePointer, commandsPointer, completion, userdata)
                        return
                    }
                    Reactor.requestKeyframeOptions.withCString { optionsPointer in
                        self.ffi.stepWithOptions(
                            handle, statePointer, commandsPointer, optionsPointer, completion,
                            userdata)
                    }
                }
            }
        }
    }

    /// What `reactor_step_with_options` is handed for a step that asks for
    /// keyframes.
    static let requestKeyframeOptions = #"{"request_keyframe":true}"#

    // MARK: - Step frame codecs

    /// Choose the codecs this client's stepped sessions carry their video step
    /// frames in, most preferred first.
    ///
    /// ```swift
    /// try reactor.setCodecPreference([.vp9, .h264])            // VP9 when both ends have it
    /// try reactor.setCodecPreference([])                       // encoding off: raw frames
    /// try reactor.setCodecPreference([.av1], track: "main_video")
    /// try reactor.setCodecPreference(nil)                      // back to the default
    /// ```
    ///
    /// Without a preference a session prefers H.264, then VP9, AV1 and VP8. Each
    /// list is filtered by what this machine can encode (input tracks) or decode
    /// (output tracks), and the runtime picks per track from what is left; a
    /// track they share no codec for stays raw.
    ///
    /// Whatever the codec, ``Track/pushFrame(_:width:height:userData:captureTimeUs:)``
    /// takes the same raw pixels as before and the video frames
    /// ``step(state:commands:media:requestKeyframe:)`` returns are still RGB:
    /// the encoding is between this client and the runtime, and this library
    /// encodes and decodes it.
    ///
    /// Callable before ``connect(sessionID:connectionID:)`` or during a session.
    /// It takes effect on the **next connection** — the next connect or
    /// ``reconnect()`` — and the live connection keeps what it negotiated,
    /// which ``stepFrameEncodings`` reports. A streamed session ignores it.
    ///
    /// - Parameters:
    ///   - codecs: the codecs, most preferred first. `nil` goes back to the
    ///     default. An **empty** list for the whole session turns encoding off,
    ///     so every track carries raw frames; an empty list for one `track` goes
    ///     back to the session's list, since one track cannot turn encoding off
    ///     alone.
    ///   - track: the track the list applies to, in place of the session's, or
    ///     `nil` for every track.
    ///
    /// - Throws: ``ReactorError`` with ``ReactorError/Code/invalidState`` on a
    ///   closed client, or the library's own error when it refuses the
    ///   preference. Nothing changes on a throw.
    public func setCodecPreference(_ codecs: [StepCodec]?, track: String? = nil) throws {
        let codecsJSON = codecs.map { codecs in
            "[" + codecs.map { "\"\($0.rawValue)\"" }.joined(separator: ",") + "]"
        }
        // The library keeps the preference on the handle, which lives from
        // creation to close(), and reads it at every connect and reconnect:
        // nothing has to be kept here and handed over again.
        var failure: String?
        let reached = withHandle(else: false) { handle in
            withOptionalCString(track) { trackPointer in
                withOptionalCString(codecsJSON) { codecsPointer in
                    // Heap-allocated on a refusal only, and ours to free.
                    let answer = ffi.setCodecPreference(handle, trackPointer, codecsPointer)
                    failure = String(takingOwnership: answer, freeing: ffi.freeString)
                }
            }
            return true
        }
        guard reached else {
            throw ReactorError(
                .invalidState,
                "the client is closed, so setCodecPreference cannot run. Create a new Reactor.",
                operation: "set_codec_preference")
        }
        if let failure {
            throw ReactorError.decode(payload: failure)
        }
    }

    /// How each track's step frames travel on this connection, by track name:
    /// `"h264"`, `"vp8"`, `"vp9"` or `"av1"` for a video track the session
    /// encodes, `"raw"` for the rest.
    ///
    /// What the live connection negotiated from ``setCodecPreference(_:track:)``
    /// and the runtime's own codecs. Empty outside a connected stepped session,
    /// and on a closed client. Either way, the frames a step returns are
    /// decoded RGB.
    public var stepFrameEncodings: [String: String] {
        let json = withHandle(else: nil) { handle in
            String(takingOwnership: ffi.stepFrameEncodings(handle), freeing: ffi.freeString)
        }
        guard let json,
            let object = try? JSONSerialization.jsonObject(with: Data(json.utf8))
                as? [String: String]
        else { return [:] }
        return object
    }

    /// Check `media`'s frames, track by track, as ``step(state:commands:media:requestKeyframe:)``
    /// documents, and answer their pushes, which the step runs with the live
    /// handle right before `reactor_step`. Sorted by name, so that what reaches
    /// the buffer — and what its limit drops first — does not depend on a
    /// dictionary's order.
    ///
    /// Every frame is checked before the first is pushed: the buffer can only
    /// be cleared whole, so a frame refused halfway would leave the ones before
    /// it to ride the next step.
    func prepareStepMedia(
        _ media: [String: [StepMediaFrame]]
    ) throws -> [(OpaquePointer) -> Void] {
        var pushes: [(OpaquePointer) -> Void] = []
        for (name, frames) in media.sorted(by: { $0.key < $1.key }) {
            let track = try track(name)
            for frame in frames {
                pushes.append(try track.preparePush(frame))
            }
        }
        return pushes
    }

    /// Drop every frame still buffered for the next
    /// ``step(state:commands:media:requestKeyframe:)``. Frames already sent to the runtime are
    /// not recalled. A no-op on a streamed or closed client.
    public func clearStepInputs() {
        guard generation == .stepped else { return }
        withHandle(else: ()) { handle in ffi.clearStepInputs(handle) }
    }

    // MARK: - Creating a client from options

    /// What `reactor_create_with_options` is handed. Every key is spelled out,
    /// because the library refuses one it does not know rather than ignoring it.
    struct CreateOptions: Encodable {
        var local: Bool
        var autoResumeTracks: Bool
        var generation: String
        var stepTimeoutSeconds: Double?
        var maxMessageSize: UInt64?
        // Named rather than defaulted, for the reason createWithADM passes 0.
        let adm = "synthetic"
        let sdkVersion = ReactorSDK.version
        let sdkType = Reactor.sdkType

        private enum CodingKeys: String, CodingKey {
            case local
            case autoResumeTracks = "auto_resume_tracks"
            case stepTimeoutSeconds = "step_timeout_seconds"
            case maxMessageSize = "max_message_size"
            case adm
            case sdkVersion = "sdk_version"
            case sdkType = "sdk_type"
            case generation
        }
    }

    /// Create a client's handle through `reactor_create_with_options`: for a
    /// stepped session, or for one with a message limit.
    ///
    /// `.success(nil)` is the allocation failure `reactor_create_with_adm` also
    /// reports; `.failure` is the library refusing the options, with its reason.
    static func createWithOptions(
        ffi: FFI,
        apiURL: String,
        model: String,
        jwt: String?,
        callbacks: UnsafePointer<ReactorCallbacks>,
        options: CreateOptions
    ) -> Result<OpaquePointer?, ReactorError> {
        let json: String
        do {
            json = String(decoding: try JSON.encoder().encode(options), as: UTF8.self)
        } catch {
            return .failure(
                ReactorError(
                    .internalError, "could not encode the create options: \(error)",
                    operation: "create"))
        }

        var errorOut: UnsafeMutablePointer<CChar>?
        let handle = json.withCString { optionsPointer in
            ffi.createWithOptions(
                apiURL, model, jwt, callbacks, optionsPointer, &errorOut)
        }
        // Heap-allocated for this caller, and only on a refusal.
        if let payload = String(takingOwnership: errorOut, freeing: ffi.freeString) {
            return handle == nil
                ? .failure(ReactorError.decode(payload: payload)) : .success(handle)
        }
        return .success(handle)
    }

    /// The step timeout in seconds, as the library takes it, after refusing
    /// what it would refuse less helpfully.
    static func validateStepTimeout(
        _ timeout: Duration?, for generation: Generation
    ) throws -> Double? {
        guard let timeout else { return nil }
        guard generation == .stepped else {
            throw ReactorError(
                .badRequest,
                "stepTimeout only applies to generation: .stepped — a streamed session has "
                    + "no steps to time out",
                operation: "create")
        }
        let seconds = timeout.seconds
        guard seconds > 0 else {
            throw ReactorError(
                .badRequest, "stepTimeout must be positive, got \(seconds)s", operation: "create")
        }
        return seconds
    }

    /// The lowest `maxMessageSize`: 256 KiB, what a data channel carries unchunked.
    static let minMaxMessageSize: UInt64 = 256 * 1024
    /// The highest `maxMessageSize`: 1 GiB.
    static let maxMaxMessageSize: UInt64 = 1024 * 1024 * 1024

    /// Refuse a message limit the library would refuse, with the range in the message.
    static func validateMaxMessageSize(_ size: UInt64?) throws {
        guard let size else { return }
        guard (minMaxMessageSize...maxMaxMessageSize).contains(size) else {
            throw ReactorError(
                .badRequest,
                "maxMessageSize must be from \(minMaxMessageSize) (256 KiB) to "
                    + "\(maxMaxMessageSize) (1 GiB) bytes, got \(size)",
                operation: "create")
        }
    }
}

// MARK: - Reading a result

extension StepResult {

    /// Copy a library-owned `ReactorStepResult` into a value. Does not free it.
    init(reading result: OpaquePointer, from ffi: FFI) throws {
        var tracks: [StepTrackOutput] = []
        for track in 0..<ffi.stepResultTrackCount(result) {
            let name = String(borrowing: ffi.stepResultTrackName(result, track)) ?? ""
            let kindText = String(borrowing: ffi.stepResultTrackKind(result, track)) ?? ""
            guard let kind = TrackKind(rawValue: kindText) else {
                throw ReactorError(
                    .decodeFailed,
                    "step output track '\(name)' has kind '\(kindText)', which is neither video "
                        + "nor audio",
                    operation: "step")
            }

            var frames: [StepFrame] = []
            for frame in 0..<ffi.stepResultFrameCount(result, track) {
                var ndim: UInt32 = 0
                let shape =
                    ffi.stepResultFrameShape(result, track, frame, &ndim).map { pointer in
                        UnsafeBufferPointer(start: pointer, count: Int(ndim)).map(Int.init)
                    } ?? []

                // The one copy of the frame's bytes: out of the library's result,
                // which is freed as soon as this returns.
                var length = 0
                let data =
                    ffi.stepResultFrameData(result, track, frame, &length).map {
                        Data(bytes: $0, count: length)
                    } ?? Data()

                var metadataLength = 0
                let metadata = ffi.stepResultFrameMetadata(result, track, frame, &metadataLength)
                    .map { Data(bytes: $0, count: metadataLength) }

                var captured: Int64 = 0
                let hasCaptureTime =
                    ffi.stepResultFrameCaptureTimeUs(result, track, frame, &captured) == 1

                frames.append(
                    try StepFrame(
                        data: data,
                        dtype: String(borrowing: ffi.stepResultFrameDtype(result, track, frame))
                            ?? "",
                        shape: shape,
                        encoding: String(
                            borrowing: ffi.stepResultFrameEncoding(result, track, frame)) ?? "",
                        metadata: metadata,
                        captureTimeUs: hasCaptureTime ? captured : nil))
            }
            tracks.append(
                StepTrackOutput(
                    name: name, kind: kind, rate: ffi.stepResultTrackRate(result, track),
                    frames: frames))
        }
        // `.seconds(_:)` traps on a NaN, an infinity, or a finite value past
        // what a `Duration` holds (about 1.7e20 s), and this runs on the
        // completion thread. A NaN or an infinity measured nothing usable; a
        // finite value that large saturates.
        let seconds = ffi.stepResultElapsed(result)
        let elapsedLimit = 1e18
        let elapsed: Duration =
            seconds.isFinite ? .seconds(min(max(seconds, -elapsedLimit), elapsedLimit)) : .zero

        let files = (0..<ffi.stepResultFileCount(result)).map { file in
            StepFile(
                name: String(borrowing: ffi.stepResultFileName(result, file)) ?? "",
                contentType: String(borrowing: ffi.stepResultFileContentType(result, file)) ?? "",
                size: ffi.stepResultFileSize(result, file))
        }

        var dataLength = 0
        let data = ffi.stepResultData(result, &dataLength).map {
            Data(bytes: $0, count: dataLength)
        }

        self.init(
            step: ffi.stepResultStep(result), tracks: tracks, elapsed: elapsed, files: files,
            error: String(borrowing: ffi.stepResultError(result)), data: data)
    }
}

// MARK: - Completion

/// A step waiting on `reactor_step`'s completion.
///
/// Carries the library table itself rather than reaching it through the
/// client: the result it is handed is owned, and has to be freed through the
/// same table that made it even when the client is already on its way out.
final class PendingStep: PendingCompletion<StepResult>, @unchecked Sendable {

    let ffi: FFI

    init(owner: Reactor, ffi: FFI) {
        self.ffi = ffi
        super.init(operation: "step", owner: owner)
    }

    /// Settle from `reactor_step`'s completion: copy the result out, free it,
    /// then settle — decode first, claim second, as every completion does.
    func complete(ok: Int32, result: OpaquePointer?, errorJSON: UnsafePointer<CChar>?) {
        let outcome: Result<StepResult, any Error>
        if ok == 1, let result {
            outcome = Result { try StepResult(reading: result, from: ffi) }
            // Copied out above; the library's result has no further use.
            ffi.stepResultFree(result)
        } else if ok == 1 {
            outcome = .failure(
                ReactorError(.decodeFailed, "a step succeeded with no result", operation: "step"))
        } else {
            // NULL on failure, the header says; freed anyway if it is not, since
            // it would be ours and nobody else's.
            if let result { ffi.stepResultFree(result) }
            outcome = .failure(ReactorError.decode(payload: String(borrowing: errorJSON)))
        }
        complete(outcome)
    }
}

/// The C function the library calls when a step finishes.
let stepCompletionTrampoline: reactor_step_completion_fn = { ok, result, errorJSON, userdata in
    // Unreachable without userdata — step() always passes one — and without it
    // there is neither a caller to settle nor a table to free the result with.
    guard let userdata else { return }
    let unmanaged = Unmanaged<PendingStep>.fromOpaque(userdata)
    let pending = unmanaged.takeUnretainedValue()
    let ffi = pending.ffi

    // The same claim completionTrampoline makes, for the same reason. Losing it
    // means teardown has already settled the caller — but the result is still
    // this callback's, and nothing else will ever free it.
    guard pending.claimRetainedReference() else {
        if let result { ffi.stepResultFree(result) }
        return
    }
    defer { unmanaged.release() }

    pending.complete(ok: ok, result: result, errorJSON: errorJSON)
}
