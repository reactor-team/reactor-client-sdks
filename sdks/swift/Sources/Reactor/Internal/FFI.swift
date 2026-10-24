import CReactorFFI

/// Every call into `libreactor_ffi` goes through this table.
///
/// Two things it buys.
///
/// **The canonical header, never a copy.** The symbols come from
/// `crates/reactor-ffi/include/reactor_ffi.h` through the module map, so the
/// compiler is the parity check for types and the linker is the parity check for
/// names. The Python binding hand-wrote its declarations and became a third
/// place the ABI could drift; there is no equivalent here to drift.
///
/// **A table, so tests can lie to it.** One indirect call per operation, and in
/// exchange a unit test can hand the SDK a fake library — which is the only way
/// to cover teardown and the refuse-do-not-fail-quietly table without a live
/// session, a network and a GPU.
///
/// The members are Swift closures rather than `@convention(c)` function
/// pointers, deliberately: a C function pointer cannot capture, and a fake that
/// cannot capture cannot record what the SDK asked it for.
///
/// ## The table grows with the stack
///
/// It holds the symbols the SDK actually calls today, and each pull request adds
/// the ones it needs. A table listing all 29 exports up front would be 27 lines
/// of code nothing calls, and `check-abi-parity.py` would have nothing to say
/// about them either way — a binding is free to bind a subset.
///
/// `reactor_create` will never be among them. It takes its audio device mode
/// from an environment variable, and a library whose audience is apps and
/// scripts must never let an env var put a live microphone on the wire because a
/// model happened to declare a sendonly audio track. The SDK uses
/// `reactor_create_with_adm` with mode 0 (synthetic) and cannot do otherwise,
/// because the other function is absent from this table —
/// `scripts/check-abi-parity.py` is what keeps it absent. A stepped client is
/// created through `reactor_create_with_options` instead, whose options name
/// `"adm": "synthetic"` for the same reason.
struct FFI: Sendable {

    /// `reactor_abi_version` — the ABI the loaded library speaks.
    var abiVersion: @Sendable () -> UInt32

    /// `reactor_free_string` — releases a string the FFI heap-allocated.
    ///
    /// Only for the strings the header says the caller owns. Passing it the
    /// static string `reactor_status` returns corrupts the heap, and passing it
    /// a borrowed callback string is a double free. ``Swift/String/init(takingOwnership:freeing:)``
    /// is the only place the SDK calls this, so the distinction is made once.
    var freeString: @Sendable (UnsafeMutablePointer<CChar>?) -> Void

    /// `reactor_fetch_jwt` — exchange an API key for a token.
    ///
    /// Takes **no handle**, so nothing bounds its completion: the context must
    /// live until the completion fires and be released from inside it.
    var fetchJWT:
        @Sendable (
            _ apiURL: UnsafePointer<CChar>?, _ apiKey: UnsafePointer<CChar>?,
            _ optionsJSON: UnsafePointer<CChar>?, _ local: Int32,
            _ completion: reactor_completion_fn?, _ userdata: UnsafeMutableRawPointer?
        ) -> Void

    /// `reactor_create_with_adm` — a client, with the audio device module named
    /// explicitly.
    ///
    /// Mode 0 is synthetic, and the SDK passes nothing else. `reactor_create`
    /// takes the mode from an environment variable instead, which is how a model
    /// declaring a sendonly audio track would end up putting a live microphone on
    /// the wire without anyone asking.
    ///
    /// `autoResumeTracks` — non-zero resumes every recvonly track on connect and
    /// reconnect; zero leaves recvonly tracks paused until the host resumes them
    /// itself.
    ///
    /// `sdkVersion` and `sdkType` are reported to the coordinator as
    /// `client_info`. Every binding shares this entry point, which would
    /// otherwise report `ffi` for all of them — Python sends `python`, so this
    /// sends `swift`.
    var createWithADM:
        @Sendable (
            _ apiURL: UnsafePointer<CChar>?,
            _ model: UnsafePointer<CChar>?,
            _ jwt: UnsafePointer<CChar>?,
            _ local: Int32,
            _ autoResumeTracks: Int32,
            _ callbacks: UnsafePointer<ReactorCallbacks>?,
            _ admMode: Int32,
            _ sdkVersion: UnsafePointer<CChar>?,
            _ sdkType: UnsafePointer<CChar>?
        ) -> OpaquePointer?

