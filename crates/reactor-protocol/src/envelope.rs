//! The two-level message envelope carried over the WebRTC data channel.
//!
//! Wire format:
//! ```json
//! {
//!   "scope": "application" | "runtime",
//!   "data": {
//!     "type": "<command>",
//!     "data": { ... },
//!     "uploads": { "<param>": { "upload_id": "...", "name": "...", "mime_type": "...", "size": 1 } }
//!   }
//! }
//! ```
//!
//! `application` scope carries model-defined commands (client→runtime) and
//! model emissions (runtime→client). `runtime` scope carries platform-level
//! control (capabilities, moderation, ping, recording requests).

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::upload::FileRef;

/// Routing scope of a data-channel message.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum MessageScope {
    Application,
    Runtime,
}

impl MessageScope {
    pub fn as_str(&self) -> &'static str {
        match self {
            MessageScope::Application => "application",
            MessageScope::Runtime => "runtime",
        }
    }
}

/// Inner command message: `{ type, data, uploads? }`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct InnerMessage {
    #[serde(rename = "type")]
    pub message_type: String,
    pub data: Value,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub uploads: Option<BTreeMap<String, FileRef>>,
}

/// Outbound envelope. Inbound messages should be parsed leniently as raw
/// JSON instead (see `reactor-core`'s `messaging::parse_incoming`), because
/// runtime emissions are free-form.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Envelope {
    pub scope: MessageScope,
    pub data: InnerMessage,
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn envelope_wire_shape() {
        let mut uploads = BTreeMap::new();
        uploads.insert(
            "image".to_string(),
            FileRef {
                upload_id: "up_1".into(),
                name: "cat.png".into(),
                mime_type: "image/png".into(),
                size: 1024,
            },
        );
        let env = Envelope {
            scope: MessageScope::Application,
            data: InnerMessage {
                message_type: "set_image".into(),
                data: json!({"strength": 0.5}),
                uploads: Some(uploads),
            },
        };
        let v = serde_json::to_value(&env).unwrap();
        assert_eq!(v["scope"], "application");
        assert_eq!(v["data"]["type"], "set_image");
        assert_eq!(v["data"]["data"]["strength"], 0.5);
        assert_eq!(v["data"]["uploads"]["image"]["upload_id"], "up_1");
    }

    #[test]
    fn envelope_omits_empty_uploads() {
        let env = Envelope {
            scope: MessageScope::Runtime,
            data: InnerMessage {
                message_type: "ping".into(),
                data: json!({}),
                uploads: None,
            },
        };
        let s = serde_json::to_string(&env).unwrap();
        assert!(!s.contains("uploads"));
        assert!(s.contains("\"scope\":\"runtime\""));
    }
}
