import CReactorFFI
import Foundation
import Testing

@testable import Reactor

@Suite("Encoded frames in a step")
struct EncodedFrameTests {

    private func makeClient(fake: FakeLibrary, generation: Generation = .stepped) throws -> Reactor
    {
        try Reactor(
            model: "owner/model",
            jwt: "token",
            apiURL: Reactor.defaultAPIURL,
            local: false,
            generation: generation,
            stepTimeout: nil,
            eventQueue: nil,
            ffi: fake.table)
    }

    /// A ready session with a sendonly video, a sendonly audio and a recvonly
    /// video track.
    private func declaringSession(_ fake: FakeLibrary) {
        fake.setTracks([
            (name: "camera", kind: "video", direction: "sendonly"),
            (name: "mic", kind: "audio", direction: "sendonly"),
            (name: "output", kind: "video", direction: "recvonly"),
        ])
        fake.setStatus("ready")
    }

    private func publish(_ track: Track, on fake: FakeLibrary) async throws {
        let publishing = Task { try await track.publish() }
        #expect(await waitUntil { fake.hasPendingCompletion })
        fake.completeLastCall(ok: true)
        try await publishing.value
    }

    /// A stepped client whose `camera` is published.
    private func publishedCamera(_ fake: FakeLibrary) async throws -> (Reactor, Track) {
        declaringSession(fake)
        let client = try makeClient(fake: fake)
        let camera = try client.track("camera")
        try await publish(camera, on: fake)
        return (client, camera)
    }

    private static let idr = Data([0, 0, 0, 1, 0x65, 0xAA])
    private static let keyframe = StepMediaFrame.encoded(EncodedFrame(idr, width: 2, height: 2))

    // MARK: - pushEncodedFrame

