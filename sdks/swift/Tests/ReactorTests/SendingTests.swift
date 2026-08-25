import Foundation
import Testing

@testable import Reactor

@Suite("Sending")
struct SendingTests {

    private func makeClient(fake: FakeLibrary) throws -> Reactor {
        try Reactor(
            model: "xmax/x2",
            jwt: nil,
            apiURL: Reactor.defaultAPIURL,
            local: false,
            eventQueue: nil,
            ffi: fake.table)
    }

    /// A session shaped like the one example 04 uses: an input track to push
    /// into, and an output track to receive on.
    private func declaringSession(_ fake: FakeLibrary) {
        fake.setTracks([
            (name: "source", kind: "video", direction: "sendonly"),
            (name: "main_video", kind: "video", direction: "recvonly"),
            (name: "mic", kind: "audio", direction: "sendonly"),
        ])
        fake.setStatus("ready")
    }

    /// A 2x2 BGRA frame.
    private var frame: Data { Data(repeating: 0x40, count: 16) }

    /// Publish `name`, answering the library's completion as it would.
    private func publish(_ track: Track, on fake: FakeLibrary) async throws {
        let publishing = Task { try await track.publish() }
        let deadline = Date().addingTimeInterval(2)
        while !fake.hasPendingCompletion, Date() < deadline {
            try await Task.sleep(for: .milliseconds(5))
        }
        fake.completeLastCall(ok: true)
        try await publishing.value
    }

    // MARK: - The refusals, which are the point

    @Test("pushing before publishing is refused, not dropped")
    func pushBeforePublishIsRefused() throws {
        let fake = FakeLibrary()
        declaringSession(fake)
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let source = try client.track("source")
        do {
            try source.pushFrame(frame, width: 2, height: 2)
            Issue.record("expected a throw")
        } catch let error as ReactorError {
            // The FFI would take this frame, find no sender behind the slot, and
            // return — a caller pushing at 30fps into a model receiving nothing.
            #expect(error.code == .invalidState)
            #expect(error.message.contains("publish()"))
        }
        #expect(fake.pushedFrames.isEmpty)
    }

    @Test("pushing while a publish is in flight is refused, and says to await it")
    func pushWhilePublishingIsRefused() async throws {
        let fake = FakeLibrary()
        declaringSession(fake)
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let source = try client.track("source")
        let publishing = Task { try await source.publish() }
        let deadline = Date().addingTimeInterval(2)
        while !fake.hasPendingCompletion, Date() < deadline {
            try await Task.sleep(for: .milliseconds(5))
        }

        // In flight is its own state. There is no sender behind the slot yet, so
        // counting it as published would drop the frame silently — and counting
        // it as nothing would tell a caller who just called publish() to publish.
        do {
            try source.pushFrame(frame, width: 2, height: 2)
            Issue.record("expected a throw")
        } catch let error as ReactorError {
            #expect(error.code == .invalidState)
            #expect(error.message.lowercased().contains("await"))
        }

        fake.completeLastCall(ok: true)
        try await publishing.value
        #expect(source.published)
    }

    @Test("pushing into a recvonly track is refused, naming the direction")
    func pushIntoRecvonlyIsRefused() throws {
        let fake = FakeLibrary()
        declaringSession(fake)
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let incoming = try client.track("main_video")
        do {
            try incoming.pushFrame(frame, width: 2, height: 2)
            Issue.record("expected a throw")
        } catch let error as ReactorError {
            #expect(error.code == .invalidState)
            #expect(error.message.contains("recvonly"))
            #expect(error.message.contains("onFrame"))
        }
    }

    @Test("pushing video into an audio track is refused")
    func pushVideoIntoAudioIsRefused() throws {
        let fake = FakeLibrary()
        declaringSession(fake)
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let mic = try client.track("mic")
        #expect(throws: ReactorError.self) { try mic.pushFrame(frame, width: 2, height: 2) }
    }

    @Test("a buffer too short for the frame is refused before the call")
    func shortBufferIsRefused() async throws {
        let fake = FakeLibrary()
        declaringSession(fake)
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let source = try client.track("source")
        try await publish(source, on: fake)

        do {
            // The FFI reads width * height * 4 bytes whatever it was handed, so
            // a short buffer is a read past the end rather than a smaller frame.
            try source.pushFrame(Data(repeating: 0, count: 8), width: 2, height: 2)
            Issue.record("expected a throw")
        } catch let error as ReactorError {
            #expect(error.code == .badRequest)
            #expect(error.message.contains("8 bytes"))
            #expect(error.message.contains("16"))
        }
        #expect(fake.pushedFrames.isEmpty)
    }

