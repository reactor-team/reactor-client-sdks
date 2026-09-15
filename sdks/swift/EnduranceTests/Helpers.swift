import Foundation
import Reactor
import TestSupport

/// The FFI-dependent half of this suite's plumbing: connect resilience and
/// frame pumping, both of which need a real `Reactor`/`Track`. Mirrors
/// `sdks/python/endurance-tests/helpers.py` and
/// `sdks/cpp/endurance-tests/helpers.hpp`/`.cpp`.
///
/// Duration handling, resource sampling (`ResourceSampler`), the trend/count
/// assertions, and the reporting layer all live in the `EnduranceReporting`
/// target instead (`../EnduranceReporting/Trends.swift`,
/// `../EnduranceReporting/Report.swift`) — none of that needs `Reactor` or
/// this SDK's native library, so it lives on the FFI-free side of the split
/// and can be unit-tested directly (`../EnduranceReportingTests/`). See
/// this directory's own README.md for the fuller rationale behind every
/// signal, and `EnduranceReporting`'s own file doc comments for why the
/// split is where it is.

// MARK: - connect resilience

/// Creates a `Reactor` (`IntegrationConfig.makeReactor`) and connects it
/// (`pacedConnect`), retrying the *whole* attempt up to `maxAttempts` times
/// with a fixed delay, before letting the last failure propagate.
///
/// Written after a real CI run lost ~20 minutes of a Python endurance
/// run's accumulated trend to one coordinator-side blip — the
/// token-exchange endpoint answering a single, isolated 503, confirmed
/// via Grafana as a one-off rather than a real outage. An
/// endurance run is long and unattended specifically so a human doesn't
/// have to babysit it; failing the whole run over a handful of seconds of
/// transient unavailability defeats that.
///
/// Wraps *construction*, not just `pacedConnect`: unlike the Python/C++
/// SDKs, where an API key is exchanged for a JWT lazily inside `connect()`,
/// this SDK's `Reactor(model:apiKey:...)` initializer does that exchange
/// itself (see `Auth.swift`) — so the failure this exists to absorb can be
/// thrown by `IntegrationConfig.makeReactor` before `pacedConnect` is ever
/// reached, not just by it. Retrying a *non*-transient failure (a
/// genuinely invalid key) just costs a few extra fast attempts before still
/// failing with the same error.
func makeAndConnectWithRetries(
    model: String = IntegrationConfig.defaultModel, maxAttempts: Int = 5,
    retryDelay: Duration = .seconds(5)
) async throws -> Reactor {
    for attempt in 1...maxAttempts {
        // Declared outside the `do`, not just a `let` inside it: if
        // `pacedConnect` throws *after* the coordinator already created a
        // session (a documented case in `pacedConnect`'s own doc — a
        // session that polled 20 times without ever reaching `ready`), the
        // reactor still has to be caught here to disconnect it before this
        // attempt is abandoned. `Reactor.close()` explicitly does not end
        // the session server-side, and letting `reactor` just fall out of
        // scope would only ever reach `deinit`'s `close()` — never
        // `disconnect()` — orphaning a session that then blocks the next
        // attempt/run from starting (caught by Codex review on PR #171).
        var reactor: Reactor?
        do {
            let created = try await IntegrationConfig.makeReactor(model: model)
            reactor = created
            try await pacedConnect(created)
            return created
        } catch {
            if let reactor {
                try? await reactor.disconnect()
                reactor.close()
            }
            guard attempt < maxAttempts else { throw error }
            try? await Task.sleep(for: retryDelay)
        }
    }
    fatalError("unreachable: the loop above always returns or throws on its last attempt")
}

/// Pushes solid-colour frames into `track` at ~30fps until `isReceived`
/// reports true (flipped by an `onFrame` handler registered on the
/// *receiving* track) or `timeout` elapses. Mirrors
/// `pump_until_frame_received` in the Python/C++ suites — best-effort and
/// never throws, for the same reason: this suite is about churn/leak
/// detection over many cycles, not frame-delivery correctness
/// (`IntegrationTests` already covers that with a real timeout-and-fail
/// `waitUntil`).
@discardableResult
func pumpUntilFrameReceived(
    _ track: Track, frame: Data, width: UInt32, height: UInt32, timeout: Duration = .seconds(2),
    fps: Double = 30, isReceived: () -> Bool
) async -> Bool {
    let deadline = ContinuousClock.now.advanced(by: timeout)
    while !isReceived() && ContinuousClock.now < deadline {
        try? track.pushFrame(frame, width: width, height: height)
        try? await Task.sleep(for: .seconds(1 / fps))
    }
    return isReceived()
}

/// The latest boolean a callback has flipped, safe to read from another
/// thread — the endurance suites' equivalent of
/// `02_UploadImageTests.swift`'s `LatestFrameBox`, generalised to a flag
/// rather than a frame: an `onFrame` handler runs `@Sendable` on the FFI's
/// own delivery thread, so a plain `var` a test reads back is a data race,
/// not just a stale answer.
final class AtomicFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var flag = false

    var value: Bool {
        lock.lock()
        defer { lock.unlock() }
        return flag
    }

    func set() {
        lock.lock()
        defer { lock.unlock() }
        flag = true
    }
}
