//! Recording / clip request correlation.
//!
//! The runtime processes clip requests in receipt order and `clipFailed`
//! carries no discriminator, so a FIFO queue is the correct correlator.

use std::collections::VecDeque;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;

use futures::channel::oneshot;
use serde_json::Value;

use crate::error::{codes, CoreError};
use crate::protocol::recording::{message_type, ClipFailedPayload, ClipReadyPayload};

/// A finished (or soon-available) clip.
#[derive(Debug, Clone, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct Clip {
    pub session_id: String,
    /// "snap" (requestClip) or "recording" (requestRecording).
    pub kind: String,
    pub start_marker: f64,
    pub end_marker: f64,
    pub now_marker: f64,
    pub predicted_ready_at_ms: f64,
    /// Absolute HLS manifest URL.
    pub playlist_url: String,
}

/// Resolve a clip payload's playlist URL against the coordinator base URL
/// when the runtime returned a path-only URL.
pub fn clip_from_payload(payload: ClipReadyPayload, coordinator_base_url: &str) -> Clip {
    let playlist_url = if payload.playlist_url.starts_with("http://")
        || payload.playlist_url.starts_with("https://")
    {
        payload.playlist_url
    } else {
        format!(
            "{}/{}",
            coordinator_base_url.trim_end_matches('/'),
            payload.playlist_url.trim_start_matches('/')
        )
    };
    Clip {
        session_id: payload.session_id,
        kind: payload.kind,
        start_marker: payload.start_marker,
        end_marker: payload.end_marker,
        now_marker: payload.now_marker,
        predicted_ready_at_ms: payload.predicted_ready_at_ms,
        playlist_url,
    }
}

type ClipResult = Result<Clip, CoreError>;

/// FIFO correlator for clip requests.
#[derive(Default)]
pub struct RecordingCorrelator {
    counter: AtomicU64,
    pending: Mutex<VecDeque<(u64, oneshot::Sender<ClipResult>)>>,
}

impl RecordingCorrelator {
    pub fn new() -> Self {
        Self::default()
    }

    /// Register a clip request before sending the runtime command.
    pub fn begin(&self) -> (u64, oneshot::Receiver<ClipResult>) {
        let ticket = self.counter.fetch_add(1, Ordering::SeqCst) + 1;
        let (tx, rx) = oneshot::channel();
        self.pending.lock().unwrap().push_back((ticket, tx));
        (ticket, rx)
    }

    /// Remove a timed-out request from the queue.
    pub fn cancel(&self, ticket: u64) {
        self.pending.lock().unwrap().retain(|(t, _)| *t != ticket);
    }

    /// Number of requests awaiting a response.
    pub fn pending_count(&self) -> usize {
        self.pending.lock().unwrap().len()
    }

    /// Feed a runtime-scoped message; resolves the oldest pending request on
    /// `clipReady` / `clipFailed`. Returns `true` when the message belonged
    /// to the recording subsystem.
    pub fn handle_runtime_message(&self, message: &Value, coordinator_base_url: &str) -> bool {
        let message_kind = message.get("type").and_then(Value::as_str);
        match message_kind {
            Some(message_type::CLIP_READY) => {
                let result = message
                    .get("data")
                    .cloned()
                    .ok_or_else(|| CoreError::decode("clipReady without data"))
                    .and_then(|d| {
                        serde_json::from_value::<ClipReadyPayload>(d).map_err(CoreError::decode)
                    })
                    .map(|p| clip_from_payload(p, coordinator_base_url));
                self.resolve_front(result);
                true
            }
            Some(message_type::CLIP_FAILED) => {
                let reason = message
                    .get("data")
                    .cloned()
                    .and_then(|d| serde_json::from_value::<ClipFailedPayload>(d).ok())
                    .map(|p| p.reason)
                    .unwrap_or_else(|| "clip request failed".to_string());
                self.resolve_front(Err(CoreError::Recording {
                    code: codes::INTERNAL_ERROR.to_string(),
                    message: reason,
                }));
                true
            }
            _ => false,
        }
    }

    fn resolve_front(&self, result: ClipResult) {
        if let Some((_, tx)) = self.pending.lock().unwrap().pop_front() {
            let _ = tx.send(result);
        } else {
            log::warn!("recording response with no pending request");
        }
    }

    /// Reject every in-flight request (on disconnect).
    pub fn fail_all(&self, reason: &str) {
        let pending = std::mem::take(&mut *self.pending.lock().unwrap());
        for (_, tx) in pending {
            let _ = tx.send(Err(CoreError::Recording {
                code: codes::DISCONNECTED.to_string(),
                message: reason.to_string(),
            }));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn ready_message(url: &str) -> Value {
        json!({
            "type": "clipReady",
            "data": {
                "session_id": "sess_1",
                "kind": "snap",
                "start_marker": 0.0,
                "end_marker": 5.0,
                "now_marker": 6.0,
                "predicted_ready_at_ms": 1.0,
                "playlist_url": url
            }
        })
    }

    #[test]
    fn fifo_order() {
        let r = RecordingCorrelator::new();
        let (_, mut rx1) = r.begin();
        let (_, mut rx2) = r.begin();
        assert!(r.handle_runtime_message(&ready_message("/a.m3u8"), "https://api.reactor.inc"));
        assert!(r.handle_runtime_message(
            &ready_message("https://cdn/b.m3u8"),
            "https://api.reactor.inc"
        ));
        let clip1 = rx1.try_recv().unwrap().unwrap().unwrap();
        let clip2 = rx2.try_recv().unwrap().unwrap().unwrap();
        assert_eq!(clip1.playlist_url, "https://api.reactor.inc/a.m3u8");
        assert_eq!(clip2.playlist_url, "https://cdn/b.m3u8");
    }

    #[test]
    fn failure_rejects_front() {
        let r = RecordingCorrelator::new();
        let (_, mut rx) = r.begin();
        assert!(r.handle_runtime_message(
            &json!({"type": "clipFailed", "data": {"reason": "no frames"}}),
            "https://api"
        ));
        let err = rx.try_recv().unwrap().unwrap().unwrap_err();
        assert!(matches!(err, CoreError::Recording { message, .. } if message == "no frames"));
    }

    #[test]
    fn cancel_removes_specific_entry() {
        let r = RecordingCorrelator::new();
        let (t1, rx1) = r.begin();
        let (_, mut rx2) = r.begin();
        r.cancel(t1);
        drop(rx1);
        assert!(r.handle_runtime_message(&ready_message("/x.m3u8"), "https://api"));
        assert!(rx2.try_recv().unwrap().unwrap().is_ok());
    }

    #[test]
    fn unrelated_messages_ignored() {
        let r = RecordingCorrelator::new();
        assert!(!r.handle_runtime_message(&json!({"type": "moderation"}), "https://api"));
    }
}
