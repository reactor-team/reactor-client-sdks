//! Data-channel message encoding/decoding (`reactor_wire.v1`, protobuf).
//!
//! The data channel carries only application-scoped traffic: model commands
//! (client → runtime) and model emissions (runtime → client). Runtime/
//! platform traffic (ping, clip/recording requests, track control, ...)
//! travels on the control channel instead — see [`crate::control`].

use std::collections::{BTreeMap, HashMap};

use prost::Message as _;
use serde_json::{json, Value};

use crate::error::CoreError;
use crate::protocol::upload::FileRef;
use crate::protocol::wire::struct_convert::{struct_to_value, value_to_struct};
use crate::protocol::wire::v1::common::MessageKind;
use crate::protocol::wire::v1::data::{data_client_message, data_server_message};
use crate::protocol::wire::v1::data::{DataClientMessage, DataServerMessage};
use crate::protocol::wire::v1::model::{Command, UploadReference};

/// Encode an outbound application command as a `DataClientMessage` and
/// enforce the data-channel size limit.
pub fn encode_command(
    command: &str,
    data: Value,
    uploads: Option<BTreeMap<String, FileRef>>,
    max_bytes: usize,
) -> Result<Vec<u8>, CoreError> {
    let data = value_to_struct(data)
        .ok_or_else(|| CoreError::decode("command data must be a JSON object"))?;
    let uploads = uploads
        .into_iter()
        .flatten()
        .map(|(param, file)| {
            (
                param,
                UploadReference {
                    upload_id: file.upload_id,
                    name: file.name,
                    mime_type: file.mime_type,
                    size: file.size as i64,
                },
            )
        })
        .collect::<HashMap<_, _>>();
    let message = DataClientMessage {
        request_id: String::new(),
        kind: MessageKind::Notification as i32,
        payload: Some(data_client_message::Payload::Command(Command {
            r#type: command.to_string(),
            data: Some(data),
            uploads,
        })),
    };
    let encoded = message.encode_to_vec();
    if encoded.len() > max_bytes {
        return Err(CoreError::MessageTooLarge {
            size: encoded.len(),
            max: max_bytes,
        });
    }
    Ok(encoded)
}

/// An inbound, decoded data-channel message.
#[derive(Debug, Clone, PartialEq)]
pub enum IncomingMessage {
    /// A model emission (`ModelMessage`), as `{"type": ..., "data": ...}` —
    /// consumers key on `type` to distinguish emission kinds.
    Application(Value),
    /// The runtime rejected the last command sent on this channel.
    Error { code: String, message: String },
}

/// Decode an inbound data-channel payload.
pub fn parse_incoming(raw: &[u8]) -> Result<IncomingMessage, CoreError> {
    let message = DataServerMessage::decode(raw).map_err(CoreError::decode)?;
    match message.payload {
        Some(data_server_message::Payload::Message(model_message)) => {
            let data = model_message
                .data
                .map(struct_to_value)
                .unwrap_or(Value::Null);
            Ok(IncomingMessage::Application(json!({
                "type": model_message.r#type,
                "data": data,
            })))
        }
        Some(data_server_message::Payload::Error(error)) => Ok(IncomingMessage::Error {
            code: error.code,
            message: error.message,
        }),
        None => Err(CoreError::decode("DataServerMessage without a payload")),
    }
}

/// Legacy JSON envelope encode/decode for runtime-scoped commands
/// (`ping`, clip/recording requests, `fileUploaded`), which still travel on
/// the data channel as the two-level `{scope, data}` envelope. These do not
/// exist in the `reactor_wire.v1` data-channel schema (only `Command`/
/// `ModelMessage` do) — moving them to their typed control-channel
/// equivalents is a separate change. Everything in this module is removed
/// once that lands.
pub mod legacy {
    use serde_json::Value;

    use crate::error::CoreError;
    use crate::protocol::envelope::{Envelope, InnerMessage, MessageScope};

    pub fn encode_runtime_command(
        command: &str,
        data: Value,
        max_bytes: usize,
    ) -> Result<Vec<u8>, CoreError> {
        let envelope = Envelope {
            scope: MessageScope::Runtime,
            data: InnerMessage {
                message_type: command.to_string(),
                data,
                uploads: None,
            },
        };
        let serialized = serde_json::to_string(&envelope).map_err(CoreError::decode)?;
        if serialized.len() > max_bytes {
            return Err(CoreError::MessageTooLarge {
                size: serialized.len(),
                max: max_bytes,
            });
        }
        Ok(serialized.into_bytes())
    }

