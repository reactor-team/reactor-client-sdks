import ExampleSupport
import Foundation
import Reactor
import TestSupport
import Testing

/// Scenario 01, scripted: connect, send the model's first command, read the
/// reply, count frames. Runs against `reactor/echo` — see
/// `IntegrationConfig.defaultModel`'s doc for why, not the `reactor/helios`
/// REA-5586's hand-run examples used.
@Suite("01 - connect and receive")
struct ConnectAndReceiveTests {

    @Test("get_status round-trips, and main_video mirrors a published webcam")
    func getStatusRoundTripsAndMainVideoMirrorsWebcam() async throws {
        try await withConnectedReactor { reactor in
            // The model's first command: get_status is a `ModelMessage`-returning
            // handler, so it comes back with a correlated reply rather than a
            // bare acknowledgement — see REA-5973.
            let reply = try await reactor.sendCommand("get_status")
            #expect(reply != nil)

            let webcam = try reactor.track("webcam")
            try await webcam.publish()
            let pump = pumpFrames(into: webcam)
            defer { pump.cancel() }

            let counter = FrameCounter(label: "swift-integration-01")
            let subscription = try reactor.track("main_video").onFrame { counter.submit($0) }
            defer { subscription.cancel() }

            try await waitUntil(timeout: .seconds(10)) { counter.frames > 0 }
            #expect(counter.frames > 0)
        }
    }

    @Test("nothing arrives on main_video before anything is pushed to webcam")
    func nothingArrivesBeforeAnythingIsPushed() async throws {
        try await withConnectedReactor { reactor in
            let counter = FrameCounter(label: "swift-integration-01-no-push")
            let subscription = try reactor.track("main_video").onFrame { counter.submit($0) }
            defer { subscription.cancel() }

            // echo skips a tick entirely with nothing to read — a real negative,
            // not a race with a model that generates on its own.
            try? await Task.sleep(for: .seconds(2))
            #expect(counter.frames == 0)
        }
    }
}
