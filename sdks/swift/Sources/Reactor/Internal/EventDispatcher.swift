import Foundation

/// Where control-event handlers run.
///
/// The FFI delivers control events on a thread of its own, and a handler there
/// would be touching the host's concurrency primitives from a foreign thread —
/// which for most hosts is exactly the thing not to do. So the SDK moves them:
/// onto a serial queue it owns, or onto the queue the caller supplied, and never
/// onto the thread the FFI called on.
///
/// A **serial** queue, so handlers are serialised: two never run at once, and a
/// handler can touch its own state without a lock. Control events are low-rate
/// by construction, so the throughput a concurrent queue would buy is worth less
/// than that.
///
/// Media is the deliberate exception and does not come through here. `onFrame`
/// runs inline on the FFI's delivery thread, because blocking there *is* the
/// backpressure: the FFI keeps only the newest frame while the handler runs. A
/// queue would trade a bounded drop for unbounded latency and memory.
///
/// ## What GCD spares us
///
/// The C++ SDK had to engineer around a handler that drops the last reference to
/// its client: the destructor then lands on the dispatcher's own thread, and
/// stopping that thread joins itself, which is `std::terminate`. Here the thread
/// belongs to GCD and nothing joins it, so releasing the last reference from a
/// handler is ordinary. It is still worth a test, and there is one.
struct EventDispatcher: Sendable {

    private let queue: DispatchQueue

    /// Deliver on a serial queue this dispatcher owns.
    init() {
        self.queue = DispatchQueue(label: "inc.reactor.sdk.events")
    }

    /// Deliver on the caller's queue instead — `.main` for an app that wants its
    /// handlers on the main thread.
    ///
    /// A concurrent queue is accepted and serialisation is then the caller's
    /// business; `.main` and any serial queue keep it.
    init(queue: DispatchQueue) {
        self.queue = queue
    }

    /// Run `work` later, never inline.
    ///
    /// Always async, even when called from the queue's own thread: a handler that
    /// triggers an event must not re-enter the handler beneath itself, and an
    /// inline hop would also put FFI-thread work back on the FFI thread.
    func post(_ work: @escaping @Sendable () -> Void) {
        queue.async(execute: work)
    }
}
