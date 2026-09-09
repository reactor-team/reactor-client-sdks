import Foundation
import Reactor

/// Shared fixtures for the Swift SDK integration suite.
///
/// Real `Reactor` clients — real FFI, real WebRTC — against a real model in
/// production (`reactor/echo` by default). Nothing here is mocked; that's the
/// point. Mirrors `sdks/python/integration-tests/conftest.py` and
/// `sdks/cpp/integration-tests/fixtures.hpp` — same env var names, same pacing
/// interval, same shape — so pointing one suite at a local runtime instead of
/// production reads the same way as pointing any other.
///
/// Lives outside `sdks/swift/Tests/`, and is never picked up by `swift.sh test`
/// (the mocked-`FakeLibrary` unit suite) — only `swift.sh integration-tests`
/// filters it in. Same separation `sdks/js/integration-tests/` and
/// `sdks/python/integration-tests/` keep from their own unit suites.
enum IntegrationConfig {

    /// Same names as `sdks/js/integration-tests/harness/src/config.ts` and
    /// `sdks/python/integration-tests/conftest.py`.
    static let apiURL = value("REACTOR_API_URL") ?? Reactor.defaultAPIURL
    static let local = value("REACTOR_LOCAL") == "1"

    /// A separate key from `REACTOR_API_KEY` (which the examples use): the
    /// integration suite's own quota-bearing key, so a contributor running the
    /// examples by hand never shares a budget with CI.
    private static let apiKeyEnv = "INTEGRATION_TESTS_REACTOR_API_KEY"

    /// `reactor/echo` — the model every other SDK's integration suite runs
    /// against (`sdks/python/integration-tests/conftest.py`'s own default), and
    /// what this suite uses too, deliberately **not** the `reactor/helios` /
    /// `xmax/x2` pair REA-5586's hand-run examples used.
    ///
    /// Two reasons this is the right model for an automated, CI-gating suite
    /// and helios is not: echo's effects (`set_effect`, `set_overlay_image`)
    /// give exact, assertable pixel output on demand, where helios's generative
    /// output can only be sampled and eyeballed — and echo is provisioned for
    /// the request volume a suite that runs on every PR produces, where helios
    /// is a shared, capacity-limited model this suite hit "no available
    /// servers" against in its first draft. It declares one sendonly track,
    /// `webcam`, and mirrors it onto a recvonly `main_video` — so, unlike
    /// helios, **nothing arrives on `main_video` until something has been
    /// pushed into `webcam`** (`echo_model.py`'s `run()` skips a tick with
    /// nothing to read).
    static let defaultModel = "reactor/echo"

    /// The API key this suite runs with, or a clear failure naming the fix —
    /// never a crash, so one test's missing key fails that test rather than the
    /// whole binary. Unused when `local` is set.
    static func requiredAPIKey() throws -> String {
        guard let key = value(apiKeyEnv) else {
            throw IntegrationTestSetupError(
                "\(apiKeyEnv) is required unless REACTOR_LOCAL=1 — see "
                    + "sdks/swift/IntegrationTests/README.md")
        }
        return key
    }

    /// A `Reactor` for this suite's configuration, not yet connected.
    ///
    /// Pass `jwt` to opt out of the key exchange and hand the client an already
    /// minted token instead — required for session adoption (scenario 05): the
    /// coordinator only accepts the token that *created* a session for a second
    /// connection to adopt it by id, not a fresh one minted per client, so a
    /// joiner needs the creator's own token, not its own key.
    static func makeReactor(
        model: String = defaultModel, jwt: String? = nil
    ) async throws
        -> Reactor
    {
        if local {
            return try Reactor(model: model, jwt: nil, apiURL: apiURL, local: true, eventQueue: nil)
        }
        if let jwt {
            return try Reactor(
                model: model, jwt: jwt, apiURL: apiURL, local: false, eventQueue: nil)
        }
        let key = try requiredAPIKey()
        // Scoped to this model: a token that can reach everything the key can is
        // fine server-to-server and wrong everywhere else — same reasoning as
        // ExampleSupport's connectedClient(model:).
        return try await Reactor(
            model: model, apiKey: key, apiURL: apiURL,
            options: .init(models: [model]), local: false, eventQueue: nil)
    }

    /// Mint one token from this suite's key, for a caller that needs to hand the
    /// *same* token to more than one `Reactor` (session adoption).
    static func mintJWT(model: String = defaultModel) async throws -> String {
        try await Reactor.fetchJWT(
            apiKey: requiredAPIKey(), apiURL: apiURL,
            options: .init(models: [model]), local: local)
    }

    private static func value(_ name: String) -> String? {
        guard let raw = ProcessInfo.processInfo.environment[name], !raw.isEmpty else { return nil }
        return raw
    }
}

