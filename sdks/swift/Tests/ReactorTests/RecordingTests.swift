import Foundation
import Testing

@testable import Reactor

@Suite("Recording")
struct RecordingTests {

    private func makeClient(
        fake: FakeLibrary, jwt: String? = "token", local: Bool = false
    ) throws
        -> Reactor
    {
        try Reactor(
            model: "reactor/helios",
            jwt: jwt,
            apiURL: Reactor.defaultAPIURL,
            local: local,
            eventQueue: nil,
            ffi: fake.table)
    }

    private let clipPayload = #"""
        {"playlist_url":"https://api.reactor.inc/clips/abc.m3u8","session_id":"s_1",
         "kind":"clip","predicted_ready_at_ms":1712345678000}
        """#

    private func answering<T: Sendable>(
        _ fake: FakeLibrary,
        result: String?,
        _ work: @escaping @Sendable () async throws -> T
    ) async throws -> T {
        let task = Task { try await work() }
        _ = await waitUntil { fake.hasPendingCompletion }
        fake.completeLastCall(ok: true, result: result)
        return try await task.value
    }

    // MARK: - Asking

    @Test("a clip request carries its duration and comes back parsed")
    func clipRequest() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let clip = try await answering(fake, result: clipPayload) {
            try await client.requestClip(.seconds(10))
        }

        #expect(fake.clipCalls == [10])
        #expect(clip.playlistURL == "https://api.reactor.inc/clips/abc.m3u8")
        #expect(clip.sessionID == "s_1")
        #expect(clip.predictedReadyAtMS == 1_712_345_678_000)
    }

    @Test("a recording request reaches its own call")
    func recordingRequest() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        _ = try await answering(fake, result: clipPayload) {
            try await client.requestRecording()
        }

        #expect(fake.recordingCalls == 1)
        #expect(fake.clipCalls.isEmpty)
    }

    @Test("a zero or negative duration is refused before the call")
    func nonPositiveDurationIsRefused() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        await #expect(throws: ReactorError.self) { try await client.requestClip(.zero) }
        await #expect(throws: ReactorError.self) { try await client.requestClip(.seconds(-5)) }
        #expect(fake.clipCalls.isEmpty)
    }

    @Test("a reply with no playlist is a decode failure, not an empty clip")
    func replyWithoutPlaylistIsRefused() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        await #expect(throws: ReactorError.self) {
            try await answering(fake, result: #"{"session_id":"s_1"}"#) {
                try await client.requestClip(.seconds(5))
            }
        }
    }

    // MARK: - Downloading

    @Test("a download passes the playlist, the token and the prediction")
    func downloadCarriesEverything() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let clip = try await answering(fake, result: clipPayload) {
            try await client.requestClip(.seconds(10))
        }

        let destination = URL(fileURLWithPath: "/tmp/clip.mp4")
        let downloading = Task { try await client.download(clip, to: destination) }
        #expect(await waitUntil { fake.hasPendingDownload }, "the download never started")

        let call = try #require(fake.downloadCalls.first)
        #expect(call.playlistURL == clip.playlistURL)
        // The coordinator-hosted playlist needs the bearer token; the library
        // decides which requests may carry it, because a presigned segment on
        // another host *rejects* one rather than ignoring it.
        #expect(call.jwt == "token")
        #expect(call.outPath == "/tmp/clip.mp4")
        #expect(call.predictedReadyAtMS == 1_712_345_678_000)
        // nil means "as long as the session can still produce it".
        #expect(call.readyTimeoutSeconds == -1)

        fake.completeDownload(
            ok: true, result: #"{"path":"/tmp/clip.mp4","bytes":1024,"segments":4}"#)
        let result = try await downloading.value

        #expect(result.path.path == "/tmp/clip.mp4")
        #expect(result.bytes == 1024)
        #expect(result.segments == 4)
    }

    @Test("a ready timeout is passed as seconds past the prediction")
    func readyTimeoutIsPassedThrough() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake, jwt: nil, local: true)
        defer { client.close() }

        let clip = try await answering(fake, result: clipPayload) {
            try await client.requestClip(.seconds(10))
        }

        let downloading = Task {
            try await client.download(
                clip, to: URL(fileURLWithPath: "/tmp/c.mp4"), readyTimeout: .seconds(30))
        }
        #expect(await waitUntil { fake.hasPendingDownload }, "the download never started")

        let call = try #require(fake.downloadCalls.first)
        #expect(call.readyTimeoutSeconds == 30)
        #expect(call.jwt == nil)
        #expect(call.local == 1)

        fake.completeDownload(ok: true, result: #"{"path":"/tmp/c.mp4","bytes":1,"segments":1}"#)
        _ = try await downloading.value
    }

    @Test("progress arrives as segments are written")
    func progressIsReported() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let clip = try await answering(fake, result: clipPayload) {
            try await client.requestClip(.seconds(10))
        }

        let seen = Locked<[DownloadProgress]>([])
        let downloading = Task {
            try await client.download(clip, to: URL(fileURLWithPath: "/tmp/c.mp4")) { progress in
                seen.withLock { $0.append(progress) }
            }
        }
        #expect(await waitUntil { fake.hasPendingDownload }, "the download never started")

        fake.reportDownloadProgress(done: 1, total: 4)
        fake.reportDownloadProgress(done: 4, total: 4)
        fake.completeDownload(ok: true, result: #"{"path":"/tmp/c.mp4","bytes":9,"segments":4}"#)
        _ = try await downloading.value

        let progress = seen.withLock { $0 }
        #expect(progress.count == 2)
        #expect(progress.first?.done == 1)
        #expect(progress.last?.fraction == 1)
    }

    @Test("a failed download throws the typed error the library reported")
    func failedDownloadThrows() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let clip = try await answering(fake, result: clipPayload) {
            try await client.requestClip(.seconds(10))
        }

        let downloading = Task {
            try await client.download(clip, to: URL(fileURLWithPath: "/tmp/c.mp4"))
        }
        #expect(await waitUntil { fake.hasPendingDownload }, "the download never started")
        fake.completeDownload(
            ok: false, error: #"{"code":"NOT_FOUND","message":"no such clip"}"#)

        await #expect(throws: ReactorError.self) { _ = try await downloading.value }
    }

    // MARK: - The download that outlives its client

    @Test("closing mid-download settles the caller, and says the file may still arrive")
    func closingMidDownloadSettlesTheCaller() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)

        let clip = try await answering(fake, result: clipPayload) {
            try await client.requestClip(.seconds(10))
        }

        // Watched through a flag rather than awaited, because the regression this
        // guards against is a caller that is never settled at all — awaiting it
        // would hang the suite instead of failing this test.
        let settled = Locked(false)
        let outcome = Locked<(any Error)?>(nil)
        Task {
            do {
                _ = try await client.download(clip, to: URL(fileURLWithPath: "/tmp/c.mp4"))
            } catch {
                outcome.withLock { $0 = error }
            }
            settled.withLock { $0 = true }
        }

        #expect(await waitUntil { fake.hasPendingDownload }, "the download never started")

        // The library was told this download outlives the handle, so its
        // completion may never come. Leaving it out of teardown is a caller
        // awaiting for the life of the process; freeing the operation instead is
        // a use-after-free when the callback does arrive.
        client.close()

        #expect(
            await waitUntil { settled.withLock { $0 } },
            "closing must settle a download the library will not bound")

        let error = outcome.withLock { $0 } as? ReactorError
        #expect(error?.code == .aborted)
        // "Aborted" alone would send someone to delete a file that is about to
        // appear.
        #expect(error?.message.contains("may still arrive") == true)
        #expect(error?.message.contains("/tmp/c.mp4") == true)
    }

    @Test("a completion arriving after teardown touches nothing")
    func lateCompletionIsHarmless() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)

        let clip = try await answering(fake, result: clipPayload) {
            try await client.requestClip(.seconds(10))
        }

        let settled = Locked(false)
        Task {
            _ = try? await client.download(clip, to: URL(fileURLWithPath: "/tmp/c.mp4"))
            settled.withLock { $0 = true }
        }
        #expect(await waitUntil { fake.hasPendingDownload }, "the download never started")

        client.close()
        #expect(await waitUntil { settled.withLock { $0 } })

        // The ticket carries only a weak reference, and it is the callback's own
        // to free — so this is safe, and it is the exact sequence AddressSanitizer
        // caught the C++ SDK on. Under ASan a regression here is a report; here it
        // is at least a crash rather than a pass.
        fake.reportDownloadProgress(done: 2, total: 4)
        fake.completeDownload(ok: true, result: #"{"path":"/tmp/c.mp4","bytes":1,"segments":4}"#)
    }

    @Test("starting a download on a closed client is refused")
    func downloadAfterCloseIsRefused() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)

        let clip = try await answering(fake, result: clipPayload) {
            try await client.requestClip(.seconds(10))
        }
        client.close()

        await #expect(throws: ReactorError.self) {
            try await client.download(clip, to: URL(fileURLWithPath: "/tmp/c.mp4"))
        }
    }

    // MARK: - What the type system rules out

    @Test("a ready timeout cannot be a NaN, and a huge one stays finite")
    func timeoutsAreAlwaysFinite() {
        // The C header warns that a NaN crossing as a double panics inside a
        // detached task, which drops the completion and leaves the binding
        // waiting for a callback that can no longer come. `Duration` cannot hold
        // a NaN, so the public API cannot express one — and even the largest it
        // can hold converts to a finite number of seconds.
        #expect(Duration.seconds(30).seconds == 30)
        #expect(Duration.milliseconds(1500).seconds == 1.5)
        #expect(Duration(secondsComponent: .max, attosecondsComponent: 0).seconds.isFinite)
        #expect(Duration.zero.seconds == 0)
    }
}
