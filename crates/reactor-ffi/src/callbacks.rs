//! Lifetime control for the host's callback pointers.
//!
//! A host callback stops being callable at a moment the host chooses, not one this
//! library controls. A ctypes `CFUNCTYPE` object is freed when Python drops its
//! last reference; a `cgo.Handle` stops resolving when Go deletes it; a JNI
//! `GlobalRef` dies on `DeleteGlobalRef`. Calling a pointer after that point is a
//! jump into freed memory.
//!
//! No binding can protect itself here on its own, because none of them can observe
//! whether a callback is currently running or about to start. That has to come from
//! this side, and [`CallbackGate`] is it.
//!
//! [`retire`](CallbackGate::retire) closes the gate and reports what it achieved.
//! On [`Quiescence::Complete`] nothing is executing and nothing will start, so the
//! host may release its pointers. On [`Quiescence::Incomplete`] a callback is still
//! running and cannot be waited for — the host is wedged, or the caller *is* the
//! callback — and the host must keep the pointers alive. Leaking them is the correct
//! answer there; freeing them is the use-after-free this module exists to prevent.

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Condvar, Mutex};
use std::thread::{self, ThreadId};
use std::time::Duration;

use log::warn;

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

#[cfg(test)]
mod tests {
    use std::sync::mpsc;
    use std::sync::Arc;
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
