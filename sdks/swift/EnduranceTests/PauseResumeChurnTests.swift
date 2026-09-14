import EnduranceReporting
import Foundation
import Reactor
import TestSupport
import Testing

/// Endurance: one long-lived session, repeated pause/resume churn on a
/// recvonly track — no frames, no commands, no reconnect. Mirrors
/// `sdks/python/endurance-tests/tests/test_pause_resume_churn.py`.
///
/// Isolates `Track.pause()`/`resume()` as its own scenario rather than
/// folding it into SessionChurnTests.swift's already-mixed publish/frame/
/// command loop — a leak specific to the pause/resume path (e.g. the native
/// session-side pause state not fully clearing) should read as its own
/// signal.
///
/// Run with:
///
///     ENDURANCE_DURATION_SECONDS=3600 mise run test:swift:endurance-tests
extension EnduranceTests {

    // The recvonly track reactor/echo always declares — the same one
    // LifecycleChurnTests.swift/SessionChurnTests.swift already subscribe to
    // for onFrame, chosen here for the same reason: no need to invent a name.
    private static let pauseResumeChurnTrackName = "main_video"

    private static let pauseResumeChurnDescription =
        "One long-lived session: repeated pause/resume on a recvonly track. No frames, no "
        + "commands, no reconnect — isolates Track.pause()/resume() as its own signal."

    @Test("Pause/resume churn: has no sustained resource growth")
    func pauseResumeChurnHasNoSustainedGrowth() async throws {
        let sampler = ResourceSampler()
        let live = LiveReporter(
            testName: "pause-resume-churn", durationS: EnduranceConfig.durationSeconds)
        var iteration = 0
        var metrics: [MetricResult] = []
        let startedAt = nowISO()
        var pending: (any Error)?
        var invariantViolated = false

        do {
            try await withConnectedReactor { reactor in
                // Resolves the track's direction from the session's already-
                // declared capabilities — a local, synchronous lookup, no
                // network round trip, so this is safe to do once before the
                // loop rather than on every iteration.
                let track = try reactor.track(Self.pauseResumeChurnTrackName)

                while !sampler.deadlineReached {
                    try await track.pause()
                    try await track.resume()

                    // Exact, not trend-based: resume() above already
                    // returned, so the session should report nothing paused
                    // on every single iteration — `pausedTracks` is read
                    // fresh from the session, not cached, so a leftover
                    // entry here is a real leftover pause state, not a stale
                    // local flag.
                    let paused = reactor.pausedTracks
                    if !paused.isEmpty {
                        metrics.append(
                            MetricResult(
                                name: "paused_tracks_after_resume", start: 0,
                                end: Double(paused.count), change: Double(paused.count),
                                unit: "count", status: "fail",
                                detail:
                                    "\(paused.sorted()) still reported paused after resume() on "
                                    + "iteration \(iteration)",
                                threshold: "always empty", firstBadCycle: iteration))
                        invariantViolated = true
                        break
                    }

                    sampler.sample(cycle: iteration)
                    live.update(sampler.samples)
                    iteration += 1
                }
            }
        } catch {
            pending = error
        }

        if pending == nil && !invariantViolated {
            if iteration >= 3 {
                do {
                    metrics = try standardResourceMetrics(sampler.samples)
                } catch {
                    pending = error
                }
            } else {
                pending = EnduranceAssertionError(
                    "only completed \(iteration) iteration(s) — raise "
                        + "ENDURANCE_DURATION_SECONDS to get enough data for a trend")
            }
        }

        try finishAndCheck(
            testName: "pause-resume-churn", sdk: "Swift",
            description: Self.pauseResumeChurnDescription, sdkVersion: ReactorSDK.version,
            durationS: EnduranceConfig.durationSeconds, startedAt: startedAt,
            samples: sampler.samples, live: live, metrics: metrics, iterations: iteration,
            runError: pending.map { "\(type(of: $0)): \($0)" })
        if let pending {
            throw pending
        }
    }
}
