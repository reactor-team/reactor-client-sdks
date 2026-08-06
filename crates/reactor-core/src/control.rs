//! Control-channel request correlation.
//!
//! Requests carry a generated `request_id` (`ctrl_1`, `ctrl_2`, ...);
//! responses are matched by id. Timeouts are enforced by the caller
//! (see [`crate::reactor::Reactor`]) racing the receiver against a
//! platform sleep, then calling [`ControlCorrelator::cancel`].

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;

use futures::channel::oneshot;
use serde_json::Value;

use crate::error::CoreError;
use crate::protocol::control::ControlMessage;

type ControlResult = Result<(), CoreError>;

/// A control request ready to send.
pub struct PendingControl {
    /// Serialized request to write to the control channel.
    pub payload: String,
    pub request_id: String,
    /// Resolves when the correlated response arrives.
    pub receiver: oneshot::Receiver<ControlResult>,
}

/// Correlates control-channel requests with their responses.
#[derive(Default)]
pub struct ControlCorrelator {
    counter: AtomicU64,
    pending: Mutex<HashMap<String, oneshot::Sender<ControlResult>>>,
}

impl ControlCorrelator {
    pub fn new() -> Self {
        Self::default()
    }

    /// Build a request and register it for correlation.
    pub fn begin(&self, method: &str, data: Value) -> PendingControl {
        let id = self.counter.fetch_add(1, Ordering::SeqCst) + 1;
        let request_id = format!("ctrl_{id}");
        let message = ControlMessage::Request {
            method: method.to_string(),
            request_id: request_id.clone(),
            data,
        };
        let payload =
            serde_json::to_string(&message).expect("control request serialization cannot fail");
        let (tx, receiver) = oneshot::channel();
        self.pending.lock().unwrap().insert(request_id.clone(), tx);
        PendingControl {
            payload,
            request_id,
            receiver,
        }
    }

    /// Serialize a fire-and-forget notification.
    pub fn notification(event: &str, data: Value) -> String {
        serde_json::to_string(&ControlMessage::Notification {
            event: event.to_string(),
            data,
        })
        .expect("control notification serialization cannot fail")
    }

    /// Feed an inbound control-channel payload. Returns `true` if it resolved
    /// a pending request; notifications and unknown ids return `false`.
    pub fn handle_message(&self, raw: &str) -> bool {
        let Ok(message) = serde_json::from_str::<ControlMessage>(raw) else {
            log::warn!("unparseable control message: {raw}");
            return false;
        };
        let ControlMessage::Response {
            request_id,
            method,
            error,
        } = message
        else {
            return false;
        };
        let Some(tx) = self.pending.lock().unwrap().remove(&request_id) else {
            log::warn!("control response for unknown request_id {request_id}");
            return false;
        };
        let result = match error {
            None => Ok(()),
            Some(body) => Err(CoreError::ControlRequest {
                method: method.unwrap_or_else(|| "request".to_string()),
                code: body.code.unwrap_or_else(|| "UNKNOWN".to_string()),
                message: body.message,
            }),
        };
        let _ = tx.send(result);
        true
    }

    /// Forget a request (after a timeout) so a late response is not
    /// delivered to a dropped receiver.
    pub fn cancel(&self, request_id: &str) {
        self.pending.lock().unwrap().remove(request_id);
    }

    /// Reject every in-flight request (on disconnect).
    pub fn fail_all(&self, reason: &str) {
        let pending = std::mem::take(&mut *self.pending.lock().unwrap());
        for (request_id, tx) in pending {
            let _ = tx.send(Err(CoreError::ControlRequest {
                method: "request".to_string(),
                code: crate::error::codes::DISCONNECTED.to_string(),
                message: format!("{reason} (request_id={request_id})"),
            }));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn resolves_success_response() {
        let c = ControlCorrelator::new();
        let mut pending = c.begin("publish_track", json!({"name": "webcam"}));
        assert!(pending.payload.contains("\"request_id\":\"ctrl_1\""));
        let handled = c.handle_message(&format!(
            r#"{{"type": "response", "request_id": "{}", "error": null}}"#,
            pending.request_id
        ));
        assert!(handled);
        assert!(pending.receiver.try_recv().unwrap().unwrap().is_ok());
    }

    #[test]
    fn resolves_error_response() {
        let c = ControlCorrelator::new();
        let mut pending = c.begin("publish_track", json!({"name": "webcam"}));
        c.handle_message(&format!(
            r#"{{"type": "response", "request_id": "{}",
                 "error": {{"code": "PUBLISHER_SLOT_TAKEN", "message": "taken"}}}}"#,
            pending.request_id
        ));
        let err = pending.receiver.try_recv().unwrap().unwrap().unwrap_err();
        assert!(
            matches!(err, CoreError::ControlRequest { code, .. } if code == "PUBLISHER_SLOT_TAKEN")
        );
    }

    #[test]
    fn ignores_notifications_and_unknown_ids() {
        let c = ControlCorrelator::new();
        assert!(!c.handle_message(r#"{"type": "notification", "event": "x", "data": {}}"#));
        assert!(!c.handle_message(r#"{"type": "response", "request_id": "ctrl_99"}"#));
        assert!(!c.handle_message("not json"));
    }

    #[test]
    fn cancel_prevents_late_delivery() {
        let c = ControlCorrelator::new();
        let pending = c.begin("publish_track", json!({}));
        c.cancel(&pending.request_id);
        assert!(!c.handle_message(&format!(
            r#"{{"type": "response", "request_id": "{}"}}"#,
            pending.request_id
        )));
    }

    #[test]
    fn fail_all_rejects_pending() {
        let c = ControlCorrelator::new();
        let mut p1 = c.begin("a", json!({}));
        let mut p2 = c.begin("b", json!({}));
        c.fail_all("disconnected");
        assert!(p1.receiver.try_recv().unwrap().unwrap().is_err());
        assert!(p2.receiver.try_recv().unwrap().unwrap().is_err());
    }
}
