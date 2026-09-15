import ExampleSupport
import Foundation
import Reactor
import TestSupport
import Testing

/// Scenario 03, scripted: pause and resume a track. Mirrors
/// `sdks/python/integration-tests/tests/test_tracks_and_frames.py`'s
/// `test_pause_stops_delivery_and_resume_restarts_it`, including its grace
/// window: `pause()` resolves once the request is acknowledged locally, but
/// pausing is transport-level — the signal still has to reach whatever is
/// sending, and a frame or two already in flight when it does still lands.
/// Confirmed empirically there too: without the grace window, a few frames
/// from before the pause took effect get counted as "during pause". The
/// zero-tolerance window starts only after it.
@Suite("03 - pause and resume")
struct PauseAndResumeTests {

    @Test("nothing is delivered while paused; resuming restarts delivery")
    func nothingDeliveredWhilePausedResumeRestartsDelivery() async throws {
        try await withConnectedReactor { reactor in
            let webcam = try reactor.track("webcam")
            try await webcam.publish()
            let pump = pumpFrames(into: webcam)
            defer { pump.cancel() }

            let output = try reactor.track("main_video")
            let counter = FrameCounter(label: "swift-integration-03")
            let subscription = try output.onFrame { counter.submit($0) }
            defer { subscription.cancel() }

            try await waitUntil(timeout: .seconds(8)) { counter.frames > 0 }

            try await output.pause()
            #expect(output.paused)
            try await Task.sleep(for: .milliseconds(500))  // grace window, see above
            let baseline = counter.frames
            try await Task.sleep(for: .seconds(1.5))
            let duringPause = counter.frames - baseline

            try await output.resume()
            #expect(!output.paused)
            try await waitUntil(timeout: .seconds(5)) { counter.frames > baseline }

            #expect(duringPause == 0, "\(duringPause) frames arrived while main_video was paused")
        }
    }
}
