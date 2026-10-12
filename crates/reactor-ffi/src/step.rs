//! The owned side of `reactor_step`: what a step's completion hands the host,
//! and the parsing of what the host hands in.
//!
//! A [`ReactorStepResult`] is built once, on the tokio worker that decoded the
//! step, and given to the host as an opaque pointer it frees with
//! `reactor_step_result_free`. Every accessor borrows from it, so a frame's
//! bytes are moved in from the core's [`DecodedFrame`] and never copied again:
//! reading `data` twice returns the same pointer.

use std::ffi::{CStr, CString};

use reactor_core::error::CoreError;
use reactor_core::protocol::session::TrackKind;
use reactor_core::protocol::wire::v1::step::FrameEncoding;
use reactor_core::step::{DecodedFrame, StepCommand, StepOutput, TrackOutput};
use serde_json::Value;

/// One step's output, owned by the host between the completion and
/// `reactor_step_result_free`.
pub struct ReactorStepResult {
    pub(crate) index: u64,
    pub(crate) tracks: Vec<StepTrack>,
}

pub(crate) struct StepTrack {
    pub(crate) name: CString,
    pub(crate) kind: &'static CStr,
    pub(crate) frames: Vec<StepFrame>,
}

pub(crate) struct StepFrame {
    pub(crate) encoding: &'static CStr,
    pub(crate) dtype: CString,
    pub(crate) shape: Vec<u32>,
    pub(crate) data: Vec<u8>,
    pub(crate) metadata: Option<Vec<u8>>,
    pub(crate) capture_time_us: Option<i64>,
}

/// A C string from text the runtime chose. An interior NUL cannot cross the
/// boundary, so it is dropped rather than truncating everything after it.
fn c_string(text: String) -> CString {
    CString::new(text).unwrap_or_else(|e| {
        let mut bytes = e.into_vec();
        bytes.retain(|b| *b != 0);
        CString::new(bytes).unwrap_or_default()
    })
}

fn kind_str(kind: TrackKind) -> &'static CStr {
    match kind {
        TrackKind::Video => c"video",
        TrackKind::Audio => c"audio",
    }
}

fn encoding_str(encoding: FrameEncoding) -> &'static CStr {
    match encoding {
        FrameEncoding::Raw => c"raw",
        FrameEncoding::Unspecified => c"unspecified",
    }
}

impl From<StepOutput> for ReactorStepResult {
    fn from(output: StepOutput) -> Self {
        Self {
            index: output.index,
            tracks: output
                .tracks
                .into_iter()
                .map(
                    |TrackOutput {
                         track,
                         kind,
                         frames,
                     }| StepTrack {
                        name: c_string(track),
                        kind: kind_str(kind),
                        frames: frames.into_iter().map(StepFrame::from).collect(),
                    },
                )
                .collect(),
        }
    }
}

impl From<DecodedFrame> for StepFrame {
    fn from(frame: DecodedFrame) -> Self {
        Self {
            encoding: encoding_str(frame.encoding),
            dtype: c_string(frame.dtype),
            shape: frame.shape,
            data: frame.data,
            metadata: frame.metadata,
            capture_time_us: frame.capture_time_us,
        }
    }
}

impl ReactorStepResult {
    pub(crate) fn track(&self, track: u32) -> Option<&StepTrack> {
        self.tracks.get(track as usize)
    }

    pub(crate) fn frame(&self, track: u32, frame: u32) -> Option<&StepFrame> {
        self.track(track)?.frames.get(frame as usize)
    }
}

/// `state_json` as the core takes it: absent is `null`, which runs no state.
pub(crate) fn parse_state(state_json: Option<&str>) -> Result<Value, CoreError> {
    match state_json {
        None => Ok(Value::Null),
        Some(raw) => serde_json::from_str(raw)
            .map_err(|e| CoreError::decode(format!("state_json is not valid JSON: {e}"))),
    }
}

