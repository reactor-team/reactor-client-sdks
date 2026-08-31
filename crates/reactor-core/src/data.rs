//! Data-channel request correlation (`reactor_wire.v1`, protobuf).
//!
//! Mirrors [`crate::control::ControlCorrelator`]: a request carries a
//! generated `request_id` (`data_1`, `data_2`, ...) and `kind: Request`;
//! the correlated response is matched by that id when it arrives. A
//! response's `payload` oneof may itself be unset — a handler that returned
//! no message acks with just a matching `request_id` — and that still
//! resolves the correlation, as `None` rather than being dropped. An inbound
//! message with an empty `request_id` (`kind: Notification`) is an
//! unprompted broadcast rather than a reply to any request — it matches
//! nothing and is dispatched as a `message` event.
//!
//! Correlation decides who gets an *awaited* value, not who gets an event:
//! a correlated reply carrying a message is dispatched as a `message` event
//! as well, so a listener sees it alongside the resolved call. Only a
//! correlated *error* is withheld from the event surface, because the
//! awaiting caller already received it. Timeouts are
//! enforced by the caller (see [`crate::reactor::Reactor`]) racing the
//! receiver against a platform sleep, then calling [`DataCorrelator::cancel`].

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;

use futures::channel::oneshot;
use prost::Message as _;

use crate::error::CoreError;
use crate::protocol::wire::v1::common::MessageKind;
use crate::protocol::wire::v1::data::{
    data_client_message, data_server_message, DataClientMessage, DataServerMessage,
};

type DataResult = Result<Option<data_server_message::Payload>, CoreError>;

/// A data-channel request ready to send.
pub struct PendingData {
    /// Serialized request to write to the data channel.
    pub payload: Vec<u8>,
    pub request_id: String,
    /// Resolves when the correlated response arrives.
    pub receiver: oneshot::Receiver<DataResult>,
}

/// A decoded inbound data-channel payload, and whether it resolved a
/// pending [`DataCorrelator::begin`] request.
pub struct HandledMessage {
    pub payload: Option<data_server_message::Payload>,
    /// `true` when this reply's `request_id` matched (and resolved) a
    /// pending `send_command()` call.
    ///
    /// This gates **error** reporting only: a correlated failure already
    /// reached the caller as `CoreError::CommandRequest`, so raising it on
    /// the `error` event too would double-report it. A correlated *message*
    /// is still dispatched as a `message` event — see
    /// [`DataCorrelator::handle_message`].
    pub correlated: bool,
}

/// Correlates data-channel commands with their responses.
#[derive(Default)]
pub struct DataCorrelator {
    counter: AtomicU64,
    /// Keyed by `request_id`; the stored command name lets `fail_all`
    /// report which command a disconnect-time rejection belongs to.
    pending: Mutex<HashMap<String, (String, oneshot::Sender<DataResult>)>>,
}

impl DataCorrelator {
    pub fn new() -> Self {
        Self::default()
    }

    /// Build a request and register it for correlation.
    pub fn begin(&self, payload: data_client_message::Payload) -> PendingData {
        let command = match &payload {
            data_client_message::Payload::Command(c) => c.r#type.clone(),
            data_client_message::Payload::Error(_) => "command".to_string(),
        };
        let id = self.counter.fetch_add(1, Ordering::SeqCst) + 1;
        let request_id = format!("data_{id}");
        let message = DataClientMessage {
            request_id: request_id.clone(),
            kind: MessageKind::Request as i32,
            payload: Some(payload),
        };
        let (tx, receiver) = oneshot::channel();
        self.pending
            .lock()
            .unwrap()
            .insert(request_id.clone(), (command, tx));
        PendingData {
            payload: message.encode_to_vec(),
            request_id,
            receiver,
        }
    }

    /// Feed an inbound data-channel payload. Resolves the pending request it
    /// correlates to, if any, and always returns the decoded payload so the
    /// caller can additionally dispatch it as a public event — a command's
    /// awaited reply is still broadcast as a `message` event for listeners
    /// that prefer that surface.
    pub fn handle_message(&self, raw: &[u8]) -> Option<HandledMessage> {
        let message = match DataServerMessage::decode(raw) {
            Ok(m) => m,
            Err(error) => {
                log::warn!("unparseable data message: {error}");
                return None;
            }
        };
        // A bodyless command acknowledgement (a handler that returned no
        // message) carries a matching request_id but leaves the `payload`
        // oneof entirely unset. `None` still resolves the pending
        // correlation below instead of being silently dropped.
        let payload = message.payload;
        let mut correlated = false;
        if !message.request_id.is_empty() {
            match self.pending.lock().unwrap().remove(&message.request_id) {
                Some((_, tx)) => {
                    let _ = tx.send(Ok(payload.clone()));
                    correlated = true;
                }
                None => {
                    log::warn!(
                        "data response for unknown request_id {}",
                        message.request_id
                    );
                }
            }
        }
        Some(HandledMessage {
            payload,
            correlated,
        })
    }

    /// Forget a request (after a timeout) so a late response is not
    /// delivered to a dropped receiver.
    pub fn cancel(&self, request_id: &str) {
        self.pending.lock().unwrap().remove(request_id);
    }

