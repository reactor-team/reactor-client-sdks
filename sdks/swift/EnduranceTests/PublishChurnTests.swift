import EnduranceReporting
import Foundation
import Reactor
import TestSupport
import Testing

/// Endurance: one long-lived session, repeated publish/unpublish churn on a
/// sendonly track slot — no frames, no commands, no reconnect. Mirrors
/// `sdks/python/endurance-tests/tests/test_publish_churn.py`.
///
/// Isolates the publish/unpublish slot-activation path itself
/// (`Track.publish()`/`unpublish()`) from SessionChurnTests.swift's broader
/// mix (which also pushes frames and sends a command each iteration): a leak
/// specific to *this* pair should read as its own clear signal instead of
/// being folded into a trend several different operations are all
/// contributing to. Also packs in far more iterations per minute than
/// session-churn, since there is no per-cycle frame-pump wait or command
/// round trip.
///
/// Run with:
///
///     ENDURANCE_DURATION_SECONDS=3600 mise run test:swift:endurance-tests
extension EnduranceTests {

    // reactor/echo declares a fixed set of track names (see echo_model.py) —
    // same reasoning as SessionChurnTests.swift's own track name: republish
    // the same slot every iteration rather than invent a fresh per-iteration
    // name.
    private static let publishChurnTrackName = "webcam"

    private static let publishChurnDescription =
        "One long-lived session: repeated publish/unpublish on a single sendonly slot. No "
        + "frames, no commands, no reconnect — isolates the publish/unpublish path from "
        + "session-churn's broader mix."

    @Test("Publish churn: has no sustained resource growth")
    func publishChurnHasNoSustainedGrowth() async throws {
        let sampler = ResourceSampler()
        let live = LiveReporter(
            testName: "publish-churn", durationS: EnduranceConfig.durationSeconds)
        var iteration = 0
        var metrics: [MetricResult] = []
        let startedAt = nowISO()
        var pending: (any Error)?

        // Set on the first in-loop invariant violation below — breaks the
        // loop (further iterations' trend data would be unreliable against
        // a corrupted invariant) without letting the violation surface as
        // an unhandled error (which would report as "ERROR" — "not a
        // detected leak", the wrong read for a check that *is* one). Same
        // reasoning as the Python suite's identical flag.
        var invariantViolated = false

        do {
            try await withConnectedReactor { reactor in
                let track = try reactor.track(Self.publishChurnTrackName)

                while !sampler.deadlineReached {
                    try await track.publish()
                    try track.unpublish()

                    // Exact, not trend-based: unpublish() above already
                    // returned, so the slot should report not-published on
                    // every single iteration — a leftover `true` here means
                    // the SDK-side flag didn't clear even though the cycle
                    // otherwise completed normally.
                    if track.published {
                        metrics.append(
                            MetricResult(
                                name: "track_published_after_unpublish", start: 0, end: 1,
                                change: 1, unit: "count", status: "fail",
                                detail:
                                    "'\(Self.publishChurnTrackName)' still reports published=true "
                                    + "after unpublish() on iteration \(iteration)",
                                threshold: "always false", firstBadCycle: iteration))
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
                    // The fixture's one client is connected for the whole
                    // test, so unlike lifecycle-churn there is no
                    // baseline-zero equivalent to ask for here.
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
            testName: "publish-churn", sdk: "Swift", description: Self.publishChurnDescription,
            sdkVersion: ReactorSDK.version, durationS: EnduranceConfig.durationSeconds,
            startedAt: startedAt, samples: sampler.samples, live: live, metrics: metrics,
            iterations: iteration, runError: pending.map { "\(type(of: $0)): \($0)" })
        if let pending {
            throw pending
        }
    }
}