    /// `reactor_create_with_options` — a client from a JSON object of options.
    ///
    /// Only a stepped client is created through here: the generation mode is a
    /// creation option this is the only entry for. The options name `"adm":
    /// "synthetic"` explicitly, for the reason ``createWithADM`` passes mode 0.
    ///
    /// Null when the options are refused, with a heap JSON error object stored
    /// through `errorOut` for the caller to free.
    var createWithOptions:
        @Sendable (
            _ apiURL: UnsafePointer<CChar>?,
            _ model: UnsafePointer<CChar>?,
            _ jwt: UnsafePointer<CChar>?,
            _ callbacks: UnsafePointer<ReactorCallbacks>?,
            _ optionsJSON: UnsafePointer<CChar>?,
            _ errorOut: UnsafeMutablePointer<UnsafeMutablePointer<CChar>?>?
        ) -> OpaquePointer?

    /// `reactor_destroy` — 0 when no callback is running and none will start,
    /// `-1` when one could not be waited for.
    var destroy: @Sendable (OpaquePointer?) -> Int32

    /// `reactor_connect` — creates or adopts a session.
    var connect:
        @Sendable (
            OpaquePointer?, UnsafePointer<CChar>?, UnsafePointer<UInt32>?,
            reactor_completion_fn?, UnsafeMutableRawPointer?
        ) -> Void

    /// `reactor_disconnect` — ends the session server-side. Not recoverable.
    var disconnect:
        @Sendable (OpaquePointer?, reactor_completion_fn?, UnsafeMutableRawPointer?) -> Void

    /// `reactor_reconnect` — cycles the connection, keeping the session.
    var reconnect:
        @Sendable (OpaquePointer?, reactor_completion_fn?, UnsafeMutableRawPointer?) -> Void

    /// `reactor_status` — a **static** string. Never freed.
    var status: @Sendable (OpaquePointer?) -> UnsafePointer<CChar>?

    /// `reactor_session_id` — heap-allocated, or null when there is no session.
    /// The caller frees it.
    var sessionID: @Sendable (OpaquePointer?) -> UnsafeMutablePointer<CChar>?

    /// `reactor_tracks` — the declared tracks as a JSON array, in declaration
    /// order. `"[]"` before the session is accepted and after teardown, which is
    /// what lets a caller tell "no tracks yet" from an unknown name.
    /// Heap-allocated; the caller frees it.
    var tracks: @Sendable (OpaquePointer?) -> UnsafeMutablePointer<CChar>?

    /// `reactor_paused_tracks` — the paused names as a JSON array, sorted.
    /// Heap-allocated; the caller frees it.
    var pausedTracks: @Sendable (OpaquePointer?) -> UnsafeMutablePointer<CChar>?

    /// `reactor_publish_track` — activates the send slot. Attaching media is
    /// separate, and pushing before this completes drops the frame.
    var publishTrack:
        @Sendable (
            OpaquePointer?, UnsafePointer<CChar>?, reactor_completion_fn?,
            UnsafeMutableRawPointer?
        ) -> Void

    /// `reactor_unpublish_track` — synchronous, because it is a local status
    /// change plus a fire-and-forget notification rather than a round trip.
    ///
    /// Null on success. On failure, a heap JSON error object the caller frees.
    var unpublishTrack:
        @Sendable (OpaquePointer?, UnsafePointer<CChar>?) -> UnsafeMutablePointer<CChar>?

    /// `reactor_pause_track` — deactivates a track's transceiver.
    var pauseTrack:
        @Sendable (
            OpaquePointer?, UnsafePointer<CChar>?, reactor_completion_fn?,
            UnsafeMutableRawPointer?
        ) -> Void

    /// `reactor_resume_track` — re-activates it.
    var resumeTrack:
        @Sendable (
            OpaquePointer?, UnsafePointer<CChar>?, reactor_completion_fn?,
            UnsafeMutableRawPointer?
        ) -> Void