/// Something this suite's setup could not do — a missing key, mostly. Distinct
/// from `ReactorError`, which is a *session* refusing something.
struct IntegrationTestSetupError: Error, CustomStringConvertible {
    let description: String
    init(_ description: String) { self.description = description }
}

// MARK: - Session-creation pacing
//
// reactor/echo's session-creation quota is enforced per API key across the
// whole suite, not per test — confirmed against prod on the other three SDKs:
// a suite run in isolation still tripped it, because a burst of a few tests'
// worth of connects lands within the same window. 0.7s (~86/min) is the
// interval Python's and C++'s suites converged on against the 100/min quota (now
// 500/min, see ci.yml) — real margin without being needlessly conservative.
// Retry-on-429 stays a second line of defense at the caller, not implemented
// here.
actor SessionPacer {
    static let shared = SessionPacer()

    private let interval: Duration = .milliseconds(700)
    private var lastConnectAt: ContinuousClock.Instant?

    func waitTurn() async {
        let now = ContinuousClock.now
        if let last = lastConnectAt {
            let elapsed = last.duration(to: now)
            if elapsed < interval {
                try? await Task.sleep(for: interval - elapsed)
            }
        }
        lastConnectAt = ContinuousClock.now
    }
}

/// `try await reactor.connect(...)`, paced against every other call to this
/// function in the process — not just other calls on `reactor` itself.
///
/// `reconnect()` deliberately isn't routed through here, same reasoning as the
/// Python suite's `paced_connect`: it reuses the existing session rather than
/// creating a new one, so it isn't counted against this quota either.
///
/// Retries up to three times on a rate limit or a transient "no available
/// capacity" from the platform, waiting the server's own `retryAfterMS` (or 2s,
/// absent one) — belt and suspenders, not the primary defense: pacing is what
/// keeps this suite's own average under quota, but the quota is shared with
/// every other suite and every other tenant hitting the same model, and a
/// burst or a momentary capacity dip landing from outside this process is real
/// and observed in practice (`current == limit` on a suite that alone could not
/// have produced that many connects; "no available servers" moments later with
/// no quota involved at all). Python gets this from `pytest-rerunfailures`, JS
/// for free from Playwright's `retries: 1` — this is Swift's equivalent,
/// written by hand because `swift test` has no rerun-on-failure flag.
func pacedConnect(
    _ reactor: Reactor, sessionID: String? = nil, connectionID: UInt32? = nil
) async throws {
    let maxAttempts = 4
    for attempt in 1...maxAttempts {
        await SessionPacer.shared.waitTurn()
        do {
            try await reactor.connect(sessionID: sessionID, connectionID: connectionID)
            return
        } catch let error as ReactorError where error.code == .rateLimited {
            guard attempt < maxAttempts else { throw error }
            let backoff: Duration = error.retryAfterMS.map { .milliseconds($0) } ?? .seconds(2)
            try await Task.sleep(for: backoff)
        }
    }
}

// MARK: - A connected client, torn down no matter how the test ends

/// Create a `Reactor`, connect it (paced), run `body`, then disconnect and
/// close it — whether `body` throws or not.
///
/// The equivalent of the Python suite's `reactor` fixture and the JS harness's
/// `afterEach` teardown, written as a wrapper rather than a fixture: swift-
/// testing has no yield-style fixture, and a plain `defer` cannot run the
/// `async` disconnect. Teardown goes here rather than in each test for the same
/// reason the `sdk-from-ffi` skill puts it in a `finally` in every example: a
/// creator that goes away without disconnecting orphans the session, and the
/// next run cannot start until it clears.
func withConnectedReactor<Result>(
    model: String = IntegrationConfig.defaultModel,
    jwt: String? = nil,
    sessionID: String? = nil,
    connectionID: UInt32? = nil,
    _ body: (Reactor) async throws -> Result
) async throws -> Result {
    let reactor = try await IntegrationConfig.makeReactor(model: model, jwt: jwt)
    do {
        try await pacedConnect(reactor, sessionID: sessionID, connectionID: connectionID)
        let result = try await body(reactor)
        try? await reactor.disconnect()
        reactor.close()
        return result
    } catch {
        try? await reactor.disconnect()
        reactor.close()
        throw error
    }
}

/// Poll `predicate` until it's true or `timeout` elapses.
///
/// Used for anything fed from `onFrame` callbacks, which run inline
/// on the library's media delivery thread rather than through the event queue —
/// the same reason Python's `wait_until` polls a plain counter instead of using
/// an `asyncio.Event`.
func waitUntil(
    timeout: Duration = .seconds(10), interval: Duration = .milliseconds(100),
    _ predicate: () -> Bool
) async throws {
    let deadline = ContinuousClock.now.advanced(by: timeout)
    while !predicate() {
        if ContinuousClock.now >= deadline {
            throw IntegrationTestSetupError("condition not met within \(timeout)")
        }
        try await Task.sleep(for: interval)
    }
}
