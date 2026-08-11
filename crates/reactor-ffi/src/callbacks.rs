//! The boundary where this library calls back into the host, and the two
//! properties of that boundary that everything here exists for.
//!
//! **A host callback stops being callable at a moment the host chooses.** A ctypes
//! `CFUNCTYPE` object is freed when Python drops its last reference; a `cgo.Handle`
//! stops resolving when Go deletes it; a JNI `GlobalRef` dies on
//! `DeleteGlobalRef`. Calling a pointer after that point is a jump into freed
//! memory, and no binding can protect itself, because none of them can observe
//! whether a callback is running or about to start. [`CallbackGate`] is that
//! observation.
//!
//! [`retire`](CallbackGate::retire) closes the gate and reports what it achieved.
//! On [`Quiescence::Complete`] nothing is executing and nothing will start, so the
//! host may release its pointers. On [`Quiescence::Incomplete`] a callback is still
//! running and cannot be waited for — the host is wedged, or the caller *is* the
//! callback — and the host must keep the pointers alive. Leaking them is the correct
//! answer there; freeing them is the use-after-free this module exists to prevent.
//!
//! **A host callback can block for an unbounded time.** The first thing a ctypes
//! trampoline does is take the GIL, which it will not get until whichever Python
//! thread holds it lets go — possibly after a long stretch of pure-Python work. A
//! JNI upcall attaches to the JVM, making that thread a GC root the runtime
//! suspends at safepoints. Neither is a wait a libwebrtc media thread can afford:
//! its decode and network threads have deadlines, and missing them shows up as
//! dropped frames, audio glitches and ICE timeouts. A tokio worker parked the same
//! way starves every other task on it.
//!
//! So no media thread and no tokio worker ever calls a host callback. They hand the
//! payload to a [`HostThread`], which exists to be blocked.

use std::collections::{HashMap, VecDeque};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::thread::{self, JoinHandle, ThreadId};
use std::time::Duration;

use log::{debug, warn};

/// How long [`CallbackGate::retire`] waits for an in-flight callback to return
/// before giving up on it.
///
/// The wait normally ends in microseconds. It can only stall if a callback is
/// blocked inside the host — a ctypes trampoline waiting on the GIL, say — and the
/// realistic way for that to become permanent is calling destroy during CPython
/// finalisation, when the interpreter holds the GIL and never hands it back to a
/// foreign thread. Blocking forever there would hang the process at exit, so the
/// wait is bounded and loud instead. Bindings avoid the situation entirely by
/// tearing down while the interpreter is still running.
const QUIESCE_TIMEOUT: Duration = Duration::from_secs(5);

/// What [`CallbackGate::retire`] achieved.
///
/// The distinction is the whole safety story: a host may only release its callback
/// pointers on [`Quiescence::Complete`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Quiescence {
    /// No callback is running and none will start. Pointers are safe to release.
    Complete,
    /// A callback is still executing and cannot be waited for — either the host is
    /// blocked, or the caller *is* the callback. Pointers must be kept alive; leak
    /// them rather than free them.
    Incomplete,
}

/// Decides whether host callbacks may still run, and lets teardown wait until none
/// are running.
pub struct CallbackGate {
    open: AtomicBool,
    state: Mutex<GateState>,
    idle: Condvar,
}

#[derive(Default)]
struct GateState {
    /// Callbacks in flight, counted per thread.
    ///
    /// Per gate rather than per thread-local, so a callback belonging to one client
    /// cannot make another client's `retire` believe the callback it is waiting for
    /// is its own caller and skip the wait. Two handles in one process is ordinary,
    /// and a handler for one closing the other is exactly the case that would slip
    /// through.
    in_flight: HashMap<ThreadId, usize>,
}

impl GateState {
    fn total(&self) -> usize {
        self.in_flight.values().sum()
    }

    fn own(&self) -> usize {
        self.in_flight
            .get(&thread::current().id())
            .copied()
            .unwrap_or(0)
    }
}

impl CallbackGate {
    pub fn new() -> Self {
        Self {
            open: AtomicBool::new(true),
            state: Mutex::new(GateState::default()),
            idle: Condvar::new(),
        }
    }