    /// `reactor_push_video_frame_timed` — BGRA pixels, optionally tagged,
    /// stamped with the caller's own capture time when it gave one, and the
    /// milliseconds this SDK already spent on the frame, which count toward
    /// its submit stage.
    ///
    /// The one push the SDK calls. A null tag with length 0 sends the frame
    /// untagged. A `nil` capture time reaches the library as no capture time
    /// at all, and the library stamps the frame as it is pushed: there is no
    /// value of `captureTimeUs` that means "none", since 0 is a time like any
    /// other, and a stepped session sends it to the model as the frame's
    /// capture time.
    var pushVideoFrame:
        @Sendable (
            _ handle: OpaquePointer?, _ track: UnsafePointer<CChar>?,
            _ pixels: UnsafePointer<UInt8>?, _ width: UInt32, _ height: UInt32,
            _ userData: UnsafePointer<UInt8>?, _ userDataLen: UInt32,
            _ captureTimeUs: Int64?, _ bindingMs: Double, _ generation: UInt64
        ) -> Void

    /// `reactor_push_begin` — the connection a frame push belongs to, read as
    /// the app hands the frame over and passed to ``pushVideoFrame``.
    var pushBegin: @Sendable (_ handle: OpaquePointer?) -> UInt64

    /// `reactor_frame_delivered` — marks, from inside the frame callback, the
    /// moment a video frame is handed to the app's handlers, which ends its
    /// delivery stage.
    var frameDelivered: @Sendable () -> Void

    /// `reactor_push_audio_frame` — interleaved i16 PCM.
    var pushAudioFrame:
        @Sendable (
            OpaquePointer?, UnsafePointer<CChar>?, UnsafePointer<Int16>?, UInt32, UInt32, UInt32
        ) -> Void

    /// `reactor_time_micros` — the engine's monotonic clock, which is the epoch
    /// a capture time is read in. Not the UNIX epoch, and it takes no handle.
    var timeMicros: @Sendable () -> Int64

    /// `reactor_send_command` — an application command over the data channel,
    /// with its correlated reply.
    var sendCommand:
        @Sendable (
            _ handle: OpaquePointer?, _ name: UnsafePointer<CChar>?,
            _ argsJSON: UnsafePointer<CChar>?, _ uploadsJSON: UnsafePointer<CChar>?,
            _ completion: reactor_completion_fn?, _ userdata: UnsafeMutableRawPointer?
        ) -> Void

    /// `reactor_request_schema` — the model's command schema, as an OpenAPI
    /// document.
    var requestSchema:
        @Sendable (OpaquePointer?, reactor_completion_fn?, UnsafeMutableRawPointer?) -> Void

    /// `reactor_get_stats` — a statistics snapshot for the live connection.
    ///
    /// Asynchronous because the engine collects the report on its own thread and
    /// waits for it, which can take seconds in the pathological case.
    var getStats:
        @Sendable (OpaquePointer?, reactor_completion_fn?, UnsafeMutableRawPointer?) -> Void

    /// `reactor_upload_file` — upload from a path on disk.
    var uploadFile:
        @Sendable (
            OpaquePointer?, UnsafePointer<CChar>?, reactor_completion_fn?,
            UnsafeMutableRawPointer?
        ) -> Void

    /// `reactor_upload_bytes` — upload from memory, for a caller who has the
    /// bytes rather than a path.
    var uploadBytes:
        @Sendable (
            _ handle: OpaquePointer?, _ data: UnsafePointer<UInt8>?, _ length: Int,
            _ name: UnsafePointer<CChar>?, _ mimeType: UnsafePointer<CChar>?,
            _ completion: reactor_completion_fn?, _ userdata: UnsafeMutableRawPointer?
        ) -> Void

    /// `reactor_request_clip` — a clip of the last `duration_seconds`.
    var requestClip:
        @Sendable (OpaquePointer?, Double, reactor_completion_fn?, UnsafeMutableRawPointer?) -> Void

    /// `reactor_request_recording` — a full-session recording.
    var requestRecording:
        @Sendable (OpaquePointer?, reactor_completion_fn?, UnsafeMutableRawPointer?) -> Void