    @Test("an encoded frame reaches the library untouched, as H.264, tagged and stamped")
    func encodedFrameReachesTheLibrary() async throws {
        let fake = FakeLibrary()
        let (client, camera) = try await publishedCamera(fake)
        defer { client.close() }

        try camera.pushEncodedFrame(
            EncodedFrame(
                Self.idr, width: 1280, height: 720, userData: Data("t".utf8), captureTimeUs: 9))
        try camera.pushEncodedFrame(Data([1, 2]), width: 4, height: 2)

        let pushed = fake.pushedEncoded
        #expect(pushed.count == 2)
        #expect(
            pushed[0]
                == FakeLibrary.PushedEncodedFrame(
                    track: "camera", encoding: Int32(REACTOR_FRAME_ENCODING_H264),
                    data: Array(Self.idr), width: 1280, height: 720, userData: Array("t".utf8),
                    captureTimeUs: 9))
        #expect(pushed[0].encoding == 2)
        // No tag and no capture time reach the library as none, not as 0.
        #expect(pushed[1].userData == nil)
        #expect(pushed[1].captureTimeUs == nil)
        #expect(pushed[1].data == [1, 2])
        #expect(fake.pushedFrames.isEmpty)
    }

    @Test("a streamed client takes no encoded frame")
    func needsASteppedSession() async throws {
        let fake = FakeLibrary()
        declaringSession(fake)
        let client = try makeClient(fake: fake, generation: .streamed)
        defer { client.close() }
        let camera = try client.track("camera")
        try await publish(camera, on: fake)

        #expect {
            try camera.pushEncodedFrame(Self.idr, width: 2, height: 2)
        } throws: {
            let error = $0 as? ReactorError
            return error?.code == .invalidState
                && error?.message.contains("stepped session") == true
        }
        #expect(fake.pushedEncoded.isEmpty)
    }

    @Test("an audio track takes no encoded frame, and says only video does")
    func audioTrackIsRefused() async throws {
        let fake = FakeLibrary()
        let (client, _) = try await publishedCamera(fake)
        defer { client.close() }

        #expect {
            try client.track("mic").pushEncodedFrame(Self.idr, width: 2, height: 2)
        } throws: {
            let error = $0 as? ReactorError
            return error?.code == .invalidState
                && error?.message.contains("only a video track") == true
        }
        #expect(fake.pushedEncoded.isEmpty)
    }

    @Test("a recvonly track takes no encoded frame")
    func recvonlyTrackIsRefused() async throws {
        let fake = FakeLibrary()
        let (client, _) = try await publishedCamera(fake)
        defer { client.close() }

        #expect {
            try client.track("output").pushEncodedFrame(Self.idr, width: 2, height: 2)
        } throws: {
            let error = $0 as? ReactorError
            return error?.code == .invalidState && error?.message.contains("recvonly") == true
        }
        #expect(fake.pushedEncoded.isEmpty)
    }

    @Test("an encoded frame on an unpublished track is refused, not dropped")
    func unpublishedTrackIsRefused() throws {
        let fake = FakeLibrary()
        declaringSession(fake)
        let client = try makeClient(fake: fake)
        defer { client.close() }

        #expect {
            try client.track("camera").pushEncodedFrame(Self.idr, width: 2, height: 2)
        } throws: { ($0 as? ReactorError)?.code == .invalidState }
        #expect(fake.pushedEncoded.isEmpty)
    }

    @Test(
        "a frame with no data or no size is refused before the call",
        arguments: [
            (Data(), UInt32(2), UInt32(2), "no data"),
            (Data([1]), UInt32(0), UInt32(2), "decoded frame"),
            (Data([1]), UInt32(2), UInt32(0), "decoded frame"),
        ])
    func malformedFramesAreRefused(
        data: Data, width: UInt32, height: UInt32, words: String
    )
        async throws
    {
        let fake = FakeLibrary()
        let (client, camera) = try await publishedCamera(fake)
        defer { client.close() }

        #expect {
            try camera.pushEncodedFrame(data, width: width, height: height)
        } throws: {
            let error = $0 as? ReactorError
            return error?.code == .badRequest && error?.message.contains(words) == true
        }
        #expect(fake.pushedEncoded.isEmpty)
    }

    // MARK: - step(media:)

    @Test("a step's media is pushed in order, raw and encoded, before the step goes out")
    func mediaIsPushedBeforeTheStep() async throws {
        let fake = FakeLibrary()
        let (client, _) = try await publishedCamera(fake)
        defer { client.close() }
        try await publish(client.track("mic"), on: fake)

        let task = Task {
            try await client.step(
                media: [
                    "mic": [.audio([1, 2, 3, 4], sampleRate: 48_000, channels: 2)],
                    "camera": [
                        .encoded(EncodedFrame(Self.idr, width: 2, height: 2)),
                        .encoded(EncodedFrame(Data([0, 0, 1, 0x41]), width: 2, height: 2)),
                        .video(Data(repeating: 7, count: 2 * 2 * 4), width: 2, height: 2),
                    ],
                ])
        }
        #expect(await waitUntil { fake.hasPendingStep }, "the step never reached the library")
        // Everything was buffered by the time the step reached the library.
        #expect(
            fake.pushLog == ["encoded:camera", "encoded:camera", "video:camera", "audio:mic"])
        #expect(fake.pushedEncoded.map(\.data) == [Array(Self.idr), [0, 0, 1, 0x41]])
        #expect(fake.pushedAudio.first?.samplesPerChannel == 2)
        fake.completeLastStep(ok: true, result: FakeStepResult(step: 1, tracks: []))
        _ = try await task.value
        #expect(fake.stepCalls.count == 1)
    }

    @Test("a step refused for its commands pushes none of its media")
    func malformedStepPushesNoMedia() async throws {
        let fake = FakeLibrary()
        let (client, _) = try await publishedCamera(fake)
        defer { client.close() }

        await #expect {
            _ = try await client.step(
                commands: [StepCommand("")],
                media: ["camera": [.encoded(EncodedFrame(Self.idr, width: 2, height: 2))]])
        } throws: { ($0 as? ReactorError)?.code == .badRequest }
        #expect(fake.pushLog.isEmpty)
        #expect(fake.stepCalls.isEmpty)
    }

    @Test("media for a track the session does not declare fails the step before it goes out")
    func mediaForAnUnknownTrack() async throws {
        let fake = FakeLibrary()
        let (client, _) = try await publishedCamera(fake)
        defer { client.close() }

        await #expect {
            _ = try await client.step(
                media: ["webcam": [.encoded(EncodedFrame(Self.idr, width: 2, height: 2))]])
        } throws: { ($0 as? ReactorError)?.code == .notFound }
        #expect(fake.pushLog.isEmpty)
        #expect(fake.stepCalls.isEmpty)
    }

    @Test("a media frame its push refuses fails the step, which is not sent")
    func refusedMediaFrameStopsTheStep() async throws {
        let fake = FakeLibrary()
        let (client, _) = try await publishedCamera(fake)
        defer { client.close() }

        await #expect {
            _ = try await client.step(
                media: ["camera": [.encoded(EncodedFrame(Data(), width: 2, height: 2))]])
        } throws: { ($0 as? ReactorError)?.code == .badRequest }
        #expect(fake.stepCalls.isEmpty)
    }

    @Test(
        "a bad frame or track anywhere in a step's media pushes none of it",
        arguments: [
            // A later frame of the same track: an empty encoded one...
            [
                "camera": [
                    EncodedFrameTests.keyframe,
                    .video(Data(repeating: 7, count: 2 * 2 * 4), width: 2, height: 2),
                    .encoded(EncodedFrame(Data(), width: 2, height: 2)),
                ]
            ],
            // ...or a raw one too short for its size.
            [
                "camera": [
                    EncodedFrameTests.keyframe,
                    .video(Data(count: 4), width: 2, height: 2),
                ]
            ],
            // A later track, after one whose frames are all good: unpublished...
            [
                "camera": [EncodedFrameTests.keyframe],
                "mic": [.audio([1, 2], sampleRate: 48_000, channels: 1)],
            ],
            // ...recvonly...
            [
                "camera": [EncodedFrameTests.keyframe],
                "output": [EncodedFrameTests.keyframe],
            ],
            // ...or not declared at all.
            [
                "camera": [EncodedFrameTests.keyframe],
                "webcam": [EncodedFrameTests.keyframe],
            ],
        ])
    func badMediaPushesNothing(media: [String: [StepMediaFrame]]) async throws {
        let fake = FakeLibrary()
        let (client, _) = try await publishedCamera(fake)
        defer { client.close() }

        await #expect(throws: ReactorError.self) {
            _ = try await client.step(media: media)
        }
        #expect(fake.pushLog.isEmpty, "frames before the bad one were pushed")
        #expect(fake.stepCalls.isEmpty)
    }
}