    /// Reject every in-flight request (on disconnect).
    pub fn fail_all(&self, reason: &str) {
        let pending = std::mem::take(&mut *self.pending.lock().unwrap());
        for (request_id, (command, tx)) in pending {
            let _ = tx.send(Err(CoreError::CommandRequest {
                command,
                code: crate::error::codes::DISCONNECTED.to_string(),
                message: format!("{reason} (request_id={request_id})"),
            }));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::wire::v1::common::Error;
    use crate::protocol::wire::v1::model::{Command, ModelMessage};

    fn encode_response(request_id: &str, payload: data_server_message::Payload) -> Vec<u8> {
        DataServerMessage {
            request_id: request_id.to_string(),
            kind: MessageKind::Response as i32,
            payload: Some(payload),
        }
        .encode_to_vec()
    }

    #[test]
    fn resolves_success_response() {
        let c = DataCorrelator::new();
        let mut pending = c.begin(data_client_message::Payload::Command(Command {
            r#type: "get_state".into(),
            data: None,
            uploads: Default::default(),
        }));
        assert_eq!(pending.request_id, "data_1");
        let bytes = encode_response(
            &pending.request_id,
            data_server_message::Payload::Message(ModelMessage {
                r#type: "get_state_reply".into(),
                data: None,
            }),
        );
        let handled = c.handle_message(&bytes).unwrap();
        assert!(handled.correlated);
        assert!(matches!(
            pending.receiver.try_recv().unwrap().unwrap(),
            Ok(Some(data_server_message::Payload::Message(_)))
        ));
    }

    #[test]
    fn resolves_a_bodyless_ack_as_none() {
        // `encode_command_ack` (reactor-runtime) replies to a handler that
        // returned nothing with a `Response` carrying the matching
        // request_id but no `message`/`error` — the `payload` oneof itself is
        // unset. That must still resolve the pending correlation, as `None`,
        // rather than being silently dropped.
        let c = DataCorrelator::new();
        let mut pending = c.begin(data_client_message::Payload::Command(Command {
            r#type: "set_paused".into(),
            data: None,
            uploads: Default::default(),
        }));
        let bytes = DataServerMessage {
            request_id: pending.request_id.clone(),
            kind: MessageKind::Response as i32,
            payload: None,
        }
        .encode_to_vec();

        let handled = c.handle_message(&bytes).unwrap();
        assert!(handled.correlated);
        assert!(handled.payload.is_none());
        assert!(matches!(
            pending.receiver.try_recv().unwrap().unwrap(),
            Ok(None)
        ));
    }

    #[test]
    fn resolves_error_response() {
        let c = DataCorrelator::new();
        let mut pending = c.begin(data_client_message::Payload::Command(Command {
            r#type: "get_state".into(),
            data: None,
            uploads: Default::default(),
        }));
        let bytes = encode_response(
            &pending.request_id,
            data_server_message::Payload::Error(Error {
                code: "BAD_COMMAND".into(),
                message: "unknown command".into(),
            }),
        );
        let handled = c.handle_message(&bytes).unwrap();
        assert!(handled.correlated);
        match pending.receiver.try_recv().unwrap().unwrap().unwrap() {
            Some(data_server_message::Payload::Error(e)) => {
                assert_eq!(e.code, "BAD_COMMAND");
            }
            other => panic!("unexpected: {other:?}"),
        }
        match handled.payload {
            Some(data_server_message::Payload::Error(e)) => assert_eq!(e.code, "BAD_COMMAND"),
            other => panic!("unexpected: {other:?}"),
        }
    }

    #[test]
    fn notifications_are_returned_without_correlating() {
        let c = DataCorrelator::new();
        let bytes = DataServerMessage {
            request_id: String::new(),
            kind: MessageKind::Notification as i32,
            payload: Some(data_server_message::Payload::Message(ModelMessage {
                r#type: "emit".into(),
                data: None,
            })),
        }
        .encode_to_vec();
        let handled = c.handle_message(&bytes).unwrap();
        assert!(!handled.correlated);
        assert!(matches!(
            handled.payload,
            Some(data_server_message::Payload::Message(_))
        ));
    }

    #[test]
    fn unknown_request_id_still_returns_the_decoded_payload_uncorrelated() {
        let c = DataCorrelator::new();
        let bytes = encode_response(
            "data_99",
            data_server_message::Payload::Error(Error {
                code: "X".into(),
                message: "y".into(),
            }),
        );
        let handled = c.handle_message(&bytes).unwrap();
        assert!(!handled.correlated);
    }

    #[test]
    fn cancel_prevents_late_delivery() {
        let c = DataCorrelator::new();
        let mut pending = c.begin(data_client_message::Payload::Command(Command {
            r#type: "get_state".into(),
            data: None,
            uploads: Default::default(),
        }));
        c.cancel(&pending.request_id);
        let bytes = encode_response(
            &pending.request_id,
            data_server_message::Payload::Message(ModelMessage {
                r#type: "get_state_reply".into(),
                data: None,
            }),
        );
        c.handle_message(&bytes);
        assert!(pending.receiver.try_recv().is_err());
    }

    #[test]
    fn fail_all_rejects_pending_and_preserves_the_command_name() {
        let c = DataCorrelator::new();
        let mut p1 = c.begin(data_client_message::Payload::Command(Command {
            r#type: "get_state".into(),
            data: None,
            uploads: Default::default(),
        }));
        let mut p2 = c.begin(data_client_message::Payload::Command(Command {
            r#type: "set_brightness".into(),
            data: None,
            uploads: Default::default(),
        }));
        c.fail_all("disconnected");
        match p1.receiver.try_recv().unwrap().unwrap().unwrap_err() {
            CoreError::CommandRequest { command, .. } => assert_eq!(command, "get_state"),
            other => panic!("unexpected: {other:?}"),
        }
        match p2.receiver.try_recv().unwrap().unwrap().unwrap_err() {
            CoreError::CommandRequest { command, .. } => assert_eq!(command, "set_brightness"),
            other => panic!("unexpected: {other:?}"),
        }
    }
}