    /// `reactor_download_clip` — fetch and assemble a clip's HLS segments.
    ///
    /// **Not bounded by `reactor_destroy`.** The header says so plainly: a
    /// download outlives the handle it was given one of, so its completion can
    /// arrive after the client is gone. Everything about how this is called
    /// follows from that.
    var downloadClip:
        @Sendable (
            _ handle: OpaquePointer?, _ playlistURL: UnsafePointer<CChar>?,
            _ jwt: UnsafePointer<CChar>?, _ outPath: UnsafePointer<CChar>?,
            _ predictedReadyAtMS: Double, _ readyTimeoutSeconds: Double, _ local: Int32,
            _ progress: reactor_progress_fn?, _ completion: reactor_completion_fn?,
            _ userdata: UnsafeMutableRawPointer?
        ) -> Void

    // MARK: Stepped generation

    /// `reactor_step` — run one step of a stepped session.
    ///
    /// Its completion hands over an **owned** `ReactorStepResult`, which the
    /// accessors below read in place and ``stepResultFree`` releases. Bounded
    /// by `reactor_destroy` like every other completion.
    var step:
        @Sendable (
            _ handle: OpaquePointer?, _ stateJSON: UnsafePointer<CChar>?,
            _ commandsJSON: UnsafePointer<CChar>?, _ completion: reactor_step_completion_fn?,
            _ userdata: UnsafeMutableRawPointer?
        ) -> Void

    /// `reactor_clear_step_inputs` — drop every frame buffered for the next step.
    var clearStepInputs: @Sendable (OpaquePointer?) -> Void

    /// `reactor_push_step_encoded_video_frame` — buffer one encoded video frame
    /// for the next step, its bytes as they are.
    ///
    /// `encoding` is a `REACTOR_FRAME_ENCODING_*` value, and `width` and
    /// `height` the frame it decodes to. A null tag with length 0 leaves the
    /// frame untagged, and a `nil` capture time reaches the library as none,
    /// as for ``pushVideoFrame``.
    var pushStepEncodedVideoFrame:
        @Sendable (
            _ handle: OpaquePointer?, _ track: UnsafePointer<CChar>?, _ encoding: Int32,
            _ data: UnsafePointer<UInt8>?, _ length: Int, _ width: UInt32, _ height: UInt32,
            _ userData: UnsafePointer<UInt8>?, _ userDataLen: UInt32, _ captureTimeUs: Int64?
        ) -> Void

    /// `reactor_step_result_step` — the step's number in the session, from 1.
    var stepResultStep: @Sendable (OpaquePointer?) -> UInt64

    /// `reactor_step_result_elapsed` — the seconds the step's `generate()` took.
    var stepResultElapsed: @Sendable (OpaquePointer?) -> Double

    /// `reactor_step_result_error` — why a step that ran failed, borrowed from
    /// the result; null when it worked.
    var stepResultError: @Sendable (OpaquePointer?) -> UnsafePointer<CChar>?

    /// `reactor_step_result_file_count` — the extra files the model kept.
    var stepResultFileCount: @Sendable (OpaquePointer?) -> UInt32

    /// `reactor_step_result_file_name` — borrowed from the result.
    var stepResultFileName: @Sendable (OpaquePointer?, UInt32) -> UnsafePointer<CChar>?

    /// `reactor_step_result_file_content_type` — borrowed from the result.
    var stepResultFileContentType: @Sendable (OpaquePointer?, UInt32) -> UnsafePointer<CChar>?

    /// `reactor_step_result_file_size` — bytes; 0 when out of range.
    var stepResultFileSize: @Sendable (OpaquePointer?, UInt32) -> UInt64

    /// `reactor_step_result_track_count`.
    var stepResultTrackCount: @Sendable (OpaquePointer?) -> UInt32

    /// `reactor_step_result_track_name` — borrowed from the result.
    var stepResultTrackName: @Sendable (OpaquePointer?, UInt32) -> UnsafePointer<CChar>?

    /// `reactor_step_result_track_kind` — `"video"` or `"audio"`, static.
    var stepResultTrackKind: @Sendable (OpaquePointer?, UInt32) -> UnsafePointer<CChar>?

    /// `reactor_step_result_track_rate` — frames per second for video, samples
    /// per second for audio; 0 when unspecified or out of range.
    var stepResultTrackRate: @Sendable (OpaquePointer?, UInt32) -> Double

