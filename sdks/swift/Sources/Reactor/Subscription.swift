import Foundation

/// A registered event handler, and the only way to remove it.
///
/// There is no `off(event:handler:)`, and there cannot be: two Swift closures
/// cannot be compared, so a token is the only honest removal. The C++ SDK
/// reached the same conclusion for the same reason.
///
/// It cancels when it is released, the way Combine's `AnyCancellable` does — so
/// a subscription stored in a view model dies with the view model, and nothing
/// keeps delivering into an object that is gone. That also means **the result
/// has to be kept**:
///
/// ```swift
/// // Wrong: cancelled immediately, because nothing holds the token.
/// _ = reactor.onStatus { print($0) }
///
/// // Right.
/// let statusSubscription = reactor.onStatus { print($0) }
/// ```
///
/// The registration methods are deliberately **not** `@discardableResult`, so
/// the compiler warns about the first form rather than leaving a handler that
/// silently never fires.
public final class Subscription: Sendable {

    private let onCancel: @Sendable () -> Void
    private let cancelled = Locked(false)

    init(onCancel: @escaping @Sendable () -> Void) {
        self.onCancel = onCancel
    }

    deinit {
        cancel()
    }

    /// Stop delivering to this handler.
    ///
    /// Idempotent, and safe from any thread. A handler already running when this
    /// is called runs to completion; it is the next event that does not arrive.
    public func cancel() {
        let wasCancelled = cancelled.withLock { value -> Bool in
            defer { value = true }
            return value
        }
        guard !wasCancelled else { return }
        onCancel()
    }
}

/// A value behind a lock.
///
/// `NSLock` rather than an actor: this is touched from the FFI's threads, and an
/// actor would make every read `await` — which a `deinit` and a `@convention(c)`
/// trampoline cannot do.
final class Locked<Value>: @unchecked Sendable {

    private var value: Value
    private let lock = NSLock()

    init(_ value: Value) {
        self.value = value
    }

    func withLock<Result>(_ body: (inout Value) throws -> Result) rethrows -> Result {
        lock.lock()
        defer { lock.unlock() }
        return try body(&value)
    }
}
