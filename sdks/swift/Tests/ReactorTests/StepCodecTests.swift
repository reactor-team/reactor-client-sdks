import Foundation
import Testing

@testable import Reactor

@Suite("Step frame codecs")
struct StepCodecTests {

    private func makeClient(
        fake: FakeLibrary, generation: Generation = .stepped
    ) throws -> Reactor {
        try Reactor(
            model: "owner/model",
            jwt: "token",
            apiURL: Reactor.defaultAPIURL,
            local: false,
            generation: generation,
            eventQueue: nil,
            ffi: fake.table)
    }

    // MARK: - setCodecPreference

    @Test("the default order is H.264, VP9, AV1, VP8, spelled as the library takes them")
    func codecNames() {
        #expect(StepCodec.allCases.map(\.rawValue) == ["h264", "vp9", "av1", "vp8"])
    }

    @Test("a preference reaches the library at once, before any connect, as a JSON array")
    func preferenceIsSentBeforeConnect() throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        try client.setCodecPreference([.vp9, .h264])

        #expect(fake.connectCalls == 0)
        #expect(
            fake.codecPreferenceCalls == [.init(track: nil, codecsJSON: #"["vp9","h264"]"#)])
        // Success answers null: nothing to free.
        #expect(fake.freedStrings == 0)
    }

    @Test("an empty list turns encoding off, nil goes back to the default, and a track is named")
    func emptyNilAndTrack() throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        try client.setCodecPreference([])
        try client.setCodecPreference(nil)
        try client.setCodecPreference([.av1], track: "main_video")
        try client.setCodecPreference(nil, track: "webcam")

        #expect(
            fake.codecPreferenceCalls == [
                .init(track: nil, codecsJSON: "[]"),
                .init(track: nil, codecsJSON: nil),
                .init(track: "main_video", codecsJSON: #"["av1"]"#),
                .init(track: "webcam", codecsJSON: nil),
            ])
    }

    @Test("a preference set during a session reaches the same handle")
    func preferenceDuringSession() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let connect = Task { try await client.connect() }
        #expect(await waitUntil { fake.hasPendingCompletion })
        fake.completeLastCall(ok: true)
        try await connect.value

        try client.setCodecPreference([.h264])
        #expect(fake.codecPreferenceCalls == [.init(track: nil, codecsJSON: #"["h264"]"#)])
    }

    @Test("the library's refusal is thrown as its error, and its string freed")
    func refusalIsThrown() throws {
        let fake = FakeLibrary()
        fake.codecPreferenceFailure =
            #"{"code":"DECODE_FAILED","message":"unknown codec","recoverable":false,"operation":"set_codec_preference"}"#
        let client = try makeClient(fake: fake)
        defer { client.close() }

        #expect {
            try client.setCodecPreference([.h264])
        } throws: { error in
            let error = error as? ReactorError
            return error?.code == .decodeFailed && error?.message.contains("unknown codec") == true
        }
        #expect(fake.freedStrings == 1)
    }

    @Test("a closed client refuses a preference without reaching the library")
    func closedClientRefuses() throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        client.close()

        #expect {
            try client.setCodecPreference([.h264])
        } throws: { ($0 as? ReactorError)?.code == .invalidState }
        #expect(fake.codecPreferenceCalls.isEmpty)
    }

    // MARK: - stepFrameEncodings

    @Test("the encodings are read from the library, and its string freed")
    func encodingsAreRead() throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        #expect(client.stepFrameEncodings.isEmpty)

        fake.setStepFrameEncodings(#"{"main_video":"h264","webcam":"h264","main_audio":"raw"}"#)
        let freedBefore = fake.freedStrings
        #expect(
            client.stepFrameEncodings
                == ["main_video": "h264", "webcam": "h264", "main_audio": "raw"])
        #expect(fake.freedStrings == freedBefore + 1)
    }

    @Test("unreadable encodings, or a closed client, read as none")
    func unreadableEncodingsAreEmpty() throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)

        fake.setStepFrameEncodings("not json")
        #expect(client.stepFrameEncodings.isEmpty)

        fake.setStepFrameEncodings(#"{"main_video":"h264"}"#)
        client.close()
        #expect(client.stepFrameEncodings.isEmpty)
    }
}
