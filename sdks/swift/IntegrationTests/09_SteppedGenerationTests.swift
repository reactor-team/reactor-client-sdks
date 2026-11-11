import Foundation
import Reactor
import TestSupport
import Testing

/// Scenario 09, scripted: stepped generation against a real runtime. Mirrors
/// `sdks/python/integration-tests/tests/test_stepped_generation.py`.
///
/// The generic tests need a model with stepped generation: set
/// REACTOR_STEPPED_MODEL_NAME or they are skipped. REACTOR_STEPPED_INPUT_TRACK
/// names a sendonly video track to push into; without it the input test is
/// skipped. `reactor/echo` always runs: a stepped connect to it must step or
/// fail, never stream. Echo steps from 1.10.0; until that release is deployed
/// everywhere this suite runs, an older echo refuses the start, and the test
/// accepts either.
@Suite("09 - stepped generation")
struct SteppedGenerationTests {

    static let steppedModel = environment("REACTOR_STEPPED_MODEL_NAME")
    static let inputTrack = environment("REACTOR_STEPPED_INPUT_TRACK")

    private static func environment(_ name: String) -> String? {
        guard let raw = ProcessInfo.processInfo.environment[name], !raw.isEmpty else { return nil }
        return raw
    }

    /// Echo from 1.10.0 runs the default step loop, so the session is stepped:
    /// a step carries the pushed webcam frame and comes back inverted on
    /// `main_video`. An older echo drives its own `run()`: a runtime from 3.9.0
    /// refuses that stepped start and the session closes before it is ready
    /// (REA-7575), and a runtime before 3.9.0 streams, which the client reports
    /// as a mode mismatch. Either refusal is right; a stepped connect that
    /// silently streams is not. The Python and Android suites accept the same
    /// outcomes.
    @Test("a stepped connect to echo steps or fails")
    func steppedConnectToEchoStepsOrFails() async throws {
        // Only a refused connect may pass: a session that dies during the step is a failure.
        var connected = false
        do {
            try await withConnectedReactor(generation: .stepped) { reactor in
                connected = true
                let webcam = try reactor.track("webcam")
                try await webcam.publish()
                try webcam.pushFrame(
                    MediaFixtures.solidBGRAFrame(width: 64, height: 64, color: (30, 20, 10)),
                    width: 64, height: 64)
                let result = try await reactor.step(state: ["effect": "invert"])
                #expect(result.error == nil)

                let video = try #require(result.tracks.first { $0.name == "main_video" })
                let frame = try #require(video.frames.first)
                #expect(frame.shape == [64, 64, 3])
                // RGB (10, 20, 30) inverted, at the frame's centre pixel.
                let centre = (32 * 64 + 32) * 3
                #expect(Array(Array(frame.data)[centre..<centre + 3]) == [245, 235, 225])
            }
        } catch let error as ReactorError where !connected {
            #expect(error.code == .generationModeMismatch || error.code == .sessionTerminal)
        }
    }

    @Test("steps resolve in order", .enabled(if: steppedModel != nil))
    func stepsResolveInOrder() async throws {
        let model = try #require(Self.steppedModel)
        try await withConnectedReactor(model: model, generation: .stepped) { reactor in
            let first = try await reactor.step(state: ["prompt": "a lighthouse in a storm"])
            let second = try await reactor.step()
            #expect(second.step == first.step + 1)
            #expect(first.elapsed >= .zero)
            #expect(first.error == nil)
            for track in first.tracks {
                #expect(track.rate >= 0)
                for frame in track.frames {
                    #expect(!frame.shape.isEmpty)
                }
            }
        }
    }

    @Test(
        "pushed frames ride the next step",
        .enabled(if: steppedModel != nil && inputTrack != nil))
    func pushedFramesRideTheNextStep() async throws {
        let model = try #require(Self.steppedModel)
        let input = try #require(Self.inputTrack)
        try await withConnectedReactor(model: model, generation: .stepped) { reactor in
            let source = try reactor.track(input)
            try await source.publish()
            try source.pushFrame(
                MediaFixtures.solidBGRAFrame(width: 64, height: 64, color: (0, 0, 255)),
                width: 64, height: 64)
            let result = try await reactor.step(state: ["prompt": "repaint it"])
            #expect(result.step >= 1)

            // Proof the push rode the step whatever the model makes of it: a
            // frame over the 64 MiB a chunked data channel carries can only fail
            // a step it went into. A push dropped on the way would let it through.
            try source.pushFrame(
                MediaFixtures.solidBGRAFrame(width: 5000, height: 5000, color: (0, 0, 255)),
                width: 5000, height: 5000)
            do {
                _ = try await reactor.step()
                Issue.record("expected a step carrying an oversized frame to throw")
            } catch let error as ReactorError {
                #expect(error.code == .messageTooLarge)
            }
        }
    }
}
