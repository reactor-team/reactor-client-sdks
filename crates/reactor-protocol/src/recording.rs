//! Recording / clip runtime messages.
//!
//! Clip requests travel as runtime-scoped data-channel commands; readiness
//! events come back as runtime messages. The runtime processes requests in
//! receipt order and `clipFailed` carries no discriminator, so clients
//! correlate responses FIFO (see `reactor-core::recording`).

use serde::{Deserialize, Serialize};
use serde_json::Value;

/// Runtime message types for the recording subsystem.
pub mod message_type {
    /// Client → runtime: capture the last N seconds.
    pub const REQUEST_CLIP: &str = "requestClip";
    /// Client → runtime: capture the whole session so far.
    pub const REQUEST_RECORDING: &str = "requestRecording";
    /// Runtime → client: clip is (or will shortly be) available.
    pub const CLIP_READY: &str = "clipReady";
    /// Runtime → client: clip request failed.
    pub const CLIP_FAILED: &str = "clipFailed";
    /// Client → runtime: notify that a presigned upload finished.
    pub const FILE_UPLOADED: &str = "fileUploaded";
    /// Client → runtime keep-alive.
    pub const PING: &str = "ping";
}

/// Payload of a `requestClip` command.
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct RequestClipPayload {
    pub duration_seconds: f64,
}

/// Payload of a `clipReady` runtime message.
///
/// `kind` is kept as a free string ("snap" | "recording" today) for
/// forward compatibility.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ClipReadyPayload {
    pub session_id: String,
    pub kind: String,
    /// Session-relative seconds.
    pub start_marker: f64,
    pub end_marker: f64,
    pub now_marker: f64,
    /// Unix epoch milliseconds at which the chunk is predicted to be written.
    pub predicted_ready_at_ms: f64,
    /// HLS manifest URL — absolute, or a path to resolve against the
    /// coordinator base URL.
    pub playlist_url: String,
    #[serde(flatten)]
    pub extra: serde_json::Map<String, Value>,
}

/// Payload of a `clipFailed` runtime message.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ClipFailedPayload {
    pub reason: String,
    #[serde(flatten)]
    pub extra: serde_json::Map<String, Value>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn clip_ready_parse() {
        let json = r#"{
            "session_id": "sess_1",
            "kind": "snap",
            "start_marker": 1.5,
            "end_marker": 11.5,
            "now_marker": 12.0,
            "predicted_ready_at_ms": 1769900000000.0,
            "playlist_url": "/clips/playlist.m3u8?session_id=sess_1",
            "new_field": true
        }"#;
        let p: ClipReadyPayload = serde_json::from_str(json).unwrap();
        assert_eq!(p.kind, "snap");
        assert_eq!(p.end_marker - p.start_marker, 10.0);
        assert!(p.extra.contains_key("new_field"));
    }
}