    /// Claim the right to call a host pointer, or `None` once the gate has closed.
    /// Hold the guard for exactly the duration of the call.
    pub fn enter(&self) -> Option<GateGuard<'_>> {
        // Checked under the lock, so the flag cannot flip between the test and the
        // increment — otherwise `retire` could observe a count of zero and return
        // while a callback was on its way in.
        let mut state = self.state.lock().unwrap();
        if !self.open.load(Ordering::Acquire) {
            return None;
        }
        *state.in_flight.entry(thread::current().id()).or_insert(0) += 1;
        Some(GateGuard { gate: self })
    }

    /// Close the gate, then wait for callbacks already in progress to return.
    ///
    /// Idempotent. Safe to call from inside a callback — the gate closes either way
    /// — but that case cannot reach [`Quiescence::Complete`], because the caller
    /// would be waiting for itself.
    #[must_use = "Incomplete means the host must not release its callback pointers"]
    pub fn retire(&self) -> Quiescence {
        self.retire_with_timeout(QUIESCE_TIMEOUT)
    }

    /// [`retire`](Self::retire) with the wait bound spelled out, so tests can
    /// exercise the give-up path without sitting for the real timeout.
    fn retire_with_timeout(&self, wait_for: Duration) -> Quiescence {
        self.open.store(false, Ordering::Release);

        let mut state = self.state.lock().unwrap();

        // Callbacks this very thread is inside can never be waited for.
        let own = state.own();

        while state.total() > own {
            let (guard, timeout) = self
                .idle
                .wait_timeout(state, wait_for)
                .expect("callback gate mutex poisoned");
            state = guard;
            if timeout.timed_out() {
                warn!(
                    "[ffi] gave up waiting for {} host callback(s) to return after {:?}. \
                     The host is likely blocked — a ctypes trampoline waiting on the GIL, \
                     or an interpreter already finalising. Keep the callback pointers \
                     alive; freeing them now is a use-after-free. Tear the client down \
                     before the host runtime starts shutting down.",
                    state.total() - own,
                    wait_for,
                );
                return Quiescence::Incomplete;
            }
        }

        if own > 0 {
            warn!(
                "[ffi] destroyed from inside a host callback, which is still running. \
                 Keep the callback pointers alive until it returns; there is no way to \
                 wait for the caller's own callback from here."
            );
            return Quiescence::Incomplete;
        }

        Quiescence::Complete
    }

    /// Whether the gate is still open. Diagnostics and tests only — a caller that
    /// acts on this is racing, and should use [`enter`](Self::enter) instead.
    #[cfg(test)]
    fn is_open(&self) -> bool {
        self.open.load(Ordering::Acquire)
    }

    #[cfg(test)]
    fn in_flight_total(&self) -> usize {
        self.state.lock().unwrap().total()
    }
}

impl Default for CallbackGate {
    fn default() -> Self {
        Self::new()
    }
}

/// Proof that a host callback may run, for as long as this value lives.
pub struct GateGuard<'a> {
    gate: &'a CallbackGate,
}

impl Drop for GateGuard<'_> {
    fn drop(&mut self) {
        let mut state = self.gate.state.lock().unwrap();
        // Same thread that entered, so the same key.
        if let std::collections::hash_map::Entry::Occupied(mut entry) =
            state.in_flight.entry(thread::current().id())
        {
            *entry.get_mut() -= 1;
            if *entry.get() == 0 {
                entry.remove();
            }
        }
        drop(state);
        // notify_all, not notify_one: more than one thread can be retiring.
        self.gate.idle.notify_all();
    }
}

// ── Handing work to the host ─────────────────────────────────────────────────

/// What a full queue discards.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Overflow {
    /// Keep the newest. Right for video: a frame the host could not keep up with is
    /// stale by the time it would be delivered, and showing it costs latency
    /// without buying anything.
    DropOldest,
    /// Keep the backlog. Right for audio, where the queue *is* the jitter buffer and
    /// a hole is audible.
    DropNewest,
}

struct Queue<T> {
    slots: Mutex<Slots<T>>,
    ready: Condvar,
    /// `None` is unbounded. Control events are unbounded because they are low-rate
    /// and losing a status change or a session id leaves the host with a wrong view
    /// of the session; media is always bounded.
    capacity: Option<usize>,
    overflow: Overflow,
    dropped: AtomicU64,
    name: &'static str,
}

