//! Decoding the `StepResult` that answers a step, frame by frame through a
//! registry keyed by encoding.

use std::collections::HashMap;

use super::{StepFile, StepOutput, TrackOutput};
use crate::error::CoreError;
use crate::protocol::session::TrackKind;
use crate::protocol::wire::v1::platform::TrackKind as WireTrackKind;
use crate::protocol::wire::v1::step::{Frame, FrameEncoding, StepFailureCode, StepResult};

/// One decoded frame: the array `data` holds, described by `dtype` and
/// `shape` — `(height, width, 3)` `uint8` RGB for video, `(channels, samples)`
/// `int16` PCM for audio.
#[derive(Debug, Clone, PartialEq)]
pub struct DecodedFrame {
    /// How the frame travelled on the wire, before decoding.
    pub encoding: FrameEncoding,
    /// Element type, in NumPy notation (`"uint8"`, `"int16"`).
    pub dtype: String,
    pub shape: Vec<u32>,
    /// Row-major array bytes, little-endian for multi-byte elements.
    pub data: Vec<u8>,
    /// What the sender attached to the frame, opaque to the transport.
    pub metadata: Option<Vec<u8>>,
    /// When the sender says the frame was captured, in microseconds.
    pub capture_time_us: Option<i64>,
}

/// Turns one wire [`Frame`] of a given encoding into a [`DecodedFrame`].
pub trait FrameDecoder: Send + Sync {
    fn decode(&self, frame: Frame) -> Result<DecodedFrame, CoreError>;
}

/// [`FrameEncoding::Raw`]: the bytes already are the array. Checked against
/// `dtype` and `shape` when the dtype is one this knows the size of.
pub struct RawFrameDecoder;

fn dtype_size(dtype: &str) -> Option<usize> {
    match dtype {
        "uint8" | "int8" | "bool" => Some(1),
        "uint16" | "int16" | "float16" => Some(2),
        "uint32" | "int32" | "float32" => Some(4),
        "uint64" | "int64" | "float64" => Some(8),
        _ => None,
    }
}

impl FrameDecoder for RawFrameDecoder {
    fn decode(&self, frame: Frame) -> Result<DecodedFrame, CoreError> {
        if let Some(size) = dtype_size(&frame.dtype) {
            let expected = frame
                .shape
                .iter()
                .try_fold(size, |n, &d| n.checked_mul(d as usize));
            if expected != Some(frame.data.len()) {
                return Err(CoreError::decode(format!(
                    "raw {} frame of shape {:?} holds {} bytes, expected {}",
                    frame.dtype,
                    frame.shape,
                    frame.data.len(),
                    expected.map_or_else(|| "an overflowing size".to_string(), |n| n.to_string()),
                )));
            }
        }
        Ok(DecodedFrame {
            encoding: FrameEncoding::Raw,
            dtype: frame.dtype,
            shape: frame.shape,
            data: frame.data,
            metadata: frame.metadata,
            capture_time_us: frame.capture_time_us,
        })
    }
}

/// Frame decoders, keyed by [`FrameEncoding`]. [`FrameDecoders::default`]
/// knows [`FrameEncoding::Raw`]; a frame in any other encoding fails the step
/// with [`CoreError::UnsupportedFrameEncoding`].
pub struct FrameDecoders {
    decoders: HashMap<i32, Box<dyn FrameDecoder>>,
}

impl Default for FrameDecoders {
    fn default() -> Self {
        let mut decoders = Self {
            decoders: HashMap::new(),
        };
        decoders.register(FrameEncoding::Raw, Box::new(RawFrameDecoder));
        decoders
    }
}

impl FrameDecoders {
    pub fn register(&mut self, encoding: FrameEncoding, decoder: Box<dyn FrameDecoder>) {
        self.decoders.insert(encoding as i32, decoder);
    }

    pub fn decode_frame(&self, track: &str, frame: Frame) -> Result<DecodedFrame, CoreError> {
        match self.decoders.get(&frame.encoding) {
            Some(decoder) => decoder.decode(frame),
            None => Err(CoreError::UnsupportedFrameEncoding {
                track: track.to_string(),
                encoding: frame.encoding,
            }),
        }
    }

