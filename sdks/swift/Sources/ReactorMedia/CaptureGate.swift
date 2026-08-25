import Foundation

/// Whether a capture callback should still deliver what it just captured.
///
/// This exists because **closing a capture device waits for its own callback**,
/// and that turns the obvious locking into a deadlock.
///
/// `AVCaptureSession.stopRunning()` and `AVAudioEngine.stop()` both block until
/// the delegate or tap currently running has returned. So a callback that takes
/// the same lock `stop()` is holding waits for the thread that is waiting for it.
/// The C++ SDK hit exactly this: its speaker could hold a mutex across device
/// teardown because its render callback took no lock, and its microphone could
/// not, because closing a capture device waits for the capture callback. **The
/// symmetric fix deadlocks.**
///
/// The rule this type enforces:
///
/// - the flag is read and written under a lock, because two threads touch it;
/// - **the lock is never held while the device is being torn down** — `stop()`
///   flips the flag, releases the lock, and only then stops the device;
/// - a callback that runs while stopping sees `false` and **drops** what it has,
///   rather than pushing into a track that is going away.
///
/// It is a separate type so that discipline is testable without a camera: the
/// tests drive it from two threads and assert that stopping never blocks on a
/// delivery and that nothing is delivered afterwards.
public final class CaptureGate: @unchecked Sendable {

    private let lock = NSLock()
    private var open = false
    private var deliveries = 0
    private var dropped = 0

    /// A closed gate.
    public init() {}

    /// Whether captures are being delivered.
    public var isOpen: Bool {
        lock.lock()
        defer { lock.unlock() }
        return open
    }

    /// How many captures were delivered.
    public var delivered: Int {
        lock.lock()
        defer { lock.unlock() }
        return deliveries
    }

    /// How many captures were dropped because the gate was closed.
    ///
    /// A number worth reading: it is what "the camera is running but the model
    /// receives nothing" looks like from in here.
    public var droppedCaptures: Int {
        lock.lock()
        defer { lock.unlock() }
        return dropped
    }

    /// Open the gate. Call this **before** starting the device, so a callback
    /// that fires immediately is not dropped.
    public func open(_ isOpen: Bool = true) {
        lock.lock()
        open = isOpen
        lock.unlock()
    }

    /// Close the gate, and return without waiting for anything.
    ///
    /// The caller stops the device *after* this returns, with no lock held —
    /// which is the whole point.
    public func close() {
        lock.lock()
        open = false
        lock.unlock()
    }

    /// Run `deliver` if the gate is open, counting either way.
    ///
    /// The lock is taken to read the flag and released before `deliver` runs:
    /// holding it across the delivery would put the callback's work inside the
    /// same critical section teardown wants, which is how this deadlocks.
    public func withDelivery(_ deliver: () -> Void) {
        lock.lock()
        let isOpen = open
        if isOpen { deliveries += 1 } else { dropped += 1 }
        lock.unlock()

        guard isOpen else { return }
        deliver()
    }
}

/// A number two threads touch.
///
/// The media module cannot reach the SDK's internal `Locked`, and a counter is
/// not worth a dependency — but it is worth a lock: these are read from an app's
/// thread and written from a capture callback.
final class Counter: @unchecked Sendable {

    private let lock = NSLock()
    private var value = 0

    var current: Int {
        lock.lock()
        defer { lock.unlock() }
        return value
    }

    func increment() {
        lock.lock()
        value += 1
        lock.unlock()
    }
}