struct Slots<T> {
    items: VecDeque<T>,
    closed: bool,
}

impl<T> Queue<T> {
    fn push(&self, item: T) {
        let mut slots = self.slots.lock().unwrap();
        if slots.closed {
            return;
        }
        if let Some(capacity) = self.capacity {
            if slots.items.len() >= capacity {
                match self.overflow {
                    Overflow::DropOldest => {
                        slots.items.pop_front();
                    }
                    Overflow::DropNewest => {
                        drop(slots);
                        self.count_drop();
                        return;
                    }
                }
                self.count_drop();
            }
        }
        slots.items.push_back(item);
        drop(slots);
        self.ready.notify_one();
    }

    /// Block until an item is available, or `None` once the queue is closed.
    fn pop(&self) -> Option<T> {
        let mut slots = self.slots.lock().unwrap();
        loop {
            if let Some(item) = slots.items.pop_front() {
                return Some(item);
            }
            if slots.closed {
                return None;
            }
            slots = self.ready.wait(slots).unwrap();
        }
    }

    fn close(&self) {
        let mut slots = self.slots.lock().unwrap();
        slots.closed = true;
        // Pending items carry host pointers. Running them after teardown has begun
        // is what the gate refuses anyway, so drop them here rather than making the
        // worker walk a queue it will only reject.
        slots.items.clear();
        drop(slots);
        self.ready.notify_all();
    }

    fn count_drop(&self) {
        let total = self.dropped.fetch_add(1, Ordering::Relaxed) + 1;
        // Dropping under load is the design, not a fault, so this must not become
        // the bottleneck it is reporting. Log on powers of two.
        if total.is_power_of_two() {
            debug!(
                "[ffi] {} queue full, host is behind — {total} dropped so far",
                self.name
            );
        }
    }
}

/// A thread whose job is to block on the host.
///
/// Dropping it closes the queue and joins the thread, so a handle owning one is
/// guaranteed no delivery outlives it.
pub struct HostThread<T: Send + 'static> {
    queue: Arc<Queue<T>>,
    join: Option<JoinHandle<()>>,
}

/// Cloneable handle for enqueuing from tokio and media threads.
pub struct HostSender<T: Send + 'static> {
    queue: Arc<Queue<T>>,
}

impl<T: Send + 'static> Clone for HostSender<T> {
    fn clone(&self) -> Self {
        Self {
            queue: self.queue.clone(),
        }
    }
}

impl<T: Send + 'static> HostSender<T> {
    /// Enqueue `item`. Never blocks and never fails: when the host is behind, the
    /// item is discarded per the queue's [`Overflow`] policy, because the
    /// alternative is stalling a caller that cannot afford to wait.
    pub fn send(&self, item: T) {
        self.queue.push(item);
    }
}

impl<T: Send + 'static> HostThread<T> {
    pub fn spawn(
        name: &'static str,
        capacity: Option<usize>,
        overflow: Overflow,
        gate: Arc<CallbackGate>,
        mut deliver: impl FnMut(T) + Send + 'static,
    ) -> Self {
        let queue = Arc::new(Queue {
            slots: Mutex::new(Slots {
                items: VecDeque::new(),
                closed: false,
            }),
            ready: Condvar::new(),
            capacity,
            overflow,
            dropped: AtomicU64::new(0),
            name,
        });

        let worker = queue.clone();
        let join = thread::Builder::new()
            .name(name.to_string())
            .spawn(move || {
                while let Some(item) = worker.pop() {
                    // Re-checked per item: the gate can close while an item waits.
                    let Some(_admitted) = gate.enter() else { break };
                    deliver(item);
                }
            })
            .expect("spawn host callback thread");

        Self {
            queue,
            join: Some(join),
        }
    }

    pub fn sender(&self) -> HostSender<T> {
        HostSender {
            queue: self.queue.clone(),
        }
    }
}

