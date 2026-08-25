import CReactorFFI
import Foundation

/// A Reactor client.
///
/// ```swift
/// let reactor = try Reactor(model: "reactor/helios", jwt: token)
/// let statusSubscription = reactor.onStatus { print("status:", $0) }
/// try await reactor.connect()
/// ```
///
/// The object model matches the other Reactor SDKs: the client owns the
/// connection, and media lives on ``Track`` rather than here.
///
/// ## Threading
///
/// Control-event handlers run on a serial queue the SDK owns — never on the
/// thread the library called on — so a handler can touch its own state without a
/// lock. Pass `eventQueue: .main` to have them run on the main queue instead.
///
/// `async` calls resume on the library's own completion thread, deliberately not
/// through that queue: `await reactor.connect()` from the main actor would
/// otherwise be waiting for a queue that is waiting for it.
public final class Reactor: @unchecked Sendable {

    /// The coordinator this SDK talks to unless told otherwise.
    public static let defaultAPIURL = "https://api.reactor.inc"

    /// Where a coordinator run locally listens.
    public static let localAPIURL = "http://localhost:8080"

    private let ffi: FFI
    private let dispatcher: EventDispatcher
    private let state = Locked(State())

    /// Everything mutable, in one place, behind one lock.
    private struct State {
        var handle: OpaquePointer?
        var context: CallbackContext?
        var closed = false
        var statusHandlers: [UUID: @Sendable (ReactorStatus) -> Void] = [:]
        var errorHandlers: [UUID: @Sendable (ReactorError) -> Void] = [:]
        var pending: [ObjectIdentifier: PendingCompletion] = [:]
    }

    // MARK: - Lifecycle

    /// Create a client for `model`, named `owner/name`.
    ///
    /// A bare name resolves under `reactor/`, so it works by luck of ownership
    /// and answers 403 for anyone else's model. Write the owner.
    ///
    /// Nothing connects here: the handle exists, and ``connect(sessionID:connectionID:)``
    /// is what creates or adopts a session.
    ///
    /// - Throws: ``ReactorError`` with ``ReactorError/Code/versionMismatch`` when
    ///   the loaded `libreactor_ffi` speaks a different ABI than this SDK was
    ///   built against — the one failure that has to be caught before any call,
    ///   because past it the stack is corrupted rather than an error reported.
    public convenience init(
        model: String,
        jwt: String? = nil,
        apiURL: String = Reactor.defaultAPIURL,
        local: Bool = false,
        eventQueue: DispatchQueue? = nil
    ) throws {
        try self.init(
            model: model,
            jwt: jwt,
            apiURL: apiURL,
            local: local,
            eventQueue: eventQueue,
            ffi: .system
        )
    }

    /// The initialiser the tests use, with the library injected.
    init(
        model: String,
        jwt: String?,
        apiURL: String,
        local: Bool,
        eventQueue: DispatchQueue?,
        ffi: FFI
    ) throws {
        try ABI.check(ffi)

        self.ffi = ffi
        self.dispatcher = eventQueue.map(EventDispatcher.init(queue:)) ?? EventDispatcher()

        // Mirrors the Python SDK: asking for local dev without naming a URL
        // means the local coordinator, not the production one over plaintext.
        let resolvedURL = (local && apiURL == Reactor.defaultAPIURL) ? Reactor.localAPIURL : apiURL

        let context = CallbackContext(client: self)
        // Retained by hand, and released only once reactor_destroy answers 0.
        // Until then the library may still be holding this pointer.
        let userdata = Unmanaged.passRetained(context).toOpaque()

        var callbacks = ReactorCallbacks()
        callbacks.on_status = statusTrampoline
        callbacks.on_error = errorTrampoline
        callbacks.userdata = userdata

        // The FFI copies the struct's contents at create time (see create_impl in
        // crates/reactor-ffi/src/lib.rs), so it does not have to outlive this call.
        let handle = withUnsafePointer(to: &callbacks) { callbacksPointer in
            // Mode 0 is synthetic, always. Nothing here reaches reactor_create,
            // which would take the audio device mode from an environment
            // variable — and a library whose audience is apps and scripts must
            // never let an env var put a live microphone on the wire because a
            // model happened to declare a sendonly audio track.
            ffi.createWithADM(
                resolvedURL, model, jwt, local ? 1 : 0, callbacksPointer, 0)
        }

        guard let handle else {
            // Documented as allocation failure only, so there is nothing to
            // retry and nothing the caller did wrong.
            Unmanaged<CallbackContext>.fromOpaque(userdata).release()
            throw ReactorError(
                .internalError, "libreactor_ffi could not create a client", operation: "create")
        }

        state.withLock {
            $0.handle = handle
            $0.context = context
        }
    }

    deinit {
        close()
    }

