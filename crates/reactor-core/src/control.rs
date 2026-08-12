//! Control-channel request correlation (`reactor_wire.v1`, protobuf).
//!
//! Requests carry a generated `request_id` (`ctrl_1`, `ctrl_2`, ...);
//! responses are matched by id. Timeouts are enforced by the caller
//! (see [`crate::reactor::Reactor`]) racing the receiver against a
//! platform sleep, then calling [`ControlCorrelator::cancel`].
//!
//! One correlator instance serves every request/response pair on the
//! control channel — track control (`publish_track`) and clip/recording
//! requests alike — since `reactor_wire.v1` correlates all of them by
//! `request_id` uniformly.

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;

use futures::channel::oneshot;
use prost::Message as _;

use crate::error::CoreError;
use crate::protocol::wire::v1::common::MessageKind;
use crate::protocol::wire::v1::control::{
    control_client_message, control_server_message, ControlClientMessage, ControlServerMessage,
};

type ControlResult = Result<control_server_message::Payload, CoreError>;

/// A control request ready to send.
pub struct PendingControl {
    /// Serialized request to write to the control channel.
    pub payload: Vec<u8>,
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
    pub fn begin(&self, payload: control_client_message::Payload) -> PendingControl {
        let id = self.counter.fetch_add(1, Ordering::SeqCst) + 1;
        let request_id = format!("ctrl_{id}");
        let message = ControlClientMessage {
            request_id: request_id.clone(),
            kind: MessageKind::Request as i32,
            payload: Some(payload),
        };
        let (tx, receiver) = oneshot::channel();
        self.pending.lock().unwrap().insert(request_id.clone(), tx);
        PendingControl {
            payload: message.encode_to_vec(),
            request_id,
            receiver,
        }
    }

    /// Serialize a fire-and-forget notification.
    pub fn notification(payload: control_client_message::Payload) -> Vec<u8> {
        ControlClientMessage {
            request_id: String::new(),
            kind: MessageKind::Notification as i32,
            payload: Some(payload),
        }
        .encode_to_vec()
    }

