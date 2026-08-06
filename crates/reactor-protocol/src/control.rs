//! Control-channel protocol (WebRTC data channel labeled `"control"`).
//!
//! Request/response for track control plus fire-and-forget notifications:
//!
//! * `publish_track`   — claim the exclusive publisher slot for a sendonly track (request)
//! * `unpublish_track` — release the slot (notification)
//! * `pause_track` / `resume_track` — subscribe control for recvonly tracks (notification)

use serde::{Deserialize, Serialize};
use serde_json::Value;

/// Error body inside a control [`ControlMessage::Response`].
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ControlErrorBody {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub code: Option<String>,
    pub message: String,
}

/// A message on the control channel, discriminated by `"type"`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "lowercase")]
pub enum ControlMessage {
    /// Client → runtime request expecting a correlated response.
    Request {
        method: String,
        request_id: String,
        data: Value,
    },
    /// Runtime → client response. `error: null` (or absent) means success.
    Response {
        request_id: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        method: Option<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        error: Option<ControlErrorBody>,
    },
    /// One-way event in either direction.
    Notification { event: String, data: Value },
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn request_wire_shape() {
        let msg = ControlMessage::Request {
            method: "publish_track".into(),
            request_id: "ctrl_1".into(),
            data: json!({"name": "webcam"}),
        };
        let v = serde_json::to_value(&msg).unwrap();
        assert_eq!(v["type"], "request");
        assert_eq!(v["method"], "publish_track");
        assert_eq!(v["request_id"], "ctrl_1");
        assert_eq!(v["data"]["name"], "webcam");
    }

    #[test]
    fn response_with_null_error_parses_as_success() {
        let msg: ControlMessage =
            serde_json::from_str(r#"{"type": "response", "request_id": "ctrl_1", "error": null}"#)
                .unwrap();
        match msg {
            ControlMessage::Response {
                request_id, error, ..
            } => {
                assert_eq!(request_id, "ctrl_1");
                assert!(error.is_none());
            }
            other => panic!("unexpected: {other:?}"),
        }
    }

    #[test]
    fn response_with_error() {
        let msg: ControlMessage = serde_json::from_str(
            r#"{"type": "response", "request_id": "ctrl_2",
                "error": {"code": "PUBLISHER_SLOT_TAKEN", "message": "taken"}}"#,
        )
        .unwrap();
        match msg {
            ControlMessage::Response { error: Some(e), .. } => {
                assert_eq!(e.code.as_deref(), Some("PUBLISHER_SLOT_TAKEN"));
            }
            other => panic!("unexpected: {other:?}"),
        }
    }

    #[test]
    fn notification_wire_shape() {
        let msg = ControlMessage::Notification {
            event: "pause_track".into(),
            data: json!({"name": "main_video"}),
        };
        let v = serde_json::to_value(&msg).unwrap();
        assert_eq!(v["type"], "notification");
        assert_eq!(v["event"], "pause_track");
    }
}
