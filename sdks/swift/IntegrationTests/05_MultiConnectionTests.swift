import ExampleSupport
import Foundation
import Reactor
import TestSupport
import Testing

/// Scenario 05, scripted: two clients on one session, the second adopting it by
/// id. Mirrors `sdks/python/integration-tests/tests/test_multi_connection.py`.
///
/// **A session-scoped token cannot adopt a session it did not create** — a
/// joiner minting its own token gets a fresh session rather than adopting the
/// creator's (403, "this token is session-scoped"), confirmed against
/// production by the other three SDKs. So both clients here are built from one
/// minted token, not one each.
///
/// Teardown is asymmetric on purpose: only the creator ends the session; the
/// observer just leaves. Not concurrently, either — the JS suite's own
/// multi-connection test raced destroying both at once against a real bug.
@Suite("05 - multi connection")
struct MultiConnectionTests {

    /// A connected creator, and a callable that connects a joiner to it — both
    /// built from one token minted up front, the whole point of this suite.
    private func sharedPair() async throws -> (creator: Reactor, join: () async throws -> Reactor) {
        // No key to mint from in local mode, and none needed: `makeReactor`'s
        // own local branch ignores `jwt` and connects to the local
        // coordinator directly. Minting unconditionally here made both tests
        // in this suite fail during setup on every local run — found by
        // Codex review on this PR.
        let token = IntegrationConfig.local ? nil : try await IntegrationConfig.mintJWT()
        let creator = try await IntegrationConfig.makeReactor(jwt: token)
        try await pacedConnect(creator)

        func join() async throws -> Reactor {
            let joiner = try await IntegrationConfig.makeReactor(jwt: token)
            try await pacedConnect(joiner, sessionID: creator.sessionID)
            return joiner
        }

        return (creator, join)
    }

    @Test("the observer adopts the creator's session by id")
    func observerAdoptsCreatorsSession() async throws {
        let (creator, join) = try await sharedPair()
        var joiner: Reactor?
        do {
            joiner = try await join()
            #expect(joiner?.sessionID == creator.sessionID)
            #expect(joiner?.status == .ready)

            joiner?.close()
            try? await creator.disconnect()
            creator.close()
        } catch {
            joiner?.close()
            try? await creator.disconnect()
            creator.close()
            throw error
        }
    }

    @Test("the joiner observes session state the creator set before it connected")
    func joinerObservesStateCreatorSetBeforeItConnected() async throws {
        let (creator, join) = try await sharedPair()
        var joiner: Reactor?
        var pump: Task<Void, Never>?
        do {
            let webcam = try creator.track("webcam")
            try await webcam.publish()
            let color: (b: UInt8, g: UInt8, r: UInt8) = (20, 150, 60)
            // Long enough to comfortably outlast the joiner's own connect
            // handshake — a second WebRTC negotiation on top of the creator's —
            // before the frame-count wait below even starts.
            pump = pumpFrames(into: webcam, color: color, fps: 30)

            try await creator.sendCommand("set_effect", ["effect": .string("invert")])
            try await creator.sendCommand("set_intensity", ["intensity": 1.0])

            let joined = try await join()
            joiner = joined
            #expect(joined.sessionID == creator.sessionID)

            // The effect is session (model-instance) state set before the
            // joiner even connected — a fresh session defaults to "none", so
            // seeing it inverted on the joiner's own view of main_video is
            // what proves this is the same session rather than a second one
            // that happens to share an id.
            let frames = LatestFrameBox()
            let subscription = try joined.track("main_video").onFrame { frames.set($0) }
            defer { subscription.cancel() }

            try await waitUntil(timeout: .seconds(10)) { frames.value != nil }
            let frame = try #require(frames.value)
            let inverted: (b: UInt8, g: UInt8, r: UInt8) = (
                255 - color.b, 255 - color.g, 255 - color.r
            )
            MediaFixtures.assertDominantColor(frame, expected: inverted)

            pump?.cancel()
            joined.close()
            try? await creator.disconnect()
            creator.close()
        } catch {
            pump?.cancel()
            joiner?.close()
            try? await creator.disconnect()
            creator.close()
            throw error
        }
    }
}
