//! Data-channel message encoding/decoding.

use std::collections::BTreeMap;

use serde_json::Value;

use crate::error::CoreError;
use crate::protocol::envelope::{Envelope, InnerMessage, MessageScope};
use crate::protocol::upload::FileRef;

/// Encode an outbound command into the two-level envelope and enforce the
/// data-channel size limit.
pub fn encode_command(
    command: &str,
    data: Value,
    scope: MessageScope,
    uploads: Option<BTreeMap<String, FileRef>>,
    max_bytes: usize,
) -> Result<String, CoreError> {
    let uploads = uploads.filter(|u| !u.is_empty());
    let envelope = Envelope {
        scope,
        data: InnerMessage {
            message_type: command.to_string(),
            data,
            uploads,
        },
    };
    let serialized = serde_json::to_string(&envelope).map_err(CoreError::decode)?;
    let size = serialized.len();
    if size > max_bytes {
        return Err(CoreError::MessageTooLarge {
            size,
            max: max_bytes,
        });
    }
    Ok(serialized)
}

/// An inbound data-channel message, classified by scope.
#[derive(Debug, Clone, PartialEq)]
pub enum IncomingMessage {
    Application(Value),
    Runtime(Value),
}

/// Parse an inbound data-channel payload leniently: enveloped messages are
/// unwrapped; anything without a recognizable envelope is treated as an
/// application message (matching the JS SDK's behavior).
pub fn parse_incoming(raw: &str) -> IncomingMessage {
    let value: Value = match serde_json::from_str(raw) {
        Ok(v) => v,
        Err(_) => return IncomingMessage::Application(Value::String(raw.to_string())),
    };
    let scope = value.get("scope").and_then(Value::as_str);
    match (scope, value.get("data")) {
        (Some("application"), Some(data)) => IncomingMessage::Application(data.clone()),
        (Some("runtime"), Some(data)) => IncomingMessage::Runtime(data.clone()),
        _ => IncomingMessage::Application(value),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn encode_basic_command() {
        let s = encode_command(
            "set_prompt",
            json!({"prompt": "a cat"}),
            MessageScope::Application,
            None,
            1024,
        )
        .unwrap();
        let v: Value = serde_json::from_str(&s).unwrap();
        assert_eq!(v["scope"], "application");
        assert_eq!(v["data"]["type"], "set_prompt");
        assert_eq!(v["data"]["data"]["prompt"], "a cat");
        assert!(v["data"].get("uploads").is_none());
    }

    #[test]
    fn encode_with_uploads() {
        let mut uploads = BTreeMap::new();
        uploads.insert(
            "image".to_string(),
            FileRef {
                upload_id: "up_9".into(),
                name: "a.png".into(),
                mime_type: "image/png".into(),
                size: 10,
            },
        );
        let s = encode_command(
            "set_image",
            json!({}),
            MessageScope::Application,
            Some(uploads),
            4096,
        )
        .unwrap();
        let v: Value = serde_json::from_str(&s).unwrap();
        assert_eq!(v["data"]["uploads"]["image"]["upload_id"], "up_9");
    }

    #[test]
    fn size_limit_enforced() {
        let err = encode_command(
            "big",
            json!({"blob": "x".repeat(2048)}),
            MessageScope::Application,
            None,
            256,
        )
        .unwrap_err();
        assert!(matches!(err, CoreError::MessageTooLarge { .. }));
    }

    #[test]
    fn parse_enveloped_and_bare() {
        let app =
            parse_incoming(r#"{"scope": "application", "data": {"type": "emit", "data": 1}}"#);
        assert_eq!(
            app,
            IncomingMessage::Application(json!({"type": "emit", "data": 1}))
        );

        let rt = parse_incoming(r#"{"scope": "runtime", "data": {"type": "clipReady"}}"#);
        assert_eq!(rt, IncomingMessage::Runtime(json!({"type": "clipReady"})));

        let bare = parse_incoming(r#"{"hello": 1}"#);
        assert_eq!(bare, IncomingMessage::Application(json!({"hello": 1})));

        let txt = parse_incoming("plain");
        assert_eq!(
            txt,
            IncomingMessage::Application(Value::String("plain".into()))
        );
    }
}
