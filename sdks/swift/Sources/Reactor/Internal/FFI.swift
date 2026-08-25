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
/// `scripts/check-abi-parity.py` is what keeps it absent.
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
            _ callbacks: UnsafePointer<ReactorCallbacks>?,
            _ admMode: Int32,
            _ sdkVersion: UnsafePointer<CChar>?,
            _ sdkType: UnsafePointer<CChar>?
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

    /// `reactor_push_video_frame_with_metadata_at` — BGRA pixels, optionally
    /// tagged, optionally stamped with the caller's own capture time.
    ///
    /// The one push the SDK calls: the plain and tagged variants are this one
    /// with nothing to tag and nothing to stamp, so there is a single path to
    /// get wrong rather than three.
    var pushVideoFrame:
        @Sendable (
            _ handle: OpaquePointer?, _ track: UnsafePointer<CChar>?,
            _ pixels: UnsafePointer<UInt8>?, _ width: UInt32, _ height: UInt32,
            _ userData: UnsafePointer<UInt8>?, _ userDataLen: UInt32,
            _ captureTimeUs: Int64
        ) -> Void

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
        createWithADM: { apiURL, model, jwt, local, callbacks, admMode, sdkVersion, sdkType in
            reactor_create_with_adm(
                apiURL, model, jwt, local, callbacks, admMode, sdkVersion, sdkType)
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
        pushVideoFrame: { handle, track, pixels, width, height, userData, userDataLen, captureAt in
            reactor_push_video_frame_with_metadata_at(
                handle, track, pixels, width, height, userData, userDataLen, captureAt)
        },
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
        }
    )
}
