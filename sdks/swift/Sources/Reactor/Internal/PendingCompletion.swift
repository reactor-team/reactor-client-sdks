import CReactorFFI
import Foundation

/// One async operation waiting on `reactor_completion_fn`.
///
/// A continuation resolves **exactly once**, and resolving it twice is a
/// `fatalError` that takes the process with it. Two things could reach this
/// object — the FFI's completion, and teardown settling what is still
/// outstanding — so the settling is guarded by a lock and the first one wins.
///
/// The other half of that rule is ordering: the payload is decoded and validated
/// *before* the continuation is claimed. A conversion that throws after the claim
/// has nowhere left to report, which in C++ is a broken promise and in Swift is a
/// trap.
final class PendingCompletion: @unchecked Sendable {

    /// Which call this is, for the error a teardown reports.
    let operation: String

    private let state = Locked<CheckedContinuation<String?, any Error>?>(nil)

    /// Whether the FFI still holds the `passRetained` reference to this object
    /// that was handed to it as `userdata`.
    ///
    /// That reference is balanced by exactly one of two things: the completion
    /// trampoline consuming it, or teardown taking it back once `reactor_destroy`
    /// has answered that no completion will arrive. Whoever claims it first owns
    /// it and the other finds nothing — the same "first one wins" rule the
    /// continuation itself follows, for the same reason.
    ///
    /// Without it, closing with an operation in flight leaks this object, its
    /// continuation and everything that continuation retains, because destroy
    /// deliberately suppresses the completion that would have balanced it.
    private let retainedByLibrary = Locked(false)

    private weak var owner: Reactor?

    init(operation: String, owner: Reactor) {
        self.operation = operation
        self.owner = owner
    }

    /// Hand over the continuation to settle. Called once, before the FFI call.
    func attach(_ continuation: CheckedContinuation<String?, any Error>) {
        state.withLock { $0 = continuation }
    }

    /// Settle from the FFI's completion callback.
    ///
    /// `resultJSON` and `errorJSON` are borrowed for the duration of this call,
    /// so both are copied before anything else happens.
    func complete(ok: Int32, resultJSON: UnsafePointer<CChar>?, errorJSON: UnsafePointer<CChar>?) {
        // Decode first, claim second.
        let outcome: Result<String?, any Error> =
            ok == 1
            ? .success(String(borrowing: resultJSON))
            : .failure(ReactorError.decode(payload: String(borrowing: errorJSON)))

        owner?.forget(self)
        settle(outcome)
    }

    /// Settle from teardown, for an operation the library will never answer.
    func abandon(_ error: ReactorError) {
        settle(.failure(error))
    }

    /// Record that the FFI was handed a retained reference to this object.
    func retainedByFFI() {
        retainedByLibrary.withLock { $0 = true }
    }

    /// Take ownership of that reference, if it is still outstanding.
    ///
    /// Answers `true` to exactly one caller; whoever gets it must release it.
    func claimRetainedReference() -> Bool {
        retainedByLibrary.withLock { outstanding in
            defer { outstanding = false }
            return outstanding
        }
    }

    private func settle(_ outcome: Result<String?, any Error>) {
        // Taking the continuation out under the lock is what makes "exactly
        // once" true: whoever finds it settles it, and everyone after finds nil.
        let continuation = state.withLock { stored -> CheckedContinuation<String?, any Error>? in
            defer { stored = nil }
            return stored
        }
        guard let continuation else { return }
        continuation.resume(with: outcome)
    }
}

/// The C function the FFI calls when an operation finishes.
///
/// A file-scope `let` rather than a closure built per call: it captures nothing,
/// which is what lets it be a `@convention(c)` pointer at all.
let completionTrampoline: reactor_completion_fn = { ok, resultJSON, errorJSON, userdata in
    guard let userdata else { return }
    let unmanaged = Unmanaged<PendingCompletion>.fromOpaque(userdata)
    let pending = unmanaged.takeUnretainedValue()

    // Claimed rather than consumed outright, because teardown can get here
    // first: reactor_destroy suppresses the completions of operations still in
    // flight, and having promised they will never arrive, close() takes the
    // reference back itself. Exactly one of the two releases it.
    //
    // Reaching this object at all is safe on the same promise the callback
    // context relies on: close() only releases after destroy has answered 0,
    // which means no callback is running and none will start.
    guard pending.claimRetainedReference() else { return }
    defer { unmanaged.release() }

    pending.complete(ok: ok, resultJSON: resultJSON, errorJSON: errorJSON)
}