    /// `reactor_step_result_frame_count`.
    var stepResultFrameCount: @Sendable (OpaquePointer?, UInt32) -> UInt32

    /// `reactor_step_result_frame_encoding` — static.
    var stepResultFrameEncoding: @Sendable (OpaquePointer?, UInt32, UInt32) -> UnsafePointer<CChar>?

    /// `reactor_step_result_frame_dtype` — NumPy notation, borrowed.
    var stepResultFrameDtype: @Sendable (OpaquePointer?, UInt32, UInt32) -> UnsafePointer<CChar>?

    /// `reactor_step_result_frame_shape` — `*ndim` dimensions, borrowed.
    var stepResultFrameShape:
        @Sendable (OpaquePointer?, UInt32, UInt32, UnsafeMutablePointer<UInt32>?) ->
            UnsafePointer<UInt32>?

    /// `reactor_step_result_frame_data` — `*len` bytes, borrowed.
    var stepResultFrameData:
        @Sendable (OpaquePointer?, UInt32, UInt32, UnsafeMutablePointer<Int>?) ->
            UnsafePointer<UInt8>?

    /// `reactor_step_result_frame_metadata` — `*len` bytes, borrowed; null when
    /// the sender attached nothing, non-null with length 0 for an empty tag.
    var stepResultFrameMetadata:
        @Sendable (OpaquePointer?, UInt32, UInt32, UnsafeMutablePointer<Int>?) ->
            UnsafePointer<UInt8>?

    /// `reactor_step_result_frame_capture_time_us` — 1 and the time written, or 0.
    var stepResultFrameCaptureTimeUs:
        @Sendable (OpaquePointer?, UInt32, UInt32, UnsafeMutablePointer<Int64>?) -> Int32

    /// `reactor_step_result_free` — release a result and everything read from it.
    var stepResultFree: @Sendable (OpaquePointer?) -> Void
}

extension FFI {

