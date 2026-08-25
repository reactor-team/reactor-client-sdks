import CReactorFFI
import Foundation
import Testing

@testable import Reactor

@Suite("Client lifetime")
struct ReactorLifetimeTests {

    /// A client wired to a fake library, with the defaults every test wants.
    private func makeClient(
        fake: FakeLibrary,
        eventQueue: DispatchQueue? = nil,
        local: Bool = false,
        jwt: String? = nil,
        apiURL: String = Reactor.defaultAPIURL
    ) throws -> Reactor {
        try Reactor(
            model: "reactor/helios",
            jwt: jwt,
            apiURL: apiURL,
            local: local,
            eventQueue: eventQueue,
            ffi: fake.table)
    }

    // MARK: - Creation

    @Test("the synthetic audio module is pinned, and nothing can ask for another")
    func syntheticADMIsPinned() throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        // Mode 0 is synthetic. The SDK never calls reactor_create — which takes
        // the mode from an environment variable — and check-abi-parity.py keeps
        // that symbol out of the binding entirely, so this is the only way in.
        #expect(fake.createCalls.count == 1)
        #expect(fake.createCalls.first?.admMode == 0)
        #expect(fake.createCalls.first?.model == "reactor/helios")
    }

    @Test("local dev without a URL means the local coordinator")
    func localSwitchesTheDefaultURL() throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake, local: true)
        defer { client.close() }

        // Asking for local dev while leaving the URL alone must not mean the
        // production coordinator over plaintext.
        #expect(fake.createCalls.first?.apiURL == Reactor.localAPIURL)
        #expect(fake.createCalls.first?.local == 1)
    }

    @Test("an explicit URL survives local dev")
    func localKeepsAnExplicitURL() throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake, local: true, apiURL: "http://10.0.0.2:8080")
        defer { client.close() }

        #expect(fake.createCalls.first?.apiURL == "http://10.0.0.2:8080")
    }

    @Test("a library speaking another ABI is refused before anything is created")
    func abiMismatchRefusesAtInit() {
        let fake = FakeLibrary()
        fake.abiVersion = ABI.compiledAgainst + 7

        #expect(throws: ReactorError.self) { _ = try makeClient(fake: fake) }
        // Nothing was created, so there is nothing to destroy: the guard runs
        // before the handle exists, which is the point of checking at init.
        #expect(fake.createCalls.isEmpty)
        #expect(fake.destroyCount == 0)
    }

    // MARK: - Teardown

    @Test("close destroys the handle, with handlers still registered")
    func closeWithHandlersRegistered() throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)

        let statusSubscription = client.onStatus { _ in }
        let errorSubscription = client.onError { _ in }

        client.close()

        #expect(fake.destroyCount == 1)
        #expect(client.isClosed)
        // The subscriptions outlive the client and cancelling them must not
        // reach into a client that is gone.
        statusSubscription.cancel()
        errorSubscription.cancel()
    }

    @Test("close is idempotent")
    func closeTwice() throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)

        client.close()
        client.close()
        client.close()

        // Destroying twice would be a double free of the native handle.
        #expect(fake.destroyCount == 1)
    }

    @Test("releasing the last reference destroys the handle")
    func deinitDestroys() throws {
        let fake = FakeLibrary()
        do {
            let client = try makeClient(fake: fake)
            #expect(fake.destroyCount == 0)
            _ = client
        }
        #expect(fake.destroyCount == 1)
    }

    @Test("a destroy that could not quiesce keeps the callback context alive")
    func minusOneOrphansTheContext() throws {
        let fake = FakeLibrary()
        fake.destroyResult = -1

        let before = OrphanedCallbacks.count
        let client = try makeClient(fake: fake)
        client.close()

        // -1 means a callback is still executing and could not be waited for.
        // The handle is released either way, but releasing the context would be
        // a use-after-free the moment that callback resumes. Leaking it is the
        // correct answer, and this is what proves it is what happens.
        #expect(OrphanedCallbacks.count == before + 1)
    }

    @Test("a handler that drops the last reference to its client does not deadlock")
    func handlerReleasingItsClient() throws {
        let fake = FakeLibrary()
        let released = DispatchSemaphore(value: 0)

        // "On disconnected, throw the client away" is an ordinary thing to
        // write, and in C++ it lands the destructor on the dispatcher's own
        // thread, where stopping that thread joins itself. Here GCD owns the
        // thread and nothing joins it — but the client's own lock is still held
        // by whoever is delivering, so this is worth pinning.
        let holder = Locked<Reactor?>(try makeClient(fake: fake))
        let subscription = holder.withLock { $0 }?.onStatus { status in
            if status == .disconnected {
                holder.withLock { $0 = nil }
                released.signal()
            }
        }

        fake.fireStatus("disconnected")

        #expect(released.wait(timeout: .now() + 2) == .success)
        #expect(fake.destroyCount == 1)
        subscription?.cancel()
    }

    // MARK: - Events

    @Test("a status event reaches a handler, on the event queue rather than inline")
    func statusReachesHandlerOffTheCallingThread() throws {
        let fake = FakeLibrary()
        let queue = DispatchQueue(label: "test.events")
        let key = DispatchSpecificKey<Bool>()
        queue.setSpecific(key: key, value: true)

        let client = try makeClient(fake: fake, eventQueue: queue)
        defer { client.close() }

        let delivered = DispatchSemaphore(value: 0)
        let seen = Locked<(status: ReactorStatus?, onQueue: Bool)>((nil, false))
        let subscription = client.onStatus { status in
            seen.withLock { $0 = (status, DispatchQueue.getSpecific(key: key) == true) }
            delivered.signal()
        }
        defer { subscription.cancel() }

        var ranInline = true
        queue.sync { ranInline = false }  // drain: if it had run inline it is already done
        fake.fireStatus("ready")

        #expect(delivered.wait(timeout: .now() + 2) == .success)
        let result = seen.withLock { $0 }
        #expect(result.status == .ready)
        // Control events must never run on the thread the library called on:
        // that thread is the library's, and the host's concurrency primitives
        // are not the library's to touch.
        #expect(result.onQueue)
        #expect(!ranInline)
    }

    @Test("an unknown status is reported as disconnected rather than trapping")
    func unknownStatusIsSafe() throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let delivered = DispatchSemaphore(value: 0)
        let seen = Locked<ReactorStatus?>(nil)
        let subscription = client.onStatus { status in
            seen.withLock { $0 = status }
            delivered.signal()
        }
        defer { subscription.cancel() }

        fake.fireStatus("teleporting")

        #expect(delivered.wait(timeout: .now() + 2) == .success)
        #expect(seen.withLock { $0 } == .disconnected)
    }

    @Test("an error event arrives decoded, as the same type a failed call throws")
    func errorEventIsDecoded() throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let delivered = DispatchSemaphore(value: 0)
        let seen = Locked<ReactorError?>(nil)
        let subscription = client.onError { error in
            seen.withLock { $0 = error }
            delivered.signal()
        }
        defer { subscription.cancel() }

        fake.fireError(
            #"{"code":"UNAUTHORIZED","message":"token expired","recoverable":false,"status":401}"#)

        #expect(delivered.wait(timeout: .now() + 2) == .success)
        let error = seen.withLock { $0 }
        #expect(error?.code == .unauthorized)
        #expect(error?.status == 401)
        #expect(error?.message == "token expired")
    }

    @Test("a cancelled subscription stops receiving")
    func cancelledSubscriptionStopsFiring() throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let count = Locked(0)
        let delivered = DispatchSemaphore(value: 0)
        let subscription = client.onStatus { _ in
            count.withLock { $0 += 1 }
            delivered.signal()
        }

        fake.fireStatus("connecting")
        #expect(delivered.wait(timeout: .now() + 2) == .success)

        subscription.cancel()
        fake.fireStatus("ready")
        // Nothing to wait for; give the queue a chance to be wrong.
        let queueDrained = DispatchSemaphore(value: 0)
        DispatchQueue.global().asyncAfter(deadline: .now() + 0.2) { queueDrained.signal() }
        _ = queueDrained.wait(timeout: .now() + 2)

        #expect(count.withLock { $0 } == 1)
    }

    // MARK: - Reading the session

    @Test("status is read from the library, not cached from the last event")
    func statusIsReadLive() throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        #expect(client.status == .disconnected)
        fake.setStatus("ready")
        // A cached answer would still say disconnected, and would go on claiming
        // ready after a transport drop nobody registered a handler for.
        #expect(client.status == .ready)
    }

    @Test("the session id is copied and the library's string is freed")
    func sessionIDIsFreed() throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        #expect(client.sessionID == nil)
        #expect(fake.freedStrings == 0)

        fake.setSessionID("session-42")
        #expect(client.sessionID == "session-42")
        // Heap-allocated by the library and owned by this caller: not freeing it
        // leaks on every property read.
        #expect(fake.freedStrings == 1)
    }

    @Test("a closed client reports disconnected and no session")
    func closedClientReadsSafely() throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        fake.setStatus("ready")
        fake.setSessionID("session-42")

        client.close()

        // Reading through a destroyed handle would be a use-after-free, so the
        // handle is gone and these answer from what is knowable without it.
        #expect(client.status == .disconnected)
        #expect(client.sessionID == nil)
    }

    // MARK: - Operations

    @Test("connect resolves when the library completes it")
    func connectResolves() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        async let connected: Void = client.connect()
        try await waitForPendingCall(fake)
        fake.completeLastCall(ok: true)

        try await connected
        #expect(fake.connectCalls == 1)
    }

    @Test("a failed operation throws the typed error the payload describes")
    func failedOperationThrows() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        let connecting = Task { try await client.connect() }
        try await waitForPendingCall(fake)
        fake.completeLastCall(
            ok: false, result: nil,
            error: #"{"code":"UNAUTHORIZED","message":"nope","operation":"connect"}"#)

        await #expect(throws: ReactorError.self) { try await connecting.value }
    }

    @Test("disconnect and reconnect reach their own FFI calls")
    func disconnectAndReconnect() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        defer { client.close() }

        async let disconnected: Void = client.disconnect()
        try await waitForPendingCall(fake)
        fake.completeLastCall(ok: true)
        try await disconnected

        async let reconnected: Void = client.reconnect()
        try await waitForPendingCall(fake)
        fake.completeLastCall(ok: true)
        try await reconnected

        // Two calls with identical signatures, and a binding that swapped them
        // would end the session when asked to keep it.
        #expect(fake.disconnectCalls == 1)
        #expect(fake.reconnectCalls == 1)
    }

    @Test("closing with an operation in flight settles the caller instead of hanging")
    func closeSettlesPendingOperations() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)

        // The awaiting caller is watched through a flag rather than by awaiting
        // it, and that shape is deliberate. **A continuation that is never
        // resumed ignores cancellation**, so neither `Task.cancel()` nor
        // swift-testing's `.timeLimit` can bound this: regressing the fix and
        // awaiting the task hangs the whole run indefinitely — I watched it pass
        // ten minutes. Watching a flag instead means the regression *fails* this
        // test in seconds, which is what a test is for.
        let settled = Locked(false)
        let outcome = Locked<(any Error)?>(nil)
        Task {
            do { try await client.connect() } catch { outcome.withLock { $0 = error } }
            settled.withLock { $0 = true }
        }

        #expect(await waitUntil { fake.hasPendingCompletion }, "the SDK never called the library")

        // The completion is never fired. Without teardown settling it, this
        // caller waits for the life of the process — the exact failure the C++
        // stack shipped and had to fix.
        client.close()

        #expect(
            await waitUntil { settled.withLock { $0 } },
            "close must settle an operation the library will never answer")
        #expect((outcome.withLock { $0 } as? ReactorError)?.code == .aborted)
    }

    @Test("an operation on a closed client is refused, not silently dropped")
    func operationAfterCloseThrows() async throws {
        let fake = FakeLibrary()
        let client = try makeClient(fake: fake)
        client.close()

        do {
            try await client.connect()
            Issue.record("expected a throw")
        } catch let error as ReactorError {
            #expect(error.code == .invalidState)
            #expect(fake.connectCalls == 0)
        }
    }

    /// Wait until the fake has an operation recorded, so a test can answer it.
    ///
    /// Polling rather than sleeping a fixed amount: the call is made by another
    /// task, and a fixed sleep is either flaky or slow.
    private func waitForPendingCall(
        _ fake: FakeLibrary, timeout: Duration = .seconds(2)
    ) async throws {
        let deadline = ContinuousClock.now.advanced(by: timeout)
        while !fake.hasPendingCompletion {
            if ContinuousClock.now > deadline {
                Issue.record("the SDK never called the library")
                return
            }
            try await Task.sleep(for: .milliseconds(5))
        }
    }
}