    /// Destroy the client and release the native handle.
    ///
    /// Idempotent. Called for you when the last reference goes away; call it
    /// yourself when you want the session's resources released at a known point.
    ///
    /// This does **not** end the session server-side — ``disconnect()`` does. A
    /// creator that goes away without disconnecting orphans the session, and the
    /// next run cannot start until it clears.
    public func close() {
        // The handle comes out from under the lock, and destroy is called
        // without it. reactor_destroy blocks until every callback in flight has
        // returned, and those callbacks take this same lock — holding it across
        // the call would be a deadlock with the library's own thread.
        let (handle, context, pending) = state.withLock {
            state -> (OpaquePointer?, CallbackContext?, [PendingCompletion]) in
            guard !state.closed else { return (nil, nil, []) }
            state.closed = true
            let handle = state.handle
            let context = state.context
            let pending = Array(state.pending.values)
            state.handle = nil
            state.context = nil
            state.pending = [:]
            state.statusHandlers = [:]
            state.errorHandlers = [:]
            return (handle, context, pending)
        }

        guard let handle else { return }

        // Settle first, destroy second. An operation the library will now never
        // answer leaves its caller awaiting for the life of the process, and a
        // caller told "aborted" can at least decide what to do.
        for operation in pending {
            operation.abandon(
                ReactorError(
                    .aborted,
                    "the client was closed before \(operation.operation) completed",
                    operation: operation.operation))
        }

        let quiesced = ffi.destroy(handle)

        guard let context else { return }
        if quiesced == 0 {
            // No callback is running and none will start: releasing is safe.
            Unmanaged.passUnretained(context).release()
        } else {
            // A callback is still executing and could not be waited for. The
            // pointers must stay alive; leaking them is correct and freeing them
            // is a use-after-free.
            OrphanedCallbacks.keep(context)
        }
    }

    // MARK: - Connection

    /// Create a session, or adopt one by id, and establish the transport.
    ///
    /// - Parameters:
    ///   - sessionID: an existing session to adopt. `nil` creates a new one.
    ///   - connectionID: a connection id a backend already registered for this
    ///     session. `nil` registers a new one, which is what almost every caller
    ///     wants.
    public func connect(sessionID: String? = nil, connectionID: UInt32? = nil) async throws {
        _ = try await perform("connect") { handle, completion, userdata in
            withOptionalCString(sessionID) { sessionPointer in
                if let connectionID {
                    withUnsafePointer(to: connectionID) { connectionPointer in
                        self.ffi.connect(
                            handle, sessionPointer, connectionPointer, completion, userdata)
                    }
                } else {
                    self.ffi.connect(handle, sessionPointer, nil, completion, userdata)
                }
            }
        }
    }

    /// End the session server-side.
    ///
    /// Not recoverable: to keep the session across a transient failure, or to
    /// cycle a live connection deliberately, use ``reconnect()`` instead.
    public func disconnect() async throws {
        _ = try await perform("disconnect") { handle, completion, userdata in
            self.ffi.disconnect(handle, completion, userdata)
        }
    }

    /// Reconnect using the existing session.
    ///
    /// Tears down the live connection first if there is one, without ending the
    /// session server-side. Fails when there is no session to reconnect to.
    ///
    /// Note that a reconnect resumes recvonly tracks and nothing else: anything
    /// this client published before it is not published after it.
    public func reconnect() async throws {
        _ = try await perform("reconnect") { handle, completion, userdata in
            self.ffi.reconnect(handle, completion, userdata)
        }
    }

    // MARK: - Reading the session

    /// Where the client is in its connection lifecycle.
    ///
    /// Read from the library each time rather than cached from the last event: a
    /// cached answer goes on claiming `ready` after a transport drop that no
    /// handler was registered for.
    public var status: ReactorStatus {
        let handle = state.withLock { $0.handle }
        guard let handle else { return .disconnected }
        // A static string. Copied, never freed.
        guard let text = String(borrowing: ffi.status(handle)) else { return .disconnected }
        return ReactorStatus(ffiValue: text)
    }

    /// The current session's id, or `nil` when there is no session.
    public var sessionID: String? {
        let handle = state.withLock { $0.handle }
        guard let handle else { return nil }
        // Heap-allocated by the library and owned by this caller.
        return String(takingOwnership: ffi.sessionID(handle), freeing: ffi.freeString)
    }

    /// Whether ``close()`` has run.
    public var isClosed: Bool {
        state.withLock { $0.closed }
    }

    // MARK: - Events

    /// Register a handler for status changes.
    ///
    /// Keep the returned ``Subscription``: it cancels when released, so a handler
    /// whose token is discarded stops firing immediately.
    public func onStatus(_ handler: @escaping @Sendable (ReactorStatus) -> Void) -> Subscription {
        let id = UUID()
        state.withLock { $0.statusHandlers[id] = handler }
        return Subscription { [weak self] in
            self?.state.withLock { $0.statusHandlers[id] = nil }
        }
    }