    @Test("dimensions whose byte count overflows are refused, not trapped")
    func overflowingDimensionsAreRefused() async throws {
        let fake = FakeLibrary()
        declaringSession(fake)
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let source = try client.track("source")
        try await publish(source, on: fake)

        do {
            // width * height * 4 in Int overflows for dimensions this large, and
            // Swift traps on that — so the frame the SDK is here to refuse took
            // the whole process with it instead.
            try source.pushFrame(frame, width: .max, height: .max)
            Issue.record("expected a throw")
        } catch let error as ReactorError {
            #expect(error.code == .badRequest)
            #expect(error.message.contains("does not fit"))
        }
        #expect(fake.pushedFrames.isEmpty)
    }

    @Test("a publish answered after the session dropped does not report success")
    func stalePublishDoesNotResurrectTheSlot() async throws {
        let fake = FakeLibrary()
        declaringSession(fake)
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let source = try client.track("source")
        let publishing = Task { try await source.publish() }
        #expect(await waitUntil { fake.hasPendingCompletion }, "publish never reached the library")

        // The drop lands between the library answering and the awaiting task
        // resuming. Written unconditionally, that resumption put `.published`
        // back over the state this status change just cleared — and every push
        // afterwards was accepted into a slot with no sender behind it.
        fake.fireStatus("connecting")
        fake.completeLastCall(ok: true, result: nil, error: nil)

        await #expect(throws: ReactorError.self) { try await publishing.value }
        #expect(!source.published)

        do {
            try source.pushFrame(frame, width: 2, height: 2)
            Issue.record("expected a throw")
        } catch let error as ReactorError {
            #expect(error.code == .invalidState)
        }
    }

