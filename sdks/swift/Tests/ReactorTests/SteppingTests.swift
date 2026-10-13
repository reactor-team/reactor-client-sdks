import Foundation
import Testing

@testable import Reactor

@Suite("Stepped generation")
struct SteppingTests {

    private func makeClient(
        fake: FakeLibrary,
        generation: Generation = .stepped,
        stepTimeout: Duration? = nil,
        eventQueue: DispatchQueue? = nil
    ) throws -> Reactor {
        try Reactor(
            model: "owner/model",
            jwt: "token",
            apiURL: Reactor.defaultAPIURL,
            local: false,
            generation: generation,
            stepTimeout: stepTimeout,
            eventQueue: eventQueue,
            ffi: fake.table)
    }

    /// Start a step, wait for it to reach the library, answer it with `result`
    /// or `error`, and hand back what the caller got.
    private func stepping(
        _ fake: FakeLibrary,
        ok: Bool = true,
        result: FakeStepResult? = nil,
        error: String? = nil,
        _ work: @escaping @Sendable () async throws -> StepResult
    ) async throws -> StepResult {
        let task = Task { try await work() }
        #expect(await waitUntil { fake.hasPendingStep }, "the step never reached the library")
        fake.completeLastStep(ok: ok, result: result, error: error)
        return try await task.value
    }

    /// A result with one video track of two RGB frames and one audio track.
    private func sampleResult(index: UInt64 = 1) -> FakeStepResult {
        FakeStepResult(
            index: index,
            tracks: [
                .init(
                    name: "main_video", kind: "video",
                    frames: [
                        .init(
                            dtype: "uint8", shape: [2, 2, 3], data: Array(0..<12),
                            metadata: Array("tag".utf8), captureTimeUs: 42),
                        .init(
                            dtype: "uint8", shape: [2, 2, 3], data: Array(repeating: 9, count: 12)),
                    ]),
                .init(
                    name: "main_audio", kind: "audio",
                    frames: [
                        // (channels, samples) int16: 2 x 3, so 12 bytes.
                        .init(
                            dtype: "int16", shape: [2, 3], data: Array(repeating: 1, count: 12),
                            metadata: [])
                    ]),
            ])
    }

    // MARK: - Creation