    /// Register a handler for errors the session reports.
    ///
    /// The same ``ReactorError`` a failed call throws — one type, so a 401
    /// reported here and a 401 thrown by ``connect(sessionID:connectionID:)``
    /// cannot disagree about what happened.
    public func onError(_ handler: @escaping @Sendable (ReactorError) -> Void) -> Subscription {
        let id = UUID()
        state.withLock { $0.errorHandlers[id] = handler }
        return Subscription { [weak self] in
            self?.state.withLock { $0.errorHandlers[id] = nil }
        }
    }

    /// Status changes, as a stream, for callers who prefer `for await`.
    ///
    /// Buffers the newest value only: a consumer that falls behind on a
    /// low-rate event wants the current status, not a backlog of old ones.
    public var statusUpdates: AsyncStream<ReactorStatus> {
        AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
            let subscription = onStatus { continuation.yield($0) }
            continuation.onTermination = { _ in subscription.cancel() }
        }
    }

    /// Errors, as a stream.
    ///
    /// Buffers a short backlog rather than the newest only: unlike a status,
    /// each error is its own event and dropping one loses information.
    public var errors: AsyncStream<ReactorError> {
        AsyncStream(bufferingPolicy: .bufferingNewest(16)) { continuation in
            let subscription = onError { continuation.yield($0) }
            continuation.onTermination = { _ in subscription.cancel() }
        }
    }

    // MARK: - Internals reached from the trampolines

    /// Deliver a status the library reported. Called on an FFI thread.
    func deliver(status text: String) {
        let handlers = state.withLock { Array($0.statusHandlers.values) }
        guard !handlers.isEmpty else { return }
        let status = ReactorStatus(ffiValue: text)
        dispatcher.post {
            for handler in handlers { handler(status) }
        }
    }

    /// Deliver an error the library reported. Called on an FFI thread.
    func deliver(errorPayload payload: String?) {
        let handlers = state.withLock { Array($0.errorHandlers.values) }
        guard !handlers.isEmpty else { return }
        // Decoded here, on the library's thread, while the payload is still
        // borrowed — the handlers run later and get a value.
        let error = ReactorError.decode(payload: payload)
        dispatcher.post {
            for handler in handlers { handler(error) }
        }
    }

    /// Drop a completion that has been answered.
    func forget(_ operation: PendingCompletion) {
        state.withLock { $0.pending[ObjectIdentifier(operation)] = nil }
    }

    // MARK: - Calling the library

    /// Run one async FFI operation and wait for its completion.
    private func perform(
        _ operation: String,
        _ call: @escaping (OpaquePointer, reactor_completion_fn, UnsafeMutableRawPointer) -> Void
    ) async throws -> String? {
        let pending = PendingCompletion(operation: operation, owner: self)

        let handle = state.withLock { state -> OpaquePointer? in
            guard !state.closed, let handle = state.handle else { return nil }
            state.pending[ObjectIdentifier(pending)] = pending
            return handle
        }

        guard let handle else {
            throw ReactorError(
                .invalidState,
                "the client is closed, so \(operation) cannot run. Create a new Reactor.",
                operation: operation)
        }

        return try await withCheckedThrowingContinuation { continuation in
            // Attached before the call, or a completion that fires immediately
            // would find nothing to settle.
            pending.attach(continuation)
            let userdata = Unmanaged.passRetained(pending).toOpaque()
            call(handle, completionTrampoline, userdata)
        }
    }
}

// MARK: - Trampolines

// The C functions the library calls. File-scope `let`s because they capture
// nothing, which is what lets them be `@convention(c)` pointers at all — and
// everything they need arrives through `userdata`, which holds the client
// **weakly**.

private let statusTrampoline: reactor_on_status_fn = { status, userdata in
    // Copied before returning: the string is valid only for this call.
    guard let text = String(borrowing: status) else { return }
    CallbackContext.from(userdata)?.client?.deliver(status: text)
}

private let errorTrampoline: reactor_on_error_fn = { errorJSON, userdata in
    let payload = String(borrowing: errorJSON)
    CallbackContext.from(userdata)?.client?.deliver(errorPayload: payload)
}

/// Call `body` with a C string for `value`, or with `nil`.
///
/// `withCString` has no optional form, and the alternative — building a
/// `strdup`ed copy — would be an allocation to free on every path out.
private func withOptionalCString<Result>(
    _ value: String?,
    _ body: (UnsafePointer<CChar>?) -> Result
) -> Result {
    guard let value else { return body(nil) }
    return value.withCString(body)
}
