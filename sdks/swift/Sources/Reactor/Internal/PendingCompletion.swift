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
    // takeRetainedValue balances the passRetained at the call site. The FFI
    // promises exactly one completion per call, so this is the one place the
    // reference is consumed.
    let pending = Unmanaged<PendingCompletion>.fromOpaque(userdata).takeRetainedValue()
    pending.complete(ok: ok, resultJSON: resultJSON, errorJSON: errorJSON)
}
