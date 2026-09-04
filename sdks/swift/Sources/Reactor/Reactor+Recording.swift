import CReactorFFI
import Foundation

extension Reactor {

    // MARK: - Asking for a recording

    /// Request a clip of the last `duration` of this session.
    ///
    /// The clip is **clamped to the media the model has actually generated**, so
    /// a model running slower than real time answers with less than was asked
    /// for. That is the platform working, not a failure.
    public func requestClip(_ duration: Duration) async throws -> Clip {
        let seconds = duration.seconds
        guard seconds > 0 else {
            throw ReactorError(
                .badRequest,
                "a clip needs a positive duration; \(seconds)s asks for nothing",
                operation: "request_clip")
        }
        let payload = try await perform("request_clip") { handle, completion, userdata in
            self.ffi.requestClip(handle, seconds, completion, userdata)
        }
        return try clip(from: payload, operation: "request_clip")
    }

    /// Request a recording of the whole session.
    public func requestRecording() async throws -> Clip {
        let payload = try await perform("request_recording") { handle, completion, userdata in
            self.ffi.requestRecording(handle, completion, userdata)
        }
        return try clip(from: payload, operation: "request_recording")
    }

    // MARK: - Downloading one

    /// Download a clip's segments into one playable file.
    ///
    /// The HLS rules live in the library and this SDK reimplements none of them:
    /// the init segment rides on the `#EXT-X-MAP` *comment* line, a segment can
    /// be presigned on another host where an `Authorization` header is rejected
    /// rather than ignored, and a 202 means the chunk holding the end of the
    /// window has not closed yet.
    ///
    /// - Parameters:
    ///   - clip: what ``requestClip(_:)`` or ``requestRecording()`` answered.
    ///   - url: the file to create. Opened before the first segment is fetched,
    ///     so an unwritable path fails early.
    ///   - readyTimeout: how long to keep waiting **past the runtime's own
    ///     prediction**. `nil` waits as long as the session can still produce
    ///     the clip, which is the only sane answer for a model generating slower
    ///     than real time — a clip becomes ready because the model keeps
    ///     generating, so once the session is gone a 202 is a 202 forever.
    ///   - progress: called after each segment is written, on the download's own
    ///     thread. Blocking it delays this download and nothing else.
    ///
    /// - Note: this outlives the client. If the client is closed mid-download the
    ///   call fails with a message saying the file may still arrive — because it
    ///   may.
    @discardableResult
    public func download(
        _ clip: Clip,
        to url: URL,
        readyTimeout: Duration? = nil,
        progress: (@Sendable (DownloadProgress) -> Void)? = nil
    ) async throws -> DownloadResult {
        // Negative means "wait as long as the session lives", which is what nil
        // asks for. A NaN is unrepresentable here: `Duration` cannot hold one,
        // so the caller-bug the C header warns about — a NaN crossing as a
        // double, panicking inside a detached task, dropping the completion and
        // leaving the binding waiting forever — cannot be written in this API.
        let timeoutSeconds = readyTimeout.map(\.seconds) ?? -1
        guard timeoutSeconds.isFinite else {
            throw ReactorError(
                .badRequest,
                "readyTimeout does not convert to a finite number of seconds",
                operation: "download_clip")
        }

        let operation = DownloadOperation(outPath: url, owner: self, progress: progress)

        let jwt = self.jwt
        let local = self.isLocal
        let predicted = clip.predictedReadyAtMS ?? 0

        return try await withCheckedThrowingContinuation { continuation in
            // Attached before the operation is reachable from `state`, so a
            // teardown that finds it always has something to settle. The other
            // order left a window where close() abandoned an operation with no
            // continuation yet, and the one attached afterwards was never
            // resumed — the caller's await never returned.
            operation.attach(continuation)

            // Registration and the FFI entry under one barrier, for the reason
            // `perform` does the same: without it, close() could destroy the
            // handle between reading it and calling with it, and this call —
            // whose contract requires a live handle — got a freed one.
            //
            // A download may outlive the client, but starting one on a client
            // that is already closed has nothing to bound it and no handle to
            // read a session from.
            let started = withHandle(else: false) { handle in
                state.withLock { $0.downloads[ObjectIdentifier(operation)] = operation }

                // The ticket, not the operation, is what the library holds: it
                // carries only a weak reference, and the completion frees it.
                let ticket = Unmanaged.passRetained(DownloadTicket(operation: operation))
                    .toOpaque()

                clip.playlistURL.withCString { playlistPointer in
                    withOptionalCString(jwt) { jwtPointer in
                        url.path.withCString { pathPointer in
                            ffi.downloadClip(
                                handle, playlistPointer, jwtPointer, pathPointer, predicted,
                                timeoutSeconds, local ? 1 : 0, downloadProgressTrampoline,
                                downloadCompletionTrampoline, ticket)
                        }
                    }
                }
                return true
            }

            guard !started else { return }
            operation.refuseAsClosed()
        }
    }

    /// Drop a download that has answered.
    func forget(download operation: DownloadOperation) {
        state.withLock { $0.downloads[ObjectIdentifier(operation)] = nil }
    }

    /// Settle every download this client is still holding, because it is closing.
    ///
    /// Called from teardown. Dropping the references without settling is what
    /// leaves a caller awaiting for the life of the process.
    func abandonDownloads() {
        let operations: [DownloadOperation] = state.withLock { state in
            let all = Array(state.downloads.values)
            state.downloads = [:]
            return all
        }
        for operation in operations { operation.abandonForTeardown() }
    }

    private func clip(from payload: String?, operation: String) throws -> Clip {
        guard let payload, let data = payload.data(using: .utf8),
            let value = try? JSONDecoder().decode(JSONValue.self, from: data)
        else {
            throw ReactorError(
                .decodeFailed,
                "the reply to \(operation) is not a recording this SDK can read",
                operation: operation)
        }
        return try Clip(payload: value)
    }
}

extension Duration {

    /// This duration in seconds, as the C ABI wants it.
    ///
    /// `Duration` is (seconds, attoseconds) in `Int64`, so this cannot produce a
    /// NaN and cannot overflow into an infinity — which is exactly why the public
    /// API takes one.
    var seconds: Double {
        let (whole, attoseconds) = components
        return Double(whole) + Double(attoseconds) / 1e18
    }
}