    /// Feed an inbound control-channel payload. Resolves the pending request
    /// it correlates to, if any, and always returns the decoded payload so
    /// the caller can additionally broadcast unprompted pushes (moderation)
    /// or responses callers also expose as events (clip ready/failed).
    pub fn handle_message(&self, raw: &[u8]) -> Option<control_server_message::Payload> {
        let message = match ControlServerMessage::decode(raw) {
            Ok(m) => m,
            Err(error) => {
                log::warn!("unparseable control message: {error}");
                return None;
            }
        };
        let payload = message.payload?;
        if !message.request_id.is_empty() {
            match self.pending.lock().unwrap().remove(&message.request_id) {
                Some(tx) => {
                    let _ = tx.send(Ok(payload.clone()));
                }
                None => {
                    log::warn!(
                        "control response for unknown request_id {}",
                        message.request_id
                    );
                }
            }
        }
        Some(payload)
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
    use crate::protocol::wire::v1::common::Error;
    use crate::protocol::wire::v1::platform::{ClipFailed, ClipReady};
    use crate::protocol::wire::v1::track::{PublishTrack, PublishTrackResponse};

    fn encode_response(request_id: &str, payload: control_server_message::Payload) -> Vec<u8> {
        ControlServerMessage {
            request_id: request_id.to_string(),
            kind: MessageKind::Response as i32,
            payload: Some(payload),
        }
        .encode_to_vec()
    }

    #[test]
    fn resolves_success_response() {
        let c = ControlCorrelator::new();
        let mut pending = c.begin(control_client_message::Payload::PublishTrack(
            PublishTrack {
                name: "webcam".into(),
            },
        ));
        assert_eq!(pending.request_id, "ctrl_1");
        let bytes = encode_response(
            &pending.request_id,
            control_server_message::Payload::PublishTrack(PublishTrackResponse {
                name: "webcam".into(),
            }),
        );
        assert!(c.handle_message(&bytes).is_some());
        assert!(matches!(
            pending.receiver.try_recv().unwrap().unwrap(),
            Ok(control_server_message::Payload::PublishTrack(_))
        ));
    }

    #[test]
    fn resolves_error_response() {
        let c = ControlCorrelator::new();
        let mut pending = c.begin(control_client_message::Payload::PublishTrack(
            PublishTrack {
                name: "webcam".into(),
            },
        ));
        let bytes = encode_response(
            &pending.request_id,
            control_server_message::Payload::Error(Error {
                code: "PUBLISHER_SLOT_TAKEN".into(),
                message: "taken".into(),
            }),
        );
        c.handle_message(&bytes);
        match pending.receiver.try_recv().unwrap().unwrap().unwrap() {
            control_server_message::Payload::Error(e) => {
                assert_eq!(e.code, "PUBLISHER_SLOT_TAKEN");
            }
            other => panic!("unexpected: {other:?}"),
        }
    }

    #[test]
    fn clip_ready_and_failed_also_correlate() {
        let c = ControlCorrelator::new();
        let mut pending = c.begin(control_client_message::Payload::RequestClip(
            crate::protocol::wire::v1::platform::RequestClip {
                duration_seconds: 5.0,
            },
        ));
        let bytes = encode_response(
            &pending.request_id,
            control_server_message::Payload::ClipReady(ClipReady {
                session_id: "sess_1".into(),
                kind: "snap".into(),
                start_marker: 0.0,
                end_marker: 5.0,
                now_marker: 5.0,
                predicted_ready_at_ms: 1,
                playlist_url: "/a.m3u8".into(),
            }),
        );
        c.handle_message(&bytes);
        assert!(matches!(
            pending.receiver.try_recv().unwrap().unwrap().unwrap(),
            control_server_message::Payload::ClipReady(_)
        ));

        let mut pending2 = c.begin(control_client_message::Payload::RequestRecording(
            crate::protocol::wire::v1::platform::RequestRecording {},
        ));
        let bytes2 = encode_response(
            &pending2.request_id,
            control_server_message::Payload::ClipFailed(ClipFailed {
                reason: "no frames".into(),
            }),
        );
        c.handle_message(&bytes2);
        assert!(matches!(
            pending2.receiver.try_recv().unwrap().unwrap().unwrap(),
            control_server_message::Payload::ClipFailed(_)
        ));
    }

    #[test]
    fn broadcasts_are_returned_without_correlating() {
        let c = ControlCorrelator::new();
        let bytes = ControlServerMessage {
            request_id: String::new(),
            kind: MessageKind::Notification as i32,
            payload: Some(control_server_message::Payload::Moderation(
                crate::protocol::wire::v1::platform::Moderation {
                    action: "warn".into(),
                    input_kind: "text".into(),
                    command: "set_prompt".into(),
                    categories: vec!["violence".into()],
                    message: "flagged".into(),
                },
            )),
        }
        .encode_to_vec();
        assert!(matches!(
            c.handle_message(&bytes),
            Some(control_server_message::Payload::Moderation(_))
        ));
    }

    #[test]
    fn unknown_request_id_still_returns_the_decoded_payload() {
        let c = ControlCorrelator::new();
        let bytes = encode_response(
            "ctrl_99",
            control_server_message::Payload::Error(Error {
                code: "X".into(),
                message: "y".into(),
            }),
        );
        // Unknown request_id: still decodes and returns the payload, just
        // logs a warning instead of resolving anything.
        assert!(c.handle_message(&bytes).is_some());
    }

    #[test]
    fn cancel_prevents_late_delivery() {
        let c = ControlCorrelator::new();
        let mut pending = c.begin(control_client_message::Payload::PublishTrack(
            PublishTrack { name: "x".into() },
        ));
        c.cancel(&pending.request_id);
        let bytes = encode_response(
            &pending.request_id,
            control_server_message::Payload::PublishTrack(PublishTrackResponse {
                name: "x".into(),
            }),
        );
        c.handle_message(&bytes);
        // `cancel` dropped the sender, so the receiver sees `Canceled` —
        // never the late response.
        assert!(pending.receiver.try_recv().is_err());
    }

    #[test]
    fn fail_all_rejects_pending() {
        let c = ControlCorrelator::new();
        let mut p1 = c.begin(control_client_message::Payload::PublishTrack(
            PublishTrack { name: "a".into() },
        ));
        let mut p2 = c.begin(control_client_message::Payload::PublishTrack(
            PublishTrack { name: "b".into() },
        ));
        c.fail_all("disconnected");
        assert!(p1.receiver.try_recv().unwrap().unwrap().is_err());
        assert!(p2.receiver.try_recv().unwrap().unwrap().is_err());
    }
}
