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

                // The frame travels encoded both ways when both ends share a
                // codec (H.264 by default), so the inverted pixel comes back
                // close, not exact.
                try expectInverted(result, tolerance: 8)
            }
        } catch let error as ReactorError where !connected {
            #expect(error.code == .generationModeMismatch || error.code == .sessionTerminal)
        }
    }

    /// The same echo round trip as above, with the step frames' codec chosen:
    /// H.264 both ways, or encoding turned off so every frame travels raw. The
    /// client still pushes BGRA and receives RGB either way. An echo or a
    /// runtime that does not step is accepted as above.
    @Test(
        "a stepped session carries frames in the codec it chose",
        arguments: ["h264", "encoding-off"])
    func steppedSessionCarriesFramesInTheCodecItChose(variant: String) async throws {
        let codecs: [StepCodec] = variant == "h264" ? [.h264] : []
        let expected = variant == "h264" ? "h264" : "raw"
        let tolerance = expected == "raw" ? 0 : 8

        let reactor = try await IntegrationConfig.makeReactor(generation: .stepped)
        // Before connecting: it applies to the next connection.
        try reactor.setCodecPreference(codecs)
        var connected = false
        do {
            do {
                try await pacedConnect(reactor)
            } catch let error as ReactorError {
                #expect(error.code == .generationModeMismatch || error.code == .sessionTerminal)
                reactor.close()
                return
            }
            connected = true

            let webcam = try reactor.track("webcam")
            try await webcam.publish()
            let encodings = reactor.stepFrameEncodings
            if expected != "raw" && encodings.values.allSatisfy({ $0 == "raw" }) {
                // A runtime from before encoded step frames answers without
                // them, so every track travels raw. Accepted, as an echo that
                // does not step is, until a runtime with them is deployed.
                try? await reactor.disconnect()
                reactor.close()
                return
            }
            #expect(encodings["main_video"] == expected, "\(encodings)")
            // A track the session carries raw may be left out of the map.
            #expect((encodings["webcam"] ?? "raw") == expected, "\(encodings)")
            if expected == "raw" {
                #expect(encodings.values.allSatisfy { $0 == "raw" }, "\(encodings)")
            }

            // RGB (10, 20, 30), as BGRA.
            let pixels = MediaFixtures.solidBGRAFrame(width: 64, height: 64, color: (30, 20, 10))
            try webcam.pushFrame(pixels, width: 64, height: 64)
            let result = try await reactor.step(state: ["effect": "invert"])
            #expect(result.error == nil)
            try expectInverted(result, tolerance: tolerance)

            // The next step's frames continue the same stream.
            try webcam.pushFrame(pixels, width: 64, height: 64)
            let again = try await reactor.step()
            #expect(again.step == result.step + 1)
            try expectInverted(again, tolerance: tolerance)
        } catch {
            if connected { try? await reactor.disconnect() }
            reactor.close()
            throw error
        }
        try? await reactor.disconnect()
        reactor.close()
    }

    /// `main_video` carries one 64x64 frame whose centre is RGB (10, 20, 30)
    /// inverted, to within `tolerance` per channel.
    private func expectInverted(_ result: StepResult, tolerance: Int) throws {
        let video = try #require(result.tracks.first { $0.name == "main_video" })
        try #require(video.frames.count == 1)
        let frame = video.frames[0]
        try #require(frame.shape == [64, 64, 3])
        let centre = (32 * 64 + 32) * 3
        let pixel = Array(frame.data)[centre..<centre + 3].map(Int.init)
        let distance = zip(pixel, [245, 235, 225]).map { abs($0 - $1) }.max() ?? .max
        #expect(distance <= tolerance, "centre pixel \(pixel)")
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

            // A frame over the 64 MiB a chunked data channel carries. A runtime
            // that takes pushed frames gets each one as it is pushed, so this
            // one is dropped at the push, raised as an error event, and the step
            // goes through. An older runtime takes them only with the next step,
            // which this frame then fails.
            try source.pushFrame(
                MediaFixtures.solidBGRAFrame(width: 5000, height: 5000, color: (0, 0, 255)),
                width: 5000, height: 5000)
            do {
                let after = try await reactor.step()
                #expect(after.step > result.step)
            } catch let error as ReactorError {
                #expect(error.code == .messageTooLarge)
            }
        }
    }
}