    /// Decode a whole [`StepResult`]. A result whose `failure_code` says the
    /// step did not run, or ran with a result too large to send, is that
    /// failure, with `error` as its message (see the module docs). Otherwise
    /// fails on the first frame that cannot be decoded rather than returning a
    /// partial step.
    pub fn decode_result(&self, result: StepResult) -> Result<StepOutput, CoreError> {
        if let Some(failure) = step_failure(&result) {
            return Err(failure);
        }
        let tracks = result
            .tracks
            .into_iter()
            .map(|track| {
                let kind = match WireTrackKind::try_from(track.kind) {
                    Ok(WireTrackKind::Video) => TrackKind::Video,
                    Ok(WireTrackKind::Audio) => TrackKind::Audio,
                    _ => {
                        return Err(CoreError::decode(format!(
                            "step result track '{}' has unknown kind {}",
                            track.track, track.kind
                        )))
                    }
                };
                let frames = track
                    .frames
                    .into_iter()
                    .map(|frame| self.decode_frame(&track.track, frame))
                    .collect::<Result<_, _>>()?;
                Ok(TrackOutput {
                    track: track.track,
                    kind,
                    frames,
                    rate: track.rate,
                })
            })
            .collect::<Result<_, CoreError>>()?;
        Ok(StepOutput {
            step: result.step,
            tracks,
            elapsed: result.elapsed_seconds,
            files: result.files.into_iter().map(StepFile::from).collect(),
            error: result.error,
        })
    }
}

