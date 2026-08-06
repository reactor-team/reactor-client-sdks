//! Event stream from the core to the host / bindings.
//!
//! Multi-subscriber dispatcher: each [`Dispatcher::subscribe`] returns an
//! independent unbounded receiver; dead receivers are pruned on dispatch.

use std::sync::Mutex;

use futures::channel::mpsc;
use serde_json::Value;

use crate::error::ReactorError;
use crate::protocol::session::Capabilities;
use crate::state::ReactorStatus;

/// Events emitted by [`crate::reactor::Reactor`]. Bindings map these 1:1 to
/// the host SDK's event surface (`on("statusChanged")`, decorators, ...).
#[derive(Debug, Clone)]
pub enum ReactorEvent {
    StatusChanged(ReactorStatus),
    SessionIdChanged(Option<String>),
    /// Application-scoped message from the model.
    Message(Value),
    /// Runtime-scoped (platform) message: capabilities, moderation,
    /// recording lifecycle, ...
    RuntimeMessage(Value),
    /// A remote media track arrived; the actual media object stays in the
    /// host's WebRTC layer, identified by `mid`.
    TrackReceived {
        name: String,
        mid: Option<String>,
    },
    Error(ReactorError),
    CapabilitiesReceived(Capabilities),
}

/// Fan-out dispatcher.
#[derive(Default)]
pub struct Dispatcher {
    senders: Mutex<Vec<mpsc::UnboundedSender<ReactorEvent>>>,
}

impl Dispatcher {
    pub fn new() -> Self {
        Self::default()
    }

    /// Register a new subscriber. Events dispatched after this call are
    /// delivered to the returned receiver.
    pub fn subscribe(&self) -> mpsc::UnboundedReceiver<ReactorEvent> {
        let (tx, rx) = mpsc::unbounded();
        self.senders.lock().unwrap().push(tx);
        rx
    }

    /// Deliver `event` to all live subscribers, pruning closed ones.
    pub fn dispatch(&self, event: ReactorEvent) {
        let mut senders = self.senders.lock().unwrap();
        senders.retain(|tx| tx.unbounded_send(event.clone()).is_ok());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fan_out_and_prune() {
        let d = Dispatcher::new();
        let mut a = d.subscribe();
        let b = d.subscribe();
        d.dispatch(ReactorEvent::StatusChanged(ReactorStatus::Connecting));
        assert!(matches!(
            a.try_recv().unwrap(),
            ReactorEvent::StatusChanged(ReactorStatus::Connecting)
        ));
        drop(b);
        d.dispatch(ReactorEvent::StatusChanged(ReactorStatus::Ready));
        assert_eq!(d.senders.lock().unwrap().len(), 1);
        assert!(matches!(
            a.try_recv().unwrap(),
            ReactorEvent::StatusChanged(ReactorStatus::Ready)
        ));
    }
}