    /// Parse a legacy `{scope, data}` envelope. Returns `None` for anything
    /// that is not valid legacy JSON (in particular, a `reactor_wire.v1`
    /// protobuf frame, which [`super::parse_incoming`] should be tried on
    /// first).
    pub fn parse_incoming(raw: &[u8]) -> Option<(MessageScope, Value)> {
        let value: Value = serde_json::from_slice(raw).ok()?;
        let scope = value.get("scope").and_then(Value::as_str);
        match (scope, value.get("data")) {
            (Some("application"), Some(data)) => Some((MessageScope::Application, data.clone())),
            (Some("runtime"), Some(data)) => Some((MessageScope::Runtime, data.clone())),
            _ => None,
        }
    }

    #[cfg(test)]
    mod tests {
        use super::*;
        use serde_json::json;

        #[test]
        fn encode_runtime_command_wire_shape() {
            let bytes = encode_runtime_command("ping", json!({}), 1024).unwrap();
            let v: Value = serde_json::from_slice(&bytes).unwrap();
            assert_eq!(v["scope"], "runtime");
            assert_eq!(v["data"]["type"], "ping");
        }

        #[test]
        fn parse_runtime_and_application_scope() {
            let rt =
                parse_incoming(br#"{"scope": "runtime", "data": {"type": "clipReady"}}"#).unwrap();
            assert_eq!(rt, (MessageScope::Runtime, json!({"type": "clipReady"})));

            let app =
                parse_incoming(br#"{"scope": "application", "data": {"type": "emit"}}"#).unwrap();
            assert_eq!(app, (MessageScope::Application, json!({"type": "emit"})));
        }

        #[test]
        fn non_legacy_payload_is_none() {
            assert!(parse_incoming(b"\x08\x01").is_none());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::wire::v1::model::ModelMessage;

    fn decode_command(bytes: &[u8]) -> Command {
        let message = DataClientMessage::decode(bytes).unwrap();
        match message.payload {
            Some(data_client_message::Payload::Command(c)) => c,
            other => panic!("expected Command, got {other:?}"),
        }
    }

    #[test]
    fn encode_basic_command() {
        let bytes = encode_command("set_prompt", json!({"prompt": "a cat"}), None, 1024).unwrap();
        let command = decode_command(&bytes);
        assert_eq!(command.r#type, "set_prompt");
        assert_eq!(
            struct_to_value(command.data.unwrap()),
            json!({"prompt": "a cat"})
        );
        assert!(command.uploads.is_empty());
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
        let bytes = encode_command("set_image", json!({}), Some(uploads), 4096).unwrap();
        let command = decode_command(&bytes);
        assert_eq!(command.uploads["image"].upload_id, "up_9");
    }

    #[test]
    fn size_limit_enforced() {
        let err = encode_command("big", json!({"blob": "x".repeat(2048)}), None, 256).unwrap_err();
        assert!(matches!(err, CoreError::MessageTooLarge { .. }));
    }

    #[test]
    fn non_object_data_is_rejected() {
        let err = encode_command("bad", json!(1), None, 1024).unwrap_err();
        assert!(matches!(err, CoreError::Decode(_)));
    }

    #[test]
    fn parses_model_message() {
        let bytes = DataServerMessage {
            request_id: String::new(),
            kind: MessageKind::Notification as i32,
            payload: Some(data_server_message::Payload::Message(ModelMessage {
                r#type: "emit".into(),
                data: value_to_struct(json!({"data": 1.0})),
            })),
        }
        .encode_to_vec();
        assert_eq!(
            parse_incoming(&bytes).unwrap(),
            IncomingMessage::Application(json!({"type": "emit", "data": {"data": 1.0}}))
        );
    }

    #[test]
    fn parses_server_error() {
        let bytes = DataServerMessage {
            request_id: "req_1".into(),
            kind: MessageKind::Response as i32,
            payload: Some(data_server_message::Payload::Error(
                crate::protocol::wire::v1::common::Error {
                    code: "BAD_COMMAND".into(),
                    message: "unknown command".into(),
                },
            )),
        }
        .encode_to_vec();
        assert_eq!(
            parse_incoming(&bytes).unwrap(),
            IncomingMessage::Error {
                code: "BAD_COMMAND".into(),
                message: "unknown command".into(),
            }
        );
    }
}
