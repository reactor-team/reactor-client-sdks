import Foundation
import Reactor
import TestSupport
import Testing
/// Endurance: repeated full connect -> publish -> command -> disconnect ->
/// close cycles, each on a brand-new `Reactor`. Mirrors
/// `sdks/python/endurance-tests/tests/test_lifecycle_churn.py` and
/// `sdks/cpp/endurance-tests/test_lifecycle_churn.cpp`.
///
/// Manual-only for now (see ../README.md). Run with:
///
///     ENDURANCE_DURATION_SECONDS=3600 mise run test:swift:endurance-tests
///
/// A fresh client per cycle is what actually exercises the full
/// native-handle lifecycle (construction through the FFI, then `close()`
/// releasing it), unlike SessionChurnTests.swift's single long-lived
/// session, which only ever takes the per-operation paths.
extension EnduranceTests {

    @Test("Lifecycle churn: leaves no leftover threads/fds or resource growth")
    func leavesNoLeftoverResourcesOrGrowth() async throws {
        let sampler = ResourceSampler()
        let frame = MediaFixtures.solidBGRAFrame(width: 64, height: 64, color: (200, 80, 40))
        var cycle = 0
        var pending: (any Error)?

        do {
            while !sampler.deadlineReached {
                // Not `withConnectedReactor`: that helper disconnects and
                // closes only after its whole body block returns, which
                // gives no way to guarantee the client is torn down *while
                // `subscription` is still registered* — the one thing this
                // cycle means to exercise. The C++ port hit exactly this as
                // a real bug (Codex review on PR #170): a plain local
                // destroyed in reverse-declaration order unregistered its
                // handler before the client was closed, silently skipping
                // the path entirely. Explicit here, with
                // `withExtendedLifetime`, sidesteps the same class of
                // ambiguity for Swift's ARC-driven deinit timing.
                let reactor = try await makeAndConnectWithRetries()
                // A nested do/catch, not just the outer one below: once
                // connected, *any* failure from here on (a track lookup, a
                // publish, a command, an unpublish) has to still disconnect
                // and close this cycle's reactor before propagating —
                // otherwise it jumps straight to the outer catch, which
                // never touches `reactor` (declared inside this loop
                // iteration), leaving the session orphaned server-side the
                // same way an exhausted connect retry could (see
                // makeAndConnectWithRetries' own comment; caught here by
                // Codex review on the same PR).
                do {
                    // Registered and never explicitly cancelled (unlike
                    // SessionChurnTests.swift's explicit `.cancel()`) — the
                    // client's `close()` below has to clean this up on its
                    // own, on a client that may still have a frame in
                    // flight.
                    let received = AtomicFlag()
                    let mainVideo = try reactor.track("main_video")
                    let subscription = try mainVideo.onFrame { _ in received.set() }

                    let webcam = try reactor.track("webcam")
                    try await webcam.publish()
                    await pumpUntilFrameReceived(webcam, frame: frame, width: 64, height: 64) {
                        received.value
                    }
                    _ = try await reactor.sendCommand("set_intensity", ["intensity": 0.5])
                    try webcam.unpublish()

                    try? await reactor.disconnect()
                    // `withExtendedLifetime` guarantees ARC has not already
                    // deallocated `subscription` (which would cancel it) by
                    // this point despite no further use above — Swift does
                    // not promise a local's lifetime extends to the end of
                    // its lexical scope, only to its last use, so without
                    // this a sufficiently aggressive optimizer could
                    // reorder exactly the way the C++ bug did by
                    // construction.
                    withExtendedLifetime(subscription) {
                        reactor.close()
                    }
                } catch {
                    try? await reactor.disconnect()
                    reactor.close()
                    throw error
                }

                sampler.sample(cycle: cycle)
                cycle += 1
            }
        } catch {
            // A transient failure (a rate limit, a network hiccup) would
            // otherwise abort this test before the report below ever
            // prints — losing the whole run's accumulated trend to one
            // hiccup defeats a soak test more than the hiccup itself.
            pending = error
        }

        if !sampler.samples.isEmpty {
            sampler.printReport()
        }
        if let pending {
            throw pending
        }

        #expect(cycle >= 3)

        // Trend-based, not exact: real runs of the C++ port (which shares
        // this exact scenario shape) showed num_fds take one isolated step
        // on a single cycle — either during this process's very first
        // cycle (some shared resource finishing its warm-up) or on a run's
        // very last cycle (that sample catching the previous cycle's own
        // teardown still in flight) — neither a per-cycle leak, which would
        // keep climbing sample over sample rather than step once. See
        // helpers.hpp's identical comment in the C++ port for the full
        // account; Swift's own runs haven't been checked for the exact
        // same artifact, but there's no reason to expect this binding's
        // teardown timing to be any more synchronous than that one's.
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
        // Trend-based, wider median-backed window: native thread teardown
        // isn't guaranteed synchronous with `close()` the way it would be
        // for a purely reference-counted resource, so some cycles catch a
        // previous cycle's worker mid-exit.
        try assertNoSustainedGrowth(
            sampler.samples.map { Double($0.numThreads) }, name: "num_threads",
            maxGrowthRatio: 0.15, minAbsoluteDelta: 4, useMedian: true)
    }
}