/// `commands_json` as the core takes it: a JSON array of `{command, data}`,
/// absent meaning none. `data` may be omitted or `null` for a command without
/// arguments; whether it is an object is the core's check, not this one's.
pub(crate) fn parse_commands(commands_json: Option<&str>) -> Result<Vec<StepCommand>, CoreError> {
    let Some(raw) = commands_json else {
        return Ok(Vec::new());
    };
    let value: Value = serde_json::from_str(raw)
        .map_err(|e| CoreError::decode(format!("commands_json is not valid JSON: {e}")))?;
    let Value::Array(entries) = value else {
        return Err(CoreError::decode(
            "commands_json must be a JSON array of {\"command\", \"data\"} objects",
        ));
    };
    entries
        .into_iter()
        .enumerate()
        .map(|(i, entry)| {
            let Value::Object(mut fields) = entry else {
                return Err(CoreError::decode(format!(
                    "commands_json[{i}] must be an object of {{\"command\", \"data\"}}"
                )));
            };
            let command = match fields.remove("command") {
                Some(Value::String(name)) if !name.is_empty() => name,
                _ => {
                    return Err(CoreError::decode(format!(
                        "commands_json[{i}] needs a non-empty string \"command\""
                    )))
                }
            };
            let data = fields.remove("data").unwrap_or(Value::Null);
            // A misspelt "data" would otherwise run the command with no
            // arguments and report success.
            if let Some(key) = fields.keys().next() {
                return Err(CoreError::decode(format!(
                    "commands_json[{i}] has an unknown key \"{key}\" (expected \"command\" and \"data\")"
                )));
            }
            Ok(StepCommand::new(command, data))
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn frame(data: Vec<u8>) -> DecodedFrame {
        DecodedFrame {
            encoding: FrameEncoding::Raw,
            dtype: "uint8".into(),
            shape: vec![1, 2, 3],
            data,
            metadata: Some(b"tag".to_vec()),
            capture_time_us: Some(42),
        }
    }

    /// The frame's bytes are moved, not copied: the host reads the very buffer
    /// the decoder produced.
    #[test]
    fn a_result_owns_the_decoded_bytes_without_copying_them() {
        let bytes = vec![7u8; 6];
        let address = bytes.as_ptr();
        let result = ReactorStepResult::from(StepOutput {
            index: 3,
            tracks: vec![TrackOutput {
                track: "main_video".into(),
                kind: TrackKind::Video,
                frames: vec![frame(bytes)],
            }],
        });
        assert_eq!(result.index, 3);
        let track = result.track(0).unwrap();
        assert_eq!(track.name.to_str().unwrap(), "main_video");
        assert_eq!(track.kind, c"video");
        let f = result.frame(0, 0).unwrap();
        assert_eq!(f.data.as_ptr(), address);
        assert_eq!(f.encoding, c"raw");
        assert_eq!(f.dtype.to_str().unwrap(), "uint8");
        assert_eq!(f.shape, vec![1, 2, 3]);
        assert_eq!(f.metadata.as_deref(), Some(&b"tag"[..]));
        assert_eq!(f.capture_time_us, Some(42));
        assert!(result.frame(0, 1).is_none());
        assert!(result.track(1).is_none());
    }

    #[test]
    fn a_track_name_with_a_nul_keeps_the_rest_of_the_name() {
        assert_eq!(c_string("a\0b".into()).to_str().unwrap(), "ab");
    }

    #[test]
    fn absent_state_and_commands_mean_none() {
        assert_eq!(parse_state(None).unwrap(), Value::Null);
        assert!(parse_commands(None).unwrap().is_empty());
    }

    #[test]
    fn commands_parse_in_order_with_optional_data() {
        let commands = parse_commands(Some(
            r#"[{"command":"set_prompt","data":{"prompt":"hi"}},{"command":"reset"}]"#,
        ))
        .unwrap();
        assert_eq!(
            commands,
            vec![
                StepCommand::new("set_prompt", json!({"prompt": "hi"})),
                StepCommand::new("reset", Value::Null),
            ]
        );
    }

    /// Every malformed shape is a typed refusal, before anything is sent.
    #[test]
    fn malformed_commands_are_refused_by_what_is_wrong() {
        for (raw, needle) in [
            ("not json", "not valid JSON"),
            (r#"{"command":"x"}"#, "must be a JSON array"),
            (r#"["x"]"#, "commands_json[0] must be an object"),
            (r#"[{"data":{}}]"#, "non-empty string \"command\""),
            (r#"[{"command":""}]"#, "non-empty string \"command\""),
            (r#"[{"command":"x","args":{}}]"#, "unknown key \"args\""),
        ] {
            let error = parse_commands(Some(raw)).unwrap_err();
            assert_eq!(error.code(), reactor_core::error::codes::DECODE_FAILED);
            assert!(error.to_string().contains(needle), "{raw}: {error}");
        }
        let error = parse_state(Some("{")).unwrap_err();
        assert_eq!(error.code(), reactor_core::error::codes::DECODE_FAILED);
    }
}
