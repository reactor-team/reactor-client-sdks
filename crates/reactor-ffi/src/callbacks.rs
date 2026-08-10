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
//! this side, and [`CallbackGate`] is it: after [`retire`](CallbackGate::retire)
//! returns, no callback is executing and none will start, so the host is free to
//! release whatever its pointers refer to.

use std::cell::Cell;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Condvar, Mutex};
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

thread_local! {
    /// How many callbacks this thread is currently inside.
    ///
    /// Lets `retire` tell "another thread is mid-callback, wait for it to leave"
    /// from "I *am* that callback, and waiting for myself would deadlock" — which
    /// is what happens when a host handler reacts to an event by closing the
    /// client.
    static DEPTH: Cell<usize> = const { Cell::new(0) };
}

/// Decides whether host callbacks may still run, and lets teardown wait until none
/// are running.
pub struct CallbackGate {
    open: AtomicBool,
    in_flight: Mutex<usize>,
    idle: Condvar,
}

impl CallbackGate {
    pub fn new() -> Self {
        Self {
            open: AtomicBool::new(true),
            in_flight: Mutex::new(0),
            idle: Condvar::new(),
        }
    }

    /// Claim the right to call a host pointer, or `None` once the gate has closed.
    /// Hold the guard for exactly the duration of the call.
    pub fn enter(&self) -> Option<GateGuard<'_>> {
        // Checked under the lock, so the flag cannot flip between the test and the
        // increment — otherwise `retire` could observe a count of zero and return
        // while a callback was on its way in.
        let mut n = self.in_flight.lock().unwrap();
        if !self.open.load(Ordering::Acquire) {
            return None;
        }
        *n += 1;
        DEPTH.with(|d| d.set(d.get() + 1));
        Some(GateGuard { gate: self })
    }

    /// Close the gate, then wait for callbacks already in progress to return.
    ///
    /// Idempotent, and safe to call from inside a callback: the gate closes either
    /// way, but this thread does not wait for itself.
    pub fn retire(&self) {
        self.open.store(false, Ordering::Release);

        // Callbacks this very thread is inside can never be waited for.
        let own = DEPTH.with(|d| d.get());

        let mut n = self.in_flight.lock().unwrap();
        while *n > own {
            let (guard, timeout) = self
                .idle
                .wait_timeout(n, QUIESCE_TIMEOUT)
                .expect("callback gate mutex poisoned");
            n = guard;
            if timeout.timed_out() {
                warn!(
                    "[ffi] gave up waiting for {} host callback(s) to return after {:?}. \
                     The host may be blocked (a ctypes trampoline waiting on the GIL, \
                     or an interpreter already finalising). Releasing callback \
                     pointers now risks a use-after-free — tear the client down \
                     before the host runtime starts shutting down.",
                    *n - own,
                    QUIESCE_TIMEOUT,
                );
                return;
            }
        }
    }

    /// Whether the gate is still open. Diagnostics and tests only — a caller that
    /// acts on this is racing, and should use [`enter`](Self::enter) instead.
    #[cfg(test)]
    fn is_open(&self) -> bool {
        self.open.load(Ordering::Acquire)
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
        DEPTH.with(|d| d.set(d.get() - 1));
        let mut n = self.gate.in_flight.lock().unwrap();
        *n -= 1;
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
        gate.retire();
        assert!(gate.enter().is_none());
        assert!(!gate.is_open());
    }

    #[test]
    fn retire_is_idempotent() {
        let gate = CallbackGate::new();
        gate.retire();
        gate.retire();
        assert!(gate.enter().is_none());
    }

    #[test]
    fn a_retired_gate_stays_shut_after_a_guard_is_released() {
        let gate = CallbackGate::new();
        let guard = gate.enter().expect("gate is open");
        // Retiring from another thread would block; drop first, then retire.
        drop(guard);
        gate.retire();
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
        gate.retire();

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
        gate.retire();

        assert!(
            started.elapsed() < QUIESCE_TIMEOUT,
            "retire waited on the calling thread's own callback"
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
        assert_eq!(*gate.in_flight.lock().unwrap(), 2);
        drop(inner);
        assert_eq!(*gate.in_flight.lock().unwrap(), 1);
        drop(outer);
        assert_eq!(*gate.in_flight.lock().unwrap(), 0);
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

        gate.retire();
        assert_eq!(
            *gate.in_flight.lock().unwrap(),
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
