import Foundation
import Reactor
import TestSupport
import Testing
/// Endurance: one long-lived session, repeated
/// publish/pushFrame/command/unpublish cycles against it. Mirrors
/// `sdks/python/endurance-tests/tests/test_session_churn.py` and
/// `sdks/cpp/endurance-tests/test_session_churn.cpp`.
///
/// Manual-only for now (see ../README.md). Run with:
///
///     ENDURANCE_DURATION_SECONDS=3600 mise run test:swift:endurance-tests
///
/// No reconnect overhead here (unlike LifecycleChurnTests.swift), so this
/// packs far more iterations into the same wall-clock window — the scenario
/// for per-operation leaks (frame buffers, `Track`/`Subscription` objects)
/// that a coarser connect/disconnect cycle wouldn't surface as clearly.
extension EnduranceTests {

    // reactor/echo declares a fixed set of track names (see echo_model.py) —
    // there is no such thing as a fresh per-iteration name to publish,
    // unlike a client handle, which is why this scenario republishes the
    // same "webcam" slot every iteration instead of
    // LifecycleChurnTests.swift's fresh-object-per-cycle shape.
    private static let sessionChurnTrackName = "webcam"

    @Test("Session churn: has no sustained resource growth")
    func hasNoSustainedResourceGrowth() async throws {
        let sampler = ResourceSampler()
        let frame = MediaFixtures.solidBGRAFrame(width: 64, height: 64, color: (30, 150, 90))
        var iteration = 0
        var pending: (any Error)?

        // `withConnectedReactor` connects once via plain `pacedConnect`, not
        // `makeAndConnectWithRetries` — unlike LifecycleChurnTests.swift,
        // this scenario only ever connects once, at the very start, so a
        // transient coordinator-side blip has one narrow window to land in
        // rather than hundreds of chances over the run.
        do {
            try await withConnectedReactor { reactor in
                while !sampler.deadlineReached {
                    // Registered and cancelled every iteration — unlike
                    // LifecycleChurnTests.swift, which registers once per
                    // client and lets `close()` clean it up, this is the
                    // scenario for repeated subscribe/unsubscribe on a
                    // *long-lived* track: does the onFrame/cancel() pair
                    // leak across many cycles on the same session, not just
                    // survive one client's teardown.
                    let received = AtomicFlag()
                    let mainVideo = try reactor.track("main_video")
                    let subscription = try mainVideo.onFrame { _ in received.set() }

                    let track = try reactor.track(Self.sessionChurnTrackName)
                    try await track.publish()
                    await pumpUntilFrameReceived(track, frame: frame, width: 64, height: 64) {
                        received.value
                    }
                    _ = try await reactor.sendCommand("set_effect", ["effect": "invert"])
                    try track.unpublish()
                    // Explicit, not left to `subscription`'s own deinit:
                    // mirrors the Python/C++ suites' explicit
                    // off_frame()/remove() call, and matters for the same
                    // reason — leaving it to ARC would cancel it only when
                    // the last reference goes away, at the end of this
                    // whole test, not once per iteration, which is exactly
                    // the repeated-teardown path this scenario means to
                    // exercise.
                    subscription.cancel()

                    sampler.sample(cycle: iteration)
                    iteration += 1
                }
            }
        } catch {
            // Same reasoning as LifecycleChurnTests.swift's own catch: a
            // transient failure mid-run shouldn't cost the whole
            // accumulated trend, which is the one thing a soak test
            // actually exists to show.
            pending = error
        }

        if !sampler.samples.isEmpty {
            sampler.printReport()
        }
        if let pending {
            throw pending
        }

        #expect(iteration >= 3)

        // Trend-based, not exact — see LifecycleChurnTests.swift's own
        // comment on its identical num_fds check for why.
        try assertNoSustainedGrowth(
            sampler.samples.map { Double($0.numFDs) }, name: "num_fds", maxGrowthRatio: 0.15,
            minAbsoluteDelta: 3, useMedian: true)
        try assertNoSustainedGrowth(
            sampler.samples.map { Double($0.rssBytes) }, name: "rss_bytes", maxGrowthRatio: 0.15,
            minAbsoluteDelta: 5_000_000)
        try assertNoSustainedGrowth(
            cpuDeltas(sampler.samples), name: "cpu_s_per_cycle", maxGrowthRatio: 0.5,
            minAbsoluteDelta: 0.05)
        try assertNoSustainedGrowth(
            sampler.samples.map(\.cpuPercent), name: "cpu_percent", maxGrowthRatio: 0.5,
            minAbsoluteDelta: 5)
        // Same reasoning as LifecycleChurnTests.swift's own identical call:
        // one long-lived session can legitimately grow a thread or two
        // during warm-up and then plateau, so this is trend-based (with the
        // wider median-backed window), not a strict "never past the first
        // sample" check.
        try assertNoSustainedGrowth(
            sampler.samples.map { Double($0.numThreads) }, name: "num_threads",
            maxGrowthRatio: 0.15, minAbsoluteDelta: 4, useMedian: true)
    }
}
