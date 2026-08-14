//! Data-channel command encoding (`reactor_wire.v1`, protobuf).
//!
//! The data channel carries only application-scoped traffic: model commands
//! (client → runtime) and model emissions (runtime → client). Runtime/
//! platform traffic (ping, clip/recording requests, track control, ...)
//! travels on the control channel instead — see [`crate::control`].
//!
//! Correlating a command with its reply (rather than firing it and
//! forgetting) lives in [`crate::data::DataCorrelator`].

use std::collections::{BTreeMap, HashMap};

use serde_json::Value;

use crate::error::CoreError;
use crate::protocol::upload::FileRef;
use crate::protocol::wire::struct_convert::value_to_struct;
use crate::protocol::wire::v1::data::data_client_message;
use crate::protocol::wire::v1::model::{Command, UploadReference};

/// Build the `Command` payload for an outbound application command.
pub fn build_command_payload(
    command: &str,
    data: Value,
    uploads: Option<BTreeMap<String, FileRef>>,
) -> Result<data_client_message::Payload, CoreError> {
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
    Ok(data_client_message::Payload::Command(Command {
        r#type: command.to_string(),
        data: Some(data),
        uploads,
    }))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::wire::struct_convert::struct_to_value;
    use serde_json::json;

    fn as_command(payload: data_client_message::Payload) -> Command {
        match payload {
            data_client_message::Payload::Command(c) => c,
            other => panic!("expected Command, got {other:?}"),
        }
    }

    #[test]
    fn builds_a_basic_command_payload() {
        let payload = build_command_payload("set_prompt", json!({"prompt": "a cat"}), None)
            .map(as_command)
            .unwrap();
        assert_eq!(payload.r#type, "set_prompt");
        assert_eq!(
            struct_to_value(payload.data.unwrap()),
            json!({"prompt": "a cat"})
        );
        assert!(payload.uploads.is_empty());
    }

    #[test]
    fn builds_a_command_payload_with_uploads() {
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
        let payload = build_command_payload("set_image", json!({}), Some(uploads))
            .map(as_command)
            .unwrap();
        assert_eq!(payload.uploads["image"].upload_id, "up_9");
    }

    #[test]
    fn non_object_data_is_rejected() {
        let err = build_command_payload("bad", json!(1), None).unwrap_err();
        assert!(matches!(err, CoreError::Decode(_)));
    }
}