    /// The real library.
    ///
    /// Every member is named on both sides. Swift's memberwise initialiser
    /// requires the labels, which rules out the failure the C++ SDK needed a
    /// macro to prevent: two symbols with identical signatures silently swapped,
    /// so the binding pauses a track when asked to publish it.
    static let system = FFI(
        abiVersion: { reactor_abi_version() },
        freeString: { pointer in reactor_free_string(pointer) },
        fetchJWT: { apiURL, apiKey, optionsJSON, local, completion, userdata in
            reactor_fetch_jwt(apiURL, apiKey, optionsJSON, local, completion, userdata)
        },
        createWithADM: {
            apiURL, model, jwt, local, autoResumeTracks, callbacks, admMode, sdkVersion, sdkType in
            reactor_create_with_adm(
                apiURL, model, jwt, local, autoResumeTracks, callbacks, admMode, sdkVersion,
                sdkType)
        },
        createWithOptions: {
            apiURL, model, jwt, callbacks, optionsJSON, errorOut in
            reactor_create_with_options(apiURL, model, jwt, callbacks, optionsJSON, errorOut)
        },
        destroy: { handle in reactor_destroy(handle) },
        connect: { handle, sessionID, connectionID, completion, userdata in
            reactor_connect(handle, sessionID, connectionID, completion, userdata)
        },
        disconnect: { handle, completion, userdata in
            reactor_disconnect(handle, completion, userdata)
        },
        reconnect: { handle, completion, userdata in
            reactor_reconnect(handle, completion, userdata)
        },
        status: { handle in reactor_status(handle) },
        sessionID: { handle in reactor_session_id(handle) },
        tracks: { handle in reactor_tracks(handle) },
        pausedTracks: { handle in reactor_paused_tracks(handle) },
        publishTrack: { handle, name, completion, userdata in
            reactor_publish_track(handle, name, completion, userdata)
        },
        unpublishTrack: { handle, name in reactor_unpublish_track(handle, name) },
        pauseTrack: { handle, name, completion, userdata in
            reactor_pause_track(handle, name, completion, userdata)
        },
        resumeTrack: { handle, name, completion, userdata in
            reactor_resume_track(handle, name, completion, userdata)
        },
        pushVideoFrame: {
            handle, track, pixels, width, height, userData, userDataLen, captureAt, bindingMs,
            generation in
            reactor_push_video_frame_timed(
                handle, track, pixels, width, height, userData, userDataLen,
                captureAt == nil ? 0 : 1, captureAt ?? 0, bindingMs, generation)
        },
        pushBegin: { handle in reactor_push_begin(handle) },
        frameDelivered: { reactor_frame_delivered() },
        pushAudioFrame: { handle, track, samples, samplesPerChannel, sampleRate, channels in
            reactor_push_audio_frame(
                handle, track, samples, samplesPerChannel, sampleRate, channels)
        },
        timeMicros: { reactor_time_micros() },
        sendCommand: { handle, name, argsJSON, uploadsJSON, completion, userdata in
            reactor_send_command(handle, name, argsJSON, uploadsJSON, completion, userdata)
        },
        requestSchema: { handle, completion, userdata in
            reactor_request_schema(handle, completion, userdata)
        },
        getStats: { handle, completion, userdata in
            reactor_get_stats(handle, completion, userdata)
        },
        uploadFile: { handle, path, completion, userdata in
            reactor_upload_file(handle, path, completion, userdata)
        },
        uploadBytes: { handle, data, length, name, mimeType, completion, userdata in
            reactor_upload_bytes(handle, data, length, name, mimeType, completion, userdata)
        },
        requestClip: { handle, duration, completion, userdata in
            reactor_request_clip(handle, duration, completion, userdata)
        },
        requestRecording: { handle, completion, userdata in
            reactor_request_recording(handle, completion, userdata)
        },
        downloadClip: {
            handle, playlistURL, jwt, outPath, predicted, timeout, local, progress, completion,
            userdata in
            reactor_download_clip(
                handle, playlistURL, jwt, outPath, predicted, timeout, local, progress, completion,
                userdata)
        },
        step: { handle, stateJSON, commandsJSON, completion, userdata in
            reactor_step(handle, stateJSON, commandsJSON, completion, userdata)
        },
        clearStepInputs: { handle in reactor_clear_step_inputs(handle) },
        pushStepEncodedVideoFrame: {
            handle, track, encoding, data, length, width, height, userData, userDataLen,
            captureAt in
            reactor_push_step_encoded_video_frame(
                handle, track, encoding, data, length, width, height, userData, userDataLen,
                captureAt == nil ? 0 : 1, captureAt ?? 0)
        },
        stepResultStep: { result in reactor_step_result_step(result) },
        stepResultElapsed: { result in reactor_step_result_elapsed(result) },
        stepResultError: { result in reactor_step_result_error(result) },
        stepResultFileCount: { result in reactor_step_result_file_count(result) },
        stepResultFileName: { result, file in reactor_step_result_file_name(result, file) },
        stepResultFileContentType: { result, file in
            reactor_step_result_file_content_type(result, file)
        },
        stepResultFileSize: { result, file in reactor_step_result_file_size(result, file) },
        stepResultTrackCount: { result in reactor_step_result_track_count(result) },
        stepResultTrackName: { result, track in reactor_step_result_track_name(result, track) },
        stepResultTrackKind: { result, track in reactor_step_result_track_kind(result, track) },
        stepResultTrackRate: { result, track in reactor_step_result_track_rate(result, track) },
        stepResultFrameCount: { result, track in reactor_step_result_frame_count(result, track) },
        stepResultFrameEncoding: { result, track, frame in
            reactor_step_result_frame_encoding(result, track, frame)
        },
        stepResultFrameDtype: { result, track, frame in
            reactor_step_result_frame_dtype(result, track, frame)
        },
        stepResultFrameShape: { result, track, frame, ndim in
            reactor_step_result_frame_shape(result, track, frame, ndim)
        },
        stepResultFrameData: { result, track, frame, length in
            reactor_step_result_frame_data(result, track, frame, length)
        },
        stepResultFrameMetadata: { result, track, frame, length in
            reactor_step_result_frame_metadata(result, track, frame, length)
        },
        stepResultFrameCaptureTimeUs: { result, track, frame, captureTimeUs in
            reactor_step_result_frame_capture_time_us(result, track, frame, captureTimeUs)
        },
        stepResultFree: { result in reactor_step_result_free(result) }
    )
}
