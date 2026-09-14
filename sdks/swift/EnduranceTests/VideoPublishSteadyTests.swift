import EnduranceReporting
import Foundation
import Reactor
import TestSupport
import Testing

/// Endurance: publish a single video track once and hold it, continuously
/// pushing frames at a steady rate for the whole run — no pause, no
/// unpublish, no reconnect, no other operation in between. Mirrors
/// `sdks/python/endurance-tests/tests/test_video_publish_steady.py`.
///
/// Every other scenario in this suite is *churn* — repeatedly doing and
/// undoing something to surface a leak in that specific operation. This one
/// is the opposite shape on purpose: a real long call looks like "publish
/// once, stream for a long time," not "publish and unpublish thousands of
/// times a minute" — so this is the scenario for a leak that only shows up
/// under sustained steady-state streaming (an encoder buffer that grows
/// with elapsed time or frame count rather than with churn count, say),
/// which the churn scenarios would not be positioned to catch even if they
/// ran forever.
///
/// Run with:
///
///     ENDURANCE_DURATION_SECONDS=3600 mise run test:swift:endurance-tests
extension EnduranceTests {

    private static let videoPublishSteadyTrackName = "webcam"
    private static let videoPublishSteadyFPS = 30
    private static let videoPublishSteadyWidth = 64
    private static let videoPublishSteadyHeight = 64

    private static let videoPublishSteadyDescription =
        "One video track, published once and held for the whole run: continuous "
        + "push_frame() at \(videoPublishSteadyFPS) fps, no pause, no unpublish, no reconnect — "
        + "the steady-state shape a real long call actually takes, as opposed to the other "
        + "scenarios' repeated setup/teardown."

    @Test("Video publish steady: continuous streaming leaves no sustained growth")
    func videoPublishSteadyHasNoSustainedGrowth() async throws {
        let sampler = ResourceSampler()
        let live = LiveReporter(
            testName: "video-publish-steady", durationS: EnduranceConfig.durationSeconds)
        let frame = MediaFixtures.solidBGRAFrame(
            width: Self.videoPublishSteadyWidth, height: Self.videoPublishSteadyHeight,
            color: (90, 140, 200))
        var iteration = 0
        var metrics: [MetricResult] = []
        let startedAt = nowISO()
        var pending: (any Error)?

        do {
            try await withConnectedReactor { reactor in
                let track = try reactor.track(Self.videoPublishSteadyTrackName)
                try await track.publish()
                // Not left to the fixture's own teardown alone: unpublish
                // explicitly before `withConnectedReactor`'s disconnect/
                // close runs, the same order Python's `finally: track.
                // unpublish()` gives — a `defer` here (rather than a
                // separate `catch`) runs on every exit path from this
                // closure, thrown error or normal return alike.
                defer { try? track.unpublish() }

                while !sampler.deadlineReached {
                    // One second of continuous streaming, then one sample —
                    // a cycle here is "how much streaming happened between
                    // samples," not a discrete operation the way the other
                    // scenarios' cycles are. Keeps the sample count (and so
                    // the JSON report size) bounded to roughly one per
                    // second of ENDURANCE_DURATION_SECONDS, rather than one
                    // per push_frame() call.
                    for _ in 0..<Self.videoPublishSteadyFPS {
                        try? track.pushFrame(
                            frame, width: UInt32(Self.videoPublishSteadyWidth),
                            height: UInt32(Self.videoPublishSteadyHeight))
                        try await Task.sleep(
                            for: .seconds(1.0 / Double(Self.videoPublishSteadyFPS)))
                    }

                    sampler.sample(cycle: iteration)
                    live.update(sampler.samples)
                    iteration += 1
                }
            }
        } catch {
            pending = error
        }

        if pending == nil {
            if iteration >= 3 {
                do {
                    metrics = try standardResourceMetrics(sampler.samples)
                } catch {
                    pending = error
                }
            } else {
                pending = EnduranceAssertionError(
                    "only completed \(iteration) second(s) of streaming — raise "
                        + "ENDURANCE_DURATION_SECONDS to get enough data for a trend")
            }
        }

        try finishAndCheck(
            testName: "video-publish-steady", sdk: "Swift",
            description: Self.videoPublishSteadyDescription, sdkVersion: ReactorSDK.version,
            durationS: EnduranceConfig.durationSeconds, startedAt: startedAt,
            samples: sampler.samples, live: live, metrics: metrics, iterations: iteration,
            runError: pending.map { "\(type(of: $0)): \($0)" })
        if let pending {
            throw pending
        }
    }
}
