import Foundation
import Reactor
import TestSupport
import Testing

/// Scenario 09, scripted: stepped generation against a real runtime. Mirrors
/// `sdks/python/integration-tests/tests/test_stepped_generation.py`.
///
/// Needs a model with stepped generation, which `reactor/echo` is not yet: set
/// REACTOR_STEPPED_MODEL_NAME or the stepped tests are skipped.
/// REACTOR_STEPPED_INPUT_TRACK names a sendonly video track to push into;
/// without it the input test is skipped. The streamed `reactor/echo` model
/// doubles as the negative case: a runtime that does not start a stepped
/// session must fail `connect()` typed, not stream.
@Suite("09 - stepped generation")
struct SteppedGenerationTests {

    static let steppedModel = environment("REACTOR_STEPPED_MODEL_NAME")
    static let inputTrack = environment("REACTOR_STEPPED_INPUT_TRACK")

    private static func environment(_ name: String) -> String? {
        guard let raw = ProcessInfo.processInfo.environment[name], !raw.isEmpty else { return nil }
        return raw
    }

    @Test("a runtime that streams fails a stepped connect, typed")
    func streamingRuntimeFailsSteppedConnect() async throws {
        do {
            try await withConnectedReactor(generation: .stepped) { _ in
                Issue.record("expected a stepped connect to a streamed model to throw")
            }
        } catch let error as ReactorError {
            #expect(error.code == .generationModeMismatch)
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