/// The failure a `StepResult`'s `failure_code` stands for, or `None` when the
/// result is an output — the step worked, or ran and the model reported a
/// failure (`FAILED`, which still resolves with its reason).
fn step_failure(result: &StepResult) -> Option<CoreError> {
    let failure_code = result.failure_code;
    let error = result.error.as_deref();
    let message = |default: &str| error.unwrap_or(default).to_string();
    match StepFailureCode::try_from(failure_code) {
        Ok(StepFailureCode::Unspecified | StepFailureCode::Failed) => None,
        Ok(StepFailureCode::Refused) => Some(CoreError::StepRefused {
            message: message("the runtime refused the step"),
            entry: result.entry.clone(),
        }),
        Ok(StepFailureCode::NotReady) => Some(CoreError::StepNotReady {
            message: message("the session cannot run a step yet"),
        }),
        Ok(StepFailureCode::ResultTooLarge) => Some(CoreError::StepResultTooLarge {
            message: message("the step's result does not fit in one message"),
        }),
        Ok(StepFailureCode::CommandFailed) => Some(CoreError::StepCommandFailed {
            message: message("one of the step's calls failed"),
            command_code: result.code.clone(),
            entry: result.entry.clone(),
        }),
        Ok(StepFailureCode::SessionEnded) => Some(CoreError::StepSessionEnded {
            message: message("the session ended before the step's result was sent"),
        }),
        // A code from a newer runtime: the step's outcome is unknown, so it
        // cannot resolve as a result.
        Err(_) => Some(CoreError::StepFailed {
            code: String::new(),
            message: format!(
                "step failed with unknown failure code {failure_code}: {}",
                error.unwrap_or("no reason given")
            ),
        }),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::wire::v1::step::{StepFileRef, TrackFrames};
    use crate::step::raw;

    #[test]
    fn decodes_a_step_result_per_track() {
        let result = StepResult {
            step: 3,
            tracks: vec![
                TrackFrames {
                    track: "main_video".into(),
                    kind: WireTrackKind::Video as i32,
                    frames: vec![Frame {
                        metadata: Some(b"m".to_vec()),
                        capture_time_us: Some(7),
                        ..raw("uint8", vec![1, 2, 3], vec![0; 6])
                    }],
                    rate: 24.0,
                },
                TrackFrames {
                    track: "main_audio".into(),
                    kind: WireTrackKind::Audio as i32,
                    frames: vec![raw("int16", vec![1, 2], vec![0; 4])],
                    rate: 48_000.0,
                },
            ],
            elapsed_seconds: 0.25,
            files: vec![],
            error: None,
            failure_code: StepFailureCode::Unspecified as i32,
            code: None,
            entry: None,
        };
        let output = FrameDecoders::default().decode_result(result).unwrap();
        assert_eq!(output.step, 3);
        assert_eq!(output.elapsed, 0.25);
        assert_eq!(output.tracks[0].track, "main_video");
        assert_eq!(output.tracks[0].kind, TrackKind::Video);
        assert_eq!(output.tracks[0].rate, 24.0);
        let frame = &output.tracks[0].frames[0];
        assert_eq!(frame.encoding, FrameEncoding::Raw);
        assert_eq!(frame.shape, [1, 2, 3]);
        assert_eq!(frame.data.len(), 6);
        assert_eq!(frame.metadata.as_deref(), Some(&b"m"[..]));
        assert_eq!(frame.capture_time_us, Some(7));
        assert_eq!(output.tracks[1].kind, TrackKind::Audio);
        assert_eq!(output.tracks[1].rate, 48_000.0);
        assert_eq!(output.tracks[1].frames[0].dtype, "int16");
        assert!(output.files.is_empty());
        assert!(output.error.is_none());
    }

    /// A step that ran and failed is still a result: its frames and files, as
    /// far as it got, with the model's reason beside them.
    #[test]
    fn a_failed_step_is_a_result_that_says_why() {
        let result = StepResult {
            step: 5,
            tracks: vec![],
            elapsed_seconds: 0.1,
            files: vec![],
            error: Some("out of memory".into()),
            failure_code: StepFailureCode::Failed as i32,
            code: None,
            entry: None,
        };
        let output = FrameDecoders::default().decode_result(result).unwrap();
        assert_eq!(output.step, 5);
        assert_eq!(output.error.as_deref(), Some("out of memory"));
    }

    /// A step that did not run, or whose result was too large to send, is no
    /// A refused or failed call names the request entry at fault, and a
    /// failed handler its own code, so a caller branches on fields.
    #[test]
    fn a_failure_carries_its_entry_and_the_handlers_code() {
        let decoders = FrameDecoders::default();
        let refused = StepResult {
            failure_code: StepFailureCode::Refused as i32,
            error: Some("commands[0] is refused: text".into()),
            entry: Some("commands[0]".into()),
            ..Default::default()
        };
        match decoders.decode_result(refused) {
            Err(CoreError::StepRefused { entry, .. }) => {
                assert_eq!(entry.as_deref(), Some("commands[0]"))
            }
            other => panic!("expected StepRefused, got {other:?}"),
        }
        let failed = StepResult {
            failure_code: StepFailureCode::CommandFailed as i32,
            error: Some("tag failed (queue_full): full".into()),
            code: Some("queue_full".into()),
            entry: Some("commands[2]".into()),
            ..Default::default()
        };
        match decoders.decode_result(failed) {
            Err(CoreError::StepCommandFailed {
                command_code,
                entry,
                ..
            }) => {
                assert_eq!(command_code.as_deref(), Some("queue_full"));
                assert_eq!(entry.as_deref(), Some("commands[2]"));
            }
            other => panic!("expected StepCommandFailed, got {other:?}"),
        }
    }

    /// output: it is its own failure, with the runtime's reason as the message
    /// (or a stand-in when it gave none).
    #[test]
    fn a_failure_code_is_its_own_error() {
        let result = |failure_code: StepFailureCode, error: Option<&str>| StepResult {
            step: 0,
            tracks: vec![],
            elapsed_seconds: 0.0,
            files: vec![],
            error: error.map(str::to_string),
            failure_code: failure_code as i32,
            code: None,
            entry: None,
        };
        let decoders = FrameDecoders::default();
        match decoders.decode_result(result(StepFailureCode::Refused, Some("bad command"))) {
            Err(CoreError::StepRefused { message, entry }) => {
                assert_eq!(message, "bad command");
                assert_eq!(entry, None);
            }
            other => panic!("expected StepRefused, got {other:?}"),
        }
        match decoders.decode_result(result(StepFailureCode::NotReady, Some("starting"))) {
            Err(CoreError::StepNotReady { message }) => assert_eq!(message, "starting"),
            other => panic!("expected StepNotReady, got {other:?}"),
        }
        match decoders.decode_result(result(StepFailureCode::ResultTooLarge, None)) {
            Err(CoreError::StepResultTooLarge { message }) => assert!(!message.is_empty()),
            other => panic!("expected StepResultTooLarge, got {other:?}"),
        }
        match decoders.decode_result(result(
            StepFailureCode::CommandFailed,
            Some("set_prompt failed (bad): no"),
        )) {
            Err(CoreError::StepCommandFailed { message, .. }) => {
                assert_eq!(message, "set_prompt failed (bad): no")
            }
            other => panic!("expected StepCommandFailed, got {other:?}"),
        }
        match decoders.decode_result(result(StepFailureCode::SessionEnded, None)) {
            Err(CoreError::StepSessionEnded { message }) => assert!(!message.is_empty()),
            other => panic!("expected StepSessionEnded, got {other:?}"),
        }
        match decoders.decode_result(StepResult {
            failure_code: 99,
            ..result(StepFailureCode::Unspecified, Some("future"))
        }) {
            Err(CoreError::StepFailed { message, .. }) => assert!(message.contains("future")),
            other => panic!("expected StepFailed, got {other:?}"),
        }
    }

    #[test]
    fn decodes_the_files_a_step_kept_in_order() {
        let result = StepResult {
            step: 2,
            tracks: vec![],
            elapsed_seconds: 1.5,
            files: vec![
                StepFileRef {
                    name: "depth.npy".into(),
                    content_type: "application/octet-stream".into(),
                    size: 4096,
                },
                StepFileRef {
                    name: "caption.txt".into(),
                    content_type: "text/plain".into(),
                    size: u64::MAX,
                },
            ],
            error: None,
            failure_code: StepFailureCode::Unspecified as i32,
            code: None,
            entry: None,
        };
        let output = FrameDecoders::default().decode_result(result).unwrap();
        assert_eq!(output.elapsed, 1.5);
        assert_eq!(
            output.files,
            [
                StepFile {
                    name: "depth.npy".into(),
                    content_type: "application/octet-stream".into(),
                    size: 4096,
                },
                StepFile {
                    name: "caption.txt".into(),
                    content_type: "text/plain".into(),
                    size: u64::MAX,
                },
            ]
        );
    }

    /// The files (`StepResult.files`, field 4) and the failure
    /// (`StepResult.error`, field 5) come back out of a real protobuf round
    /// trip, not just a struct literal.
    #[test]
    fn files_survive_the_wire_encoding() {
        use prost::Message;

        let bytes = StepResult {
            step: 1,
            tracks: vec![],
            elapsed_seconds: 0.5,
            files: vec![StepFileRef {
                name: "mask.png".into(),
                content_type: "image/png".into(),
                size: 12,
            }],
            error: Some("partial".into()),
            failure_code: StepFailureCode::Failed as i32,
            code: None,
            entry: None,
        }
        .encode_to_vec();
        let decoded = StepResult::decode(bytes.as_slice()).unwrap();
        assert_eq!(decoded.error.as_deref(), Some("partial"));
        let output = FrameDecoders::default().decode_result(decoded).unwrap();
        assert_eq!(output.error.as_deref(), Some("partial"));
        assert_eq!(
            output.files,
            [StepFile {
                name: "mask.png".into(),
                content_type: "image/png".into(),
                size: 12,
            }]
        );
    }

    #[test]
    fn an_unknown_encoding_is_a_typed_error() {
        let result = StepResult {
            step: 1,
            tracks: vec![TrackFrames {
                track: "v".into(),
                kind: WireTrackKind::Video as i32,
                frames: vec![Frame {
                    encoding: 99,
                    ..raw("uint8", vec![1], vec![0])
                }],
                rate: 0.0,
            }],
            elapsed_seconds: 0.0,
            files: vec![],
            error: None,
            failure_code: StepFailureCode::Unspecified as i32,
            code: None,
            entry: None,
        };
        match FrameDecoders::default().decode_result(result) {
            Err(CoreError::UnsupportedFrameEncoding { track, encoding }) => {
                assert_eq!(track, "v");
                assert_eq!(encoding, 99);
            }
            other => panic!("expected UnsupportedFrameEncoding, got {other:?}"),
        }

        let unspecified = FrameDecoders::default().decode_frame(
            "v",
            Frame {
                encoding: FrameEncoding::Unspecified as i32,
                ..raw("uint8", vec![1], vec![0])
            },
        );
        assert!(matches!(
            unspecified,
            Err(CoreError::UnsupportedFrameEncoding { encoding: 0, .. })
        ));
    }

    #[test]
    fn a_raw_frame_whose_bytes_disagree_with_its_shape_fails() {
        let decoded =
            FrameDecoders::default().decode_frame("v", raw("int16", vec![2, 2], vec![0; 6]));
        assert!(matches!(decoded, Err(CoreError::Decode(_))));
        // A dtype this does not know the size of passes through unchecked.
        assert!(FrameDecoders::default()
            .decode_frame("v", raw("complex64", vec![1], vec![0; 3]))
            .is_ok());
    }

    #[test]
    fn a_registered_decoder_handles_its_encoding() {
        struct Upper;
        impl FrameDecoder for Upper {
            fn decode(&self, frame: Frame) -> Result<DecodedFrame, CoreError> {
                Ok(DecodedFrame {
                    encoding: FrameEncoding::Unspecified,
                    dtype: "uint8".into(),
                    shape: frame.shape,
                    data: frame.data,
                    metadata: None,
                    capture_time_us: None,
                })
            }
        }
        let mut decoders = FrameDecoders::default();
        decoders.register(FrameEncoding::Unspecified, Box::new(Upper));
        assert!(decoders
            .decode_frame(
                "v",
                Frame {
                    encoding: 0,
                    ..raw("uint8", vec![1], vec![0])
                }
            )
            .is_ok());
    }
}