impl<T: Send + 'static> Drop for HostThread<T> {
    fn drop(&mut self) {
        self.queue.close();
        if let Some(join) = self.join.take() {
            // A host handler that tears its own client down runs *on* this thread,
            // so joining would be joining ourselves. The gate has already closed by
            // then, so the loop exits on its own once the handler returns.
            if join.thread().id() != thread::current().id() {
                let _ = join.join();
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use std::sync::mpsc;
    use std::time::Instant;

    use super::*;

    #[test]
    fn an_open_gate_admits_callbacks() {
        let gate = CallbackGate::new();
        assert!(gate.enter().is_some());
        assert!(gate.is_open());
    }

    #[test]
    fn a_retired_gate_admits_nothing() {
        let gate = CallbackGate::new();
        assert_eq!(gate.retire(), Quiescence::Complete);
        assert!(gate.enter().is_none());
        assert!(!gate.is_open());
    }

    #[test]
    fn retire_is_idempotent() {
        let gate = CallbackGate::new();
        assert_eq!(gate.retire(), Quiescence::Complete);
        assert_eq!(gate.retire(), Quiescence::Complete);
        assert!(gate.enter().is_none());
    }

    #[test]
    fn a_retired_gate_stays_shut_after_a_guard_is_released() {
        let gate = CallbackGate::new();
        let guard = gate.enter().expect("gate is open");
        // Retiring from another thread would block; drop first, then retire.
        drop(guard);
        assert_eq!(gate.retire(), Quiescence::Complete);
        assert!(gate.enter().is_none());
    }

    /// The guarantee `reactor_destroy` sells to every binding: once `retire`
    /// returns, nobody is inside a callback. Without it a host frees the pointer
    /// its callback is still executing from.
    #[test]
    fn retire_waits_for_a_callback_in_flight_on_another_thread() {
        let gate = Arc::new(CallbackGate::new());
        let (entered_tx, entered_rx) = mpsc::channel();
        let (release_tx, release_rx) = mpsc::channel();
        let left = Arc::new(AtomicBool::new(false));

        let worker = {
            let gate = gate.clone();
            let left = left.clone();
            std::thread::spawn(move || {
                let guard = gate.enter().expect("gate is open");
                entered_tx.send(()).unwrap();
                // Hold the callback open until the main thread is committed to
                // waiting for it.
                release_rx.recv().unwrap();
                left.store(true, Ordering::Release);
                drop(guard);
            })
        };

        entered_rx.recv().unwrap();
        release_tx.send(()).unwrap();
        assert_eq!(gate.retire(), Quiescence::Complete);

        assert!(
            left.load(Ordering::Acquire),
            "retire returned while a callback was still in flight"
        );
        worker.join().unwrap();
    }

    /// A host handler that tears the client down runs *on* a callback thread, so
    /// the count it would wait for includes itself. It must not.
    #[test]
    fn retire_from_inside_a_callback_does_not_wait_for_itself() {
        let gate = CallbackGate::new();
        let _guard = gate.enter().expect("gate is open");

        let started = Instant::now();
        let quiescence = gate.retire();

        assert!(
            started.elapsed() < QUIESCE_TIMEOUT,
            "retire waited on the calling thread's own callback"
        );
        assert_eq!(
            quiescence,
            Quiescence::Incomplete,
            "a callback is still running, so the host must not free its pointers"
        );
        assert!(!gate.is_open());
        // Still shut for the re-entrant caller, so a handler cannot start another
        // callback on its way out.
        assert!(gate.enter().is_none());
    }

    #[test]
    fn nested_callbacks_on_one_thread_are_counted() {
        let gate = CallbackGate::new();
        let outer = gate.enter().expect("gate is open");
        let inner = gate.enter().expect("gate is open");
        assert_eq!(gate.in_flight_total(), 2);
        drop(inner);
        assert_eq!(gate.in_flight_total(), 1);
        drop(outer);
        assert_eq!(gate.in_flight_total(), 0);
    }

    /// Two clients in one process have two gates. A callback belonging to one must
    /// not let the other's retire skip the wait — which is what a thread-global
    /// depth counter did, because it could not tell the gates apart.
    #[test]
    fn depth_is_scoped_per_gate() {
        let a = CallbackGate::new();
        let b = Arc::new(CallbackGate::new());

        // This thread is inside a callback belonging to gate A.
        let _inside_a = a.enter().expect("gate A is open");

        // Meanwhile another thread is inside a callback belonging to gate B.
        let (entered_tx, entered_rx) = mpsc::channel();
        let (release_tx, release_rx) = mpsc::channel();
        let left_b = Arc::new(AtomicBool::new(false));
        let worker = {
            let b = b.clone();
            let left_b = left_b.clone();
            std::thread::spawn(move || {
                let guard = b.enter().expect("gate B is open");
                entered_tx.send(()).unwrap();
                release_rx.recv().unwrap();
                left_b.store(true, Ordering::Release);
                drop(guard);
            })
        };
        entered_rx.recv().unwrap();
        release_tx.send(()).unwrap();

        // Retiring B from inside A's callback must still wait for B's callback.
        assert_eq!(b.retire(), Quiescence::Complete);
        assert!(
            left_b.load(Ordering::Acquire),
            "retiring gate B returned while B's callback was still in flight"
        );

        worker.join().unwrap();
    }

    /// `retire` reports Incomplete rather than blocking forever when a callback is
    /// wedged inside the host. The host has to be told, because freeing the pointers
    /// on that answer is the use-after-free the whole gate exists to prevent.
    #[test]
    fn a_wedged_callback_times_out_as_incomplete() {
        let gate = Arc::new(CallbackGate::new());
        let (entered_tx, entered_rx) = mpsc::channel();
        let release = Arc::new(AtomicBool::new(false));

        let worker = {
            let gate = gate.clone();
            let release = release.clone();
            std::thread::spawn(move || {
                let guard = gate.enter().expect("gate is open");
                entered_tx.send(()).unwrap();
                while !release.load(Ordering::Acquire) {
                    std::thread::sleep(Duration::from_millis(5));
                }
                drop(guard);
            })
        };
        entered_rx.recv().unwrap();

        // Deliberately shorter than QUIESCE_TIMEOUT would allow, so the test does
        // not sit for five seconds: assert the timeout path via a scoped override.
        let started = Instant::now();
        let quiescence = gate.retire_with_timeout(Duration::from_millis(50));
        assert_eq!(quiescence, Quiescence::Incomplete);
        assert!(started.elapsed() < QUIESCE_TIMEOUT);

        release.store(true, Ordering::Release);
        worker.join().unwrap();
    }

    // ── HostThread ───────────────────────────────────────────────────────────

    /// Collects what a host thread delivered, so tests can assert on ordering and
    /// on what survived an overflow.
    fn recorder() -> (Arc<Mutex<Vec<u32>>>, impl FnMut(u32) + Send + 'static) {
        let seen = Arc::new(Mutex::new(Vec::new()));
        let sink = seen.clone();
        (seen, move |item: u32| sink.lock().unwrap().push(item))
    }

    #[test]
    fn a_host_thread_delivers_in_order() {
        let gate = Arc::new(CallbackGate::new());
        let (seen, deliver) = recorder();
        let host = HostThread::spawn("test-order", None, Overflow::DropNewest, gate, deliver);

        let tx = host.sender();
        for i in 0..100 {
            tx.send(i);
        }
        drop(host); // closes and joins

        // close() discards anything still queued, so this asserts ordering rather
        // than completeness: whatever arrived, arrived in order.
        let seen = seen.lock().unwrap();
        assert!(
            seen.windows(2).all(|w| w[0] < w[1]),
            "delivered out of order"
        );
    }

    #[test]
    fn delivery_happens_off_the_sending_thread() {
        let gate = Arc::new(CallbackGate::new());
        let sender_thread = thread::current().id();
        let (delivered_on_tx, delivered_on_rx) = mpsc::channel();
        let host = HostThread::spawn(
            "test-thread",
            None,
            Overflow::DropNewest,
            gate,
            move |_: u32| {
                delivered_on_tx.send(thread::current().id()).unwrap();
            },
        );

        host.sender().send(1);
        let delivered_on = delivered_on_rx.recv().unwrap();

        assert_ne!(
            delivered_on, sender_thread,
            "the host ran on the sending thread, which is the whole thing this avoids"
        );
    }

    /// Video's contract: under backpressure the host sees the newest frame, not a
    /// backlog of stale ones.
    #[test]
    fn drop_oldest_keeps_the_newest_item() {
        let gate = Arc::new(CallbackGate::new());
        let (release_tx, release_rx) = mpsc::channel::<()>();
        let (seen_tx, seen_rx) = mpsc::channel();

        let host = HostThread::spawn(
            "test-video",
            Some(1),
            Overflow::DropOldest,
            gate,
            move |item: u32| {
                // Block on the first item so the rest pile into a queue of one.
                if item == 0 {
                    release_rx.recv().unwrap();
                }
                seen_tx.send(item).unwrap();
            },
        );

        let tx = host.sender();
        tx.send(0);
        // Wait for the worker to pick up item 0 and block, so the queue is empty.
        thread::sleep(Duration::from_millis(50));
        for i in 1..=9 {
            tx.send(i);
        }
        release_tx.send(()).unwrap();

        assert_eq!(seen_rx.recv().unwrap(), 0);
        assert_eq!(
            seen_rx.recv().unwrap(),
            9,
            "expected the newest queued item, not a stale one"
        );
    }

    /// Audio's contract: the queue is the jitter buffer, so an overflowing queue
    /// keeps its backlog and refuses the new arrival.
    #[test]
    fn drop_newest_keeps_the_backlog() {
        let gate = Arc::new(CallbackGate::new());
        let (release_tx, release_rx) = mpsc::channel::<()>();
        let (seen_tx, seen_rx) = mpsc::channel();

        let host = HostThread::spawn(
            "test-audio",
            Some(2),
            Overflow::DropNewest,
            gate,
            move |item: u32| {
                if item == 0 {
                    release_rx.recv().unwrap();
                }
                seen_tx.send(item).unwrap();
            },
        );

        let tx = host.sender();
        tx.send(0);
        thread::sleep(Duration::from_millis(50));
        for i in 1..=9 {
            tx.send(i);
        }
        release_tx.send(()).unwrap();

        assert_eq!(seen_rx.recv().unwrap(), 0);
        assert_eq!(
            seen_rx.recv().unwrap(),
            1,
            "expected the oldest queued item"
        );
        assert_eq!(seen_rx.recv().unwrap(), 2);
    }

    /// A retired gate stops delivery even for work already queued, so nothing
    /// reaches a host pointer that may since have been freed.
    #[test]
    fn a_retired_gate_stops_a_host_thread() {
        let gate = Arc::new(CallbackGate::new());
        let (seen, deliver) = recorder();
        let host = HostThread::spawn(
            "test-retire",
            None,
            Overflow::DropNewest,
            gate.clone(),
            deliver,
        );

        assert_eq!(gate.retire(), Quiescence::Complete);
        for i in 0..10 {
            host.sender().send(i);
        }
        drop(host);

        assert!(
            seen.lock().unwrap().is_empty(),
            "delivered after the gate was retired"
        );
    }

    #[test]
    fn sending_to_a_dropped_host_thread_is_a_noop() {
        let gate = Arc::new(CallbackGate::new());
        let (seen, deliver) = recorder();
        let host = HostThread::spawn("test-closed", None, Overflow::DropNewest, gate, deliver);
        let tx = host.sender();
        drop(host);

        // The sender outlives the thread; this must not panic or deliver.
        tx.send(42);
        assert!(seen.lock().unwrap().is_empty());
    }

    #[test]
    fn concurrent_callbacks_all_drain_before_retire_returns() {
        let gate = Arc::new(CallbackGate::new());
        let running = Arc::new(AtomicBool::new(true));
        let (ready_tx, ready_rx) = mpsc::channel();

        let workers: Vec<_> = (0..8)
            .map(|_| {
                let gate = gate.clone();
                let running = running.clone();
                let ready_tx = ready_tx.clone();
                std::thread::spawn(move || {
                    let mut admitted = 0_u32;
                    let mut first = true;
                    while running.load(Ordering::Acquire) {
                        if let Some(guard) = gate.enter() {
                            admitted += 1;
                            if first {
                                ready_tx.send(()).unwrap();
                                first = false;
                            }
                            drop(guard);
                        } else {
                            break;
                        }
                    }
                    admitted
                })
            })
            .collect();
        drop(ready_tx);

        // Every worker has been through the gate at least once.
        for _ in 0..8 {
            ready_rx.recv().unwrap();
        }

        assert_eq!(gate.retire(), Quiescence::Complete);
        assert_eq!(
            gate.in_flight_total(),
            0,
            "retire returned with callbacks still counted"
        );
        running.store(false, Ordering::Release);

        for worker in workers {
            assert!(worker.join().unwrap() > 0);
        }
        // Nothing gets in after retire, however many threads are trying.
        assert!(gate.enter().is_none());
    }
}
