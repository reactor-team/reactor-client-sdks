import CReactorFFI
import Foundation

/// What the FFI's `userdata` pointer points at.
///
/// It holds the client **weakly**, which is the whole reason it exists. A
/// handler parked in a capture thread would otherwise keep the client — and with
/// it the native handle and the session — alive for the life of that thread. The
/// Python SDK holds its client weakly in callbacks for the same reason.
///
/// The context itself is retained manually (`Unmanaged.passRetained`) and
/// released only once `reactor_destroy` has returned 0, because until then the
/// library may still be holding this pointer. On `-1` it is never released: see
/// ``OrphanedCallbacks``.
final class CallbackContext: @unchecked Sendable {

    weak var client: Reactor?

    init(client: Reactor) {
        self.client = client
    }

    /// The context behind a `userdata` pointer, without changing its retain
    /// count — the library keeps the +1 until teardown.
    static func from(_ userdata: UnsafeMutableRawPointer?) -> CallbackContext? {
        guard let userdata else { return nil }
        return Unmanaged<CallbackContext>.fromOpaque(userdata).takeUnretainedValue()
    }
}

/// Callback contexts belonging to handles that could not be quiesced.
///
/// `reactor_destroy` returns `-1` when a callback is still executing and could
/// not be waited for — a wedged host, or destroy called from inside one of the
/// handle's own callbacks. The handle is released either way, but the callback
/// pointers must stay alive, so this keeps them alive forever.
///
/// **The leak is the correct answer.** Releasing here is a use-after-free the
/// moment that callback resumes; a permanent handful of bytes is not. The Python
/// SDK keeps a module-level list for this and never empties it.
enum OrphanedCallbacks {

    private static let contexts = Locked<[CallbackContext]>([])

    /// Keep `context` alive for the life of the process.
    static func keep(_ context: CallbackContext) {
        contexts.withLock { $0.append(context) }
    }

    /// How many contexts have been orphaned. For tests, and for anyone wondering
    /// whether this ever happens in practice.
    static var count: Int {
        contexts.withLock { $0.count }
    }
}
