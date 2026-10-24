import CReactorFFI
import Foundation

extension Reactor {

    // MARK: - Stepped generation

    /// Run one step of a stepped session and return what it produced.
    ///
    /// The runtime applies `state` (each key as the model's `set_<key>`
    /// command), then `commands` in order, then every input frame pushed since
    /// the previous step — ``Track/pushFrame(_:width:height:userData:captureTimeUs:)``
    /// does not send in a stepped session, it buffers for the next step — and
    /// then generates. The runtime checks the whole request before it applies
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
    /// try camera.pushFrame(pixels, width: 640, height: 360)   // buffered, not sent
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
    /// - Parameters:
    ///   - state: the model's state fields to set before the commands run, or
    ///     `nil` to leave the state as it is. The model keeps it until changed.
    ///   - commands: commands to run after the state, in order.
    ///   - media: frames for this step, by input track name, each track's in
    ///     order. Tracks are pushed in the order of their names.
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
    ///   ``ReactorError/Code/badRequest``) keeps the frames pushed for it for
    ///   the next step. Past that they belong to the step whatever its outcome:
    ///   one that could not be sent drops them too, and nothing is resent.
    public func step(
        state: [String: JSONValue]? = nil,
        commands: [StepCommand] = [],
        media: [String: [StepMediaFrame]] = [:]
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
        // Pushed only once the state and commands are known to be well formed, so
        // a step refused for those leaves the buffer as the caller left it.
        try pushStepMedia(media)

        return try await perform(
            PendingStep(owner: self, ffi: ffi), completion: stepCompletionTrampoline
        ) { handle, completion, userdata in
            withOptionalCString(stateJSON) { statePointer in
                commandsJSON.withCString { commandsPointer in
                    self.ffi.step(handle, statePointer, commandsPointer, completion, userdata)
                }
            }
        }
    }

    /// Push `media`'s frames, track by track, as ``step(state:commands:media:)``
    /// documents. Sorted by name, so that what reaches the buffer — and what its
    /// limit drops first — does not depend on a dictionary's order.
    ///
    /// Every frame is checked before the first is pushed: the buffer can only
    /// be cleared whole, so a frame refused halfway would leave the ones before
    /// it to ride the next step.
    func pushStepMedia(_ media: [String: [StepMediaFrame]]) throws {
        var pushes: [() -> Void] = []
        for (name, frames) in media.sorted(by: { $0.key < $1.key }) {
            let track = try track(name)
            for frame in frames {
                pushes.append(try track.preparePush(frame))
            }
        }
        for push in pushes {
            push()
        }
    }

    /// Drop every frame pushed since the last step, so the next
    /// ``step(state:commands:media:)`` carries only what is pushed after this. A
    /// no-op on a streamed or closed client.
    public func clearStepInputs() {
        guard generation == .stepped else { return }
        withHandle(else: ()) { handle in ffi.clearStepInputs(handle) }
    }

    // MARK: - Creating a stepped client

    /// What `reactor_create_with_options` is handed. Every key is spelled out,
    /// because the library refuses one it does not know rather than ignoring it.
    struct CreateOptions: Encodable {
        var local: Bool
        var autoResumeTracks: Bool
        var stepTimeoutSeconds: Double?
        // Named rather than defaulted, for the reason createWithADM passes 0.
        let adm = "synthetic"
        let sdkVersion = ReactorSDK.version
        let sdkType = Reactor.sdkType
        let generation = Generation.stepped.rawValue

        private enum CodingKeys: String, CodingKey {
            case local
            case autoResumeTracks = "auto_resume_tracks"
            case stepTimeoutSeconds = "step_timeout_seconds"
            case adm
            case sdkVersion = "sdk_version"
            case sdkType = "sdk_type"
            case generation
        }
    }

    /// Create a stepped client's handle.
    ///
    /// `.success(nil)` is the allocation failure `reactor_create_with_adm` also
    /// reports; `.failure` is the library refusing the options, with its reason.
    static func createStepped(
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

        self.init(
            step: ffi.stepResultStep(result), tracks: tracks, elapsed: elapsed, files: files,
            error: String(borrowing: ffi.stepResultError(result)))
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