    @Test("a streamed client is created exactly as before, without options")
    func streamedUsesTheADMEntry() throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake, generation: .streamed)
        defer { client.close() }

        #expect(client.generation == .streamed)
        #expect(fake.createCalls.count == 1)
        #expect(fake.createOptionsCalls.isEmpty)
    }

    @Test("a stepped client asks for stepped generation, with every option spelled out")
    func steppedUsesTheOptionsEntry() throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake, stepTimeout: .seconds(45))
        defer { client.close() }

        #expect(client.generation == .stepped)
        #expect(fake.createCalls.isEmpty)
        let call = try #require(fake.createOptionsCalls.first)
        #expect(call.model == "owner/model")
        #expect(call.jwt == "token")
        #expect(call.hadStepAccepted, "the step-accepted callback has no other way in")

        let options = call.options
        #expect(options["generation"] as? String == "stepped")
        // Synthetic, named: the library would otherwise read the mode from the
        // environment, and that must never open a microphone.
        #expect(options["adm"] as? String == "synthetic")
        #expect(options["sdk_type"] as? String == "swift")
        #expect(options["sdk_version"] as? String == ReactorSDK.version)
        #expect(options["local"] as? Bool == false)
        #expect(options["auto_resume_tracks"] as? Bool == true)
        #expect(options["step_timeout_seconds"] as? Double == 45)
    }

    @Test("without a step timeout the library's default is left alone")
    func noStepTimeoutSendsNone() throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        #expect(fake.createOptionsCalls.first?.options["step_timeout_seconds"] == nil)
    }

    @Test("a step timeout on a streamed client is refused, not ignored")
    func stepTimeoutNeedsStepped() {
        let fake = FakeLibrary()
        #expect {
            _ = try makeClient(fake: fake, generation: .streamed, stepTimeout: .seconds(10))
        } throws: { ($0 as? ReactorError)?.code == .badRequest }
        #expect(fake.createCalls.isEmpty)
        #expect(fake.createOptionsCalls.isEmpty)
    }

    @Test("a step timeout that is not positive is refused before anything is created")
    func stepTimeoutMustBePositive() {
        let fake = FakeLibrary()
        #expect {
            _ = try makeClient(fake: fake, stepTimeout: .zero)
        } throws: { ($0 as? ReactorError)?.code == .badRequest }
        #expect(fake.createOptionsCalls.isEmpty)
    }

    @Test("options the library refuses fail creation with its reason, and its string is freed")
    func refusedOptionsThrowTheLibrarysError() {
        let fake = FakeLibrary()
        fake.createOptionsFailure =
            #"{"code":"INVALID_STATE","message":"create options are not valid: nope","recoverable":false,"operation":"create"}"#

        #expect {
            _ = try makeClient(fake: fake)
        } throws: { error in
            let error = error as? ReactorError
            return error?.code == .invalidState && error?.message.contains("nope") == true
        }
        #expect(fake.freedStrings == 1)
        // Nothing was created, so there is nothing to destroy.
        #expect(fake.destroyCount == 0)
    }

    // MARK: - step()

    @Test("step on a streamed client is refused without reaching the library")
    func stepNeedsAStepedClient() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake, generation: .streamed)
        defer { client.close() }

        await #expect {
            _ = try await client.step()
        } throws: { ($0 as? ReactorError)?.code == .invalidState }
        #expect(fake.stepCalls.isEmpty)
    }

    @Test("state and commands reach the library as the JSON the header describes")
    func stepCarriesStateAndCommands() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        _ = try await stepping(fake, result: sampleResult()) {
            try await client.step(
                state: ["prompt": "a red fox"],
                commands: [StepCommand("advance", ["frames": 4]), StepCommand("reset")])
        }

        let call = try #require(fake.stepCalls.first)
        let state = try #require(
            JSONSerialization.jsonObject(with: Data((call.stateJSON ?? "").utf8))
                as? [String: Any])
        #expect(state["prompt"] as? String == "a red fox")

        let commands = try #require(
            JSONSerialization.jsonObject(with: Data((call.commandsJSON ?? "").utf8))
                as? [[String: Any]])
        #expect(commands.count == 2)
        #expect(commands[0]["command"] as? String == "advance")
        #expect((commands[0]["data"] as? [String: Any])?["frames"] as? Double == 4)
        #expect(commands[1]["command"] as? String == "reset")
        // Present and null, never a missing key the runtime has to guess about.
        #expect(commands[1]["data"] is NSNull)
    }

    @Test("no state means no state, and no commands an empty list")
    func stepWithNothing() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        _ = try await stepping(fake, result: sampleResult()) { try await client.step() }

        let call = try #require(fake.stepCalls.first)
        #expect(call.stateJSON == nil)
        #expect(call.commandsJSON == "[]")
    }

    @Test("a command with no name is refused before anything is sent")
    func emptyCommandNameIsRefused() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        await #expect {
            _ = try await client.step(commands: [StepCommand("")])
        } throws: { ($0 as? ReactorError)?.code == .badRequest }
        #expect(fake.stepCalls.isEmpty)
    }

    @Test("the result is copied out whole and the library's copy freed exactly once")
    func resultIsCopiedAndFreed() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        // Held weakly and nowhere else, so its release shows the SDK let go.
        weak var watched: FakeStepResult?
        func handedOver() -> FakeStepResult {
            let native = sampleResult(index: 7)
            watched = native
            return native
        }
        let result = try await stepping(fake, result: handedOver()) { try await client.step() }

        #expect(fake.freedStepResults == 1)
        #expect(watched == nil, "the SDK kept the library's result alive past the step")

        #expect(result.index == 7)
        #expect(result.tracks.map(\.name) == ["main_video", "main_audio"])

        let video = try result.track("main_video")
        #expect(video.kind == .video)
        #expect(video.frames.count == 2)
        let first = video.frames[0]
        #expect(first.dtype == "uint8")
        #expect(first.shape == [2, 2, 3])
        #expect(first.encoding == "raw")
        #expect(first.data == Data(0..<12))
        #expect(first.metadata == Data("tag".utf8))
        #expect(first.captureTimeUs == 42)
        #expect(video.frames[1].metadata == nil)
        #expect(video.frames[1].captureTimeUs == nil)

        let audio = try result.track("main_audio")
        #expect(audio.kind == .audio)
        #expect(audio.frames[0].shape == [2, 3])
        #expect(audio.frames[0].dtype == "int16")
        // An attached but empty tag is not the same as no tag.
        #expect(audio.frames[0].metadata == Data())
    }

    @Test("asking for a track that produced nothing names the ones that did")
    func missingTrackNamesTheProducedOnes() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let result = try await stepping(fake, result: sampleResult()) { try await client.step() }

        #expect {
            _ = try result.track("main_vidoe")
        } throws: { error in
            let error = error as? ReactorError
            return error?.code == .notFound && error?.message.contains("'main_video'") == true
        }
    }

    @Test("a refused step throws the runtime's own code")
    func refusedStepThrowsStepRefused() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        do {
            _ = try await stepping(
                fake, ok: false,
                error:
                    #"{"code":"step_refused","message":"no","recoverable":false,"operation":"step"}"#
            ) { try await client.step() }
            Issue.record("a refused step returned a result")
        } catch ReactorError.stepRefused {
            // The matcher is the point: `catch ReactorError.stepRefused` reads
            // like every other code.
        }
        #expect(fake.freedStepResults == 0)
    }

    @Test("a frame whose bytes disagree with its shape fails the step, and is still freed")
    func malformedFrameIsADecodeFailure() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let bad = FakeStepResult(
            index: 1,
            tracks: [
                .init(
                    name: "main_video", kind: "video",
                    frames: [.init(dtype: "uint8", shape: [2, 2, 3], data: Array(0..<11))])
            ])

        await #expect {
            _ = try await stepping(fake, result: bad) { try await client.step() }
        } throws: { ($0 as? ReactorError)?.code == .decodeFailed }
        #expect(fake.freedStepResults == 1)
    }

    @Test("a track of a kind this SDK does not know fails the step rather than guessing")
    func unknownKindIsADecodeFailure() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let odd = FakeStepResult(
            index: 1, tracks: [.init(name: "depth", kind: "depth", frames: [])])

        await #expect {
            _ = try await stepping(fake, result: odd) { try await client.step() }
        } throws: { ($0 as? ReactorError)?.code == .decodeFailed }
        #expect(fake.freedStepResults == 1)
    }

    @Test("closing with a step in flight settles the caller and releases what the library held")
    func closeSettlesAStepInFlight() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)

        // Watched through a flag rather than awaited: a continuation that is
        // never resumed ignores cancellation, so a regression would hang the run.
        let settled = Locked(false)
        let outcome = Locked<(any Error)?>(nil)
        Task {
            do { _ = try await client.step() } catch { outcome.withLock { $0 = error } }
            settled.withLock { $0 = true }
        }
        #expect(await waitUntil { fake.hasPendingStep }, "the step never reached the library")
        #expect(fake.lastUserdataIsAlive)

        client.close()

        #expect(await waitUntil { settled.withLock { $0 } })
        #expect((outcome.withLock { $0 } as? ReactorError)?.code == .aborted)
        #expect(
            await waitUntil { !fake.lastUserdataIsAlive },
            "close leaked the step completion it handed the library")
    }

    @Test("a result arriving after teardown settled the caller is still freed")
    func lateResultIsFreed() async throws {
        let fake = FakeLibrary()
        // -1: a callback could not be waited for, so the completion may still
        // come — and when it does, the result it hands over is nobody's but its.
        fake.destroyResult = -1
        let client = try makeClient(fake: fake)

        let settled = Locked(false)
        Task {
            _ = try? await client.step()
            settled.withLock { $0 = true }
        }
        #expect(await waitUntil { fake.hasPendingStep })
        client.close()
        #expect(await waitUntil { settled.withLock { $0 } })

        fake.completeLastStep(ok: true, result: sampleResult())
        #expect(fake.freedStepResults == 1)
    }

    // MARK: - The step-accepted event

    @Test("an accepted step reaches handlers on the event queue, with its index")
    func stepAcceptedIsDelivered() async throws {
        let fake = FakeLibrary()
        let queue = DispatchQueue(label: "test.events")
        let key = DispatchSpecificKey<Bool>()
        queue.setSpecific(key: key, value: true)
        let client = try makeClient(fake: fake, eventQueue: queue)
        defer { client.close() }

        let seen = Locked<[(UInt64, Bool)]>([])
        let subscription = client.onStepAccepted { index in
            seen.withLock { $0.append((index, DispatchQueue.getSpecific(key: key) == true)) }
        }
        defer { subscription.cancel() }

        fake.fireStepAccepted(3)

        #expect(await waitUntil { !seen.withLock { $0 }.isEmpty })
        let first = try #require(seen.withLock { $0 }.first)
        #expect(first.0 == 3)
        #expect(first.1, "a control event ran off the event queue")
    }

    @Test("accepted steps arrive in order on the stream")
    func stepsAcceptedStream() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let stream = client.stepsAccepted
        var iterator = stream.makeAsyncIterator()
        // The stream registers its handler when it is built, so these are seen.
        fake.fireStepAccepted(1)
        fake.fireStepAccepted(2)

        #expect(await iterator.next() == 1)
        #expect(await iterator.next() == 2)
    }

    @Test("a cancelled subscription hears nothing more")
    func cancelledStepAcceptedHandlerStops() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let count = Locked(0)
        let subscription = client.onStepAccepted { _ in count.withLock { $0 += 1 } }
        subscription.cancel()
        fake.fireStepAccepted(1)

        try await Task.sleep(for: .milliseconds(50))
        #expect(count.withLock { $0 } == 0)
    }

    // MARK: - clearStepInputs()

    @Test("clearing step inputs reaches the library on a stepped client only")
    func clearStepInputs() throws {
        let stepped = FakeLibrary()
        let client = try makeClient(fake: stepped)
        client.clearStepInputs()
        #expect(stepped.clearStepInputsCalls == 1)

        // Closed: no handle to hand the library, and no error to report.
        client.close()
        client.clearStepInputs()
        #expect(stepped.clearStepInputsCalls == 1)

        let streamed = FakeLibrary()
        let other = try makeClient(fake: streamed, generation: .streamed)
        defer { other.close() }
        other.clearStepInputs()
        #expect(streamed.clearStepInputsCalls == 0)
    }

    // MARK: - Value types

    @Test("a typed command's arguments encode as the object the runtime reads")
    func typedStepCommand() throws {
        struct Advance: Encodable, Sendable {
            let frames: Int
            let image: FileRef
        }
        let command = try StepCommand(
            "advance",
            arguments: Advance(
                frames: 2,
                image: FileRef(uploadID: "up_1", name: "a.png", mimeType: "image/png", size: 3)))

        #expect(command.data?["frames"]?.intValue == 2)
        #expect(command.data?["image"]?["upload_id"]?.stringValue == "up_1")
    }

    @Test("a typed command whose arguments are not an object is refused")
    func typedStepCommandMustBeAnObject() {
        #expect {
            _ = try StepCommand("advance", arguments: [1, 2, 3])
        } throws: { ($0 as? ReactorError)?.code == .badRequest }
    }

    @Test("a frame is checked against its shape when it is made, not when it is read")
    func stepFrameValidatesItsSize() throws {
        _ = try StepFrame(data: Data(count: 12), dtype: "int16", shape: [2, 3])
        #expect {
            _ = try StepFrame(data: Data(count: 11), dtype: "int16", shape: [2, 3])
        } throws: { ($0 as? ReactorError)?.code == .decodeFailed }
        // A dtype this SDK has no size for cannot be checked, so it is let through.
        _ = try StepFrame(data: Data(count: 5), dtype: "bfloat16", shape: [2, 3])
    }
}
