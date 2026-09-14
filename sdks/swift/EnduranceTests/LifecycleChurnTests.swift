import EnduranceReporting
import Foundation
import Reactor
import TestSupport
import Testing
/// Endurance: repeated full connect -> publish -> command -> disconnect ->
/// close cycles, each on a brand-new `Reactor`. Mirrors
/// `sdks/python/endurance-tests/tests/test_lifecycle_churn.py` and
/// `sdks/cpp/endurance-tests/test_lifecycle_churn.cpp`.
///
/// Run with:
///
///     ENDURANCE_DURATION_SECONDS=3600 mise run test:swift:endurance-tests
///
/// A fresh client per cycle is what actually exercises the full
/// native-handle lifecycle (construction through the FFI, then `close()`
/// releasing it), unlike SessionChurnTests.swift's single long-lived
/// session, which only ever takes the per-operation paths.
extension EnduranceTests {

    private static let lifecycleChurnDescription =
        "Fresh Reactor per cycle: connect, publish, push frames, send a command, disconnect, "
        + "close. Exercises the whole native-handle lifecycle."

    @Test("Lifecycle churn: leaves no leftover threads/fds or resource growth")
    func leavesNoLeftoverResourcesOrGrowth() async throws {
        let sampler = ResourceSampler()
        let live = LiveReporter(
            testName: "lifecycle-churn", durationS: EnduranceConfig.durationSeconds)
        let frame = MediaFixtures.solidBGRAFrame(width: 64, height: 64, color: (200, 80, 40))
        var cycle = 0
        // Counts a disconnect() that failed on an otherwise-normal cycle —
        // the one thing this scenario retries-and-swallows rather than
        // failing the whole run over. Mirrors Python's identical counter;
        // every other scenario in this suite leaves `errors` `nil` (see
        // RunResult.errors' own doc) since nothing in their loops does this.
        var errors = 0
        var metrics: [MetricResult] = []
        let startedAt = nowISO()
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

                    // Counted, not just swallowed: `close()` below still
                    // runs regardless (this is a best-effort disconnect,
                    // not worth failing a multi-hour run over), but a
                    // disconnect() that's failing on some cycles is itself
                    // a signal worth surfacing in the report rather than a
                    // hardcoded-looking "Errors 0" that never moves.
                    do {
                        try await reactor.disconnect()
                    } catch {
                        errors += 1
                    }
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
                live.update(sampler.samples, errors: errors)
                cycle += 1
            }
        } catch {
            // A transient failure (a rate limit, a network hiccup) would
            // otherwise abort this test before the report below ever
            // writes — losing the whole run's accumulated trend to one
            // hiccup defeats a soak test more than the hiccup itself.
            pending = error
        }

        if pending == nil {
            if cycle >= 3 {
                // baseline-zero would be the Python equivalent's
                // `live_clients_baseline_zero` — no such signal exists on
                // this binding (see ../README.md's "known scope gap").
                // fdsExact: socket/fd teardown proved synchronous with
                // disconnect()/close() returning, so num_fds gets the
                // strict "never past its starting value" check instead of
                // a trend.
                do {
                    metrics = try standardResourceMetrics(sampler.samples, fdsExact: true)
                } catch {
                    pending = error
                }
            } else {
                pending = EnduranceAssertionError(
                    "only completed \(cycle) cycle(s) — raise ENDURANCE_DURATION_SECONDS to "
                        + "get enough data for a trend")
            }
        }

        try finishAndCheck(
            testName: "lifecycle-churn", sdk: "Swift", description: Self.lifecycleChurnDescription,
            sdkVersion: ReactorSDK.version, durationS: EnduranceConfig.durationSeconds,
            startedAt: startedAt, samples: sampler.samples, live: live, metrics: metrics,
            iterations: cycle, errors: errors,
            runError: pending.map { "\(type(of: $0)): \($0)" })
        // `finishAndCheck` only throws for a "FAIL" status (a metric that
        // crossed its threshold) — an unhandled error from the loop itself
        // (status "ERROR") is recorded in the report but not re-thrown by
        // it, so this test still has to fail on its own account.
        if let pending {
            throw pending
        }
    }
}