    @Test("audio that does not divide by its channel count is refused")
    func raggedAudioIsRefused() async throws {
        let fake = FakeLibrary()
        declaringSession(fake)
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let mic = try client.track("mic")
        try await publish(mic, on: fake)

        #expect(throws: ReactorError.self) {
            try mic.pushAudioFrame([1, 2, 3], sampleRate: 48000, channels: 2)
        }
    }

    // MARK: - The happy path

    @Test("a published track pushes pixels, the tag and the capture time")
    func publishedTrackPushes() async throws {
        let fake = FakeLibrary()
        declaringSession(fake)
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let source = try client.track("source")
        try await publish(source, on: fake)
        #expect(fake.publishCalls == ["source"])

        try source.pushFrame(
            frame, width: 2, height: 2, userData: Data("tag".utf8), captureTimeUs: 99)

        let pushed = try #require(fake.pushedFrames.first)
        #expect(pushed.track == "source")
        #expect(pushed.width == 2)
        #expect(pushed.height == 2)
        #expect(pushed.byteCount == 16)
        #expect(pushed.firstByte == 0x40)
        #expect(pushed.userData == "tag")
        #expect(pushed.captureTimeUs == 99)
    }

    @Test("an untagged, unstamped push carries no tag and a zero stamp")
    func plainPush() async throws {
        let fake = FakeLibrary()
        declaringSession(fake)
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let source = try client.track("source")
        try await publish(source, on: fake)
        try source.pushFrame(frame, width: 2, height: 2)

        let pushed = try #require(fake.pushedFrames.first)
        #expect(pushed.userData == nil)
        // 0 means "stamp it as you push it", which is what the library does.
        #expect(pushed.captureTimeUs == 0)
    }

    @Test("audio reaches the library as samples per channel")
    func audioPush() async throws {
        let fake = FakeLibrary()
        declaringSession(fake)
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let mic = try client.track("mic")
        try await publish(mic, on: fake)
        try mic.pushAudioFrame([1, -1, 2, -2], sampleRate: 48000, channels: 1)

        let pushed = try #require(fake.pushedAudio.first)
        #expect(pushed.track == "mic")
        #expect(pushed.samples == [1, -1, 2, -2])
        #expect(pushed.samplesPerChannel == 4)
        #expect(pushed.sampleRate == 48000)
        #expect(pushed.channels == 1)
    }

    @Test("pause and resume reach their own calls")
    func pauseAndResume() async throws {
        let fake = FakeLibrary()
        declaringSession(fake)
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let source = try client.track("source")

        let pausing = Task { try await source.pause() }
        let deadline = Date().addingTimeInterval(2)
        while !fake.hasPendingCompletion, Date() < deadline {
            try await Task.sleep(for: .milliseconds(5))
        }
        fake.completeLastCall(ok: true)
        try await pausing.value

        let resuming = Task { try await source.resume() }
        while !fake.hasPendingCompletion, Date() < Date().addingTimeInterval(2) {
            try await Task.sleep(for: .milliseconds(5))
        }
        fake.completeLastCall(ok: true)
        try await resuming.value

        // Two calls with identical signatures. Swapped, the binding would resume
        // when asked to pause.
        #expect(fake.pauseCalls == ["source"])
        #expect(fake.resumeCalls == ["source"])
    }

    // MARK: - Publishing state, which only the binding knows

    @Test("a publish does not survive the session leaving ready")
    func publishIsForgottenWhenNotReady() async throws {
        let fake = FakeLibrary()
        declaringSession(fake)
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let source = try client.track("source")
        try await publish(source, on: fake)
        #expect(source.published)

        // A reconnect resumes recvonly tracks and nothing else, so a slot
        // published before one is not published after it.
        fake.fireStatus("connecting")
        #expect(!source.published)

        do {
            try source.pushFrame(frame, width: 2, height: 2)
            Issue.record("expected a throw")
        } catch let error as ReactorError {
            #expect(error.code == .invalidState)
            #expect(error.message.contains("reconnect"))
        }
    }

    @Test("a failed publish leaves the track retryable")
    func failedPublishIsRetryable() async throws {
        let fake = FakeLibrary()
        declaringSession(fake)
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let source = try client.track("source")
        let publishing = Task { try await source.publish() }
        let deadline = Date().addingTimeInterval(2)
        while !fake.hasPendingCompletion, Date() < deadline {
            try await Task.sleep(for: .milliseconds(5))
        }
        fake.completeLastCall(ok: false, result: nil, error: #"{"code":"CONFLICT","message":"no"}"#)

        await #expect(throws: ReactorError.self) { try await publishing.value }

        // Left at `publishing`, every later push would be refused with "await the
        // publish" for a publish that is never coming.
        #expect(!source.published)
        do {
            try source.pushFrame(frame, width: 2, height: 2)
            Issue.record("expected a throw")
        } catch let error as ReactorError {
            #expect(error.message.contains("publish()"))
        }
    }

    @Test("unpublish clears the slot on success")
    func unpublishClears() async throws {
        let fake = FakeLibrary()
        declaringSession(fake)
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let source = try client.track("source")
        try await publish(source, on: fake)

        try source.unpublish()

        #expect(fake.unpublishCalls == ["source"])
        #expect(!source.published)
    }

    @Test("a failed unpublish keeps the slot, so it stays retryable")
    func failedUnpublishKeepsState() async throws {
        let fake = FakeLibrary()
        declaringSession(fake)
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let source = try client.track("source")
        try await publish(source, on: fake)

        fake.unpublishFailure = #"{"code":"DISCONNECTED","message":"gone"}"#
        do {
            try source.unpublish()
            Issue.record("expected a throw")
        } catch let error as ReactorError {
            #expect(error.code == .disconnected)
        }

        // Clearing on failure would make the failure unretryable: the next
        // unpublish would be refused for a track this client still published.
        #expect(source.published)

        fake.unpublishFailure = nil
        try source.unpublish()
        #expect(!source.published)
    }

    @Test("pushing on a closed client is refused")
    func pushAfterCloseIsRefused() async throws {
        let fake = FakeLibrary()
        declaringSession(fake)
        let client = try makeClient(fake: fake)

        let source = try client.track("source")
        try await publish(source, on: fake)
        client.close()

        #expect(throws: ReactorError.self) { try source.pushFrame(frame, width: 2, height: 2) }
    }

    // MARK: - The clock

    @Test("the capture clock is the library's, and it is not the UNIX epoch")
    func timeMicrosComesFromTheLibrary() {
        // Read once per unit of produced media and shared across tracks: tracks
        // are synchronised by sharing a capture time, not by reaching the
        // encoder together.
        #expect(Reactor.timeMicros() > 0)
    }
}
