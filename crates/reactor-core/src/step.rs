//! Stepped generation: building a `RequestStep`, buffering its input frames,
//! and decoding the `StepResult` that answers it (`reactor_wire.v1`).
//!
//! A stepped session runs one step per request. The client sends one
//! `RequestStep` on the data channel — state, then commands, then the input
//! frames buffered per track since the previous step — and gets exactly one
//! reply with the same `request_id`: a `StepResult`, or an `Error`
//! (`step_refused`, `step_result_too_large`, ...). Nothing else is sent for a
//! step: the result is its one answer, and an error is its refusal. A step
//! that ran but that the model reported as failed is still a result, with the
//! reason in [`StepOutput::error`].
//!
//! A result names the extra files the model kept with the step
//! ([`StepFile`]) but does not carry them; their bytes are fetched over HTTP
//! from the session's step results (`GET /sessions/{id}/steps/{index}`), as
//! in a streamed session.
//!
//! Frames travel as [`FrameEncoding::Raw`] today. Decoding goes through
//! [`FrameDecoders`], keyed by encoding, so a compressed encoding is a new
//! registry entry rather than a change to the result type.

use std::collections::HashMap;
use std::sync::Mutex;

use serde_json::Value;

use crate::error::CoreError;
use crate::protocol::session::TrackKind;
use crate::protocol::wire::struct_convert::value_to_struct;
use crate::protocol::wire::v1::model::Command;
use crate::protocol::wire::v1::platform::TrackKind as WireTrackKind;
use crate::protocol::wire::v1::step::{
    Frame, FrameEncoding, RequestStep, StepFileRef, StepResult, TrackFrames,
};

/// `dtype` of a raw video frame: RGB, one byte per channel.
pub const VIDEO_DTYPE: &str = "uint8";
/// `dtype` of a raw audio frame: signed 16-bit PCM.
pub const AUDIO_DTYPE: &str = "int16";

/// One command of a step, run after the step's state, in order. The same
/// `{command, data}` pair a session's `starting_input` takes.
#[derive(Debug, Clone, PartialEq)]
pub struct StepCommand {
    pub command: String,
    /// The command's arguments: a JSON object, or `null` for none.
    pub data: Value,
}

impl StepCommand {
    pub fn new(command: impl Into<String>, data: Value) -> Self {
        Self {
            command: command.into(),
            data,
        }
    }
}

/// Build the `RequestStep` for `state`, `commands` and the buffered `inputs`.
///
/// `state` is a JSON object of the model's state fields (each key runs as the
/// model's `set_<key>` command, before `commands`), or `null` for none.
pub fn build_request_step(
    state: Value,
    commands: Vec<StepCommand>,
    inputs: Vec<TrackFrames>,
) -> Result<RequestStep, CoreError> {
    let state = match state {
        Value::Null => None,
        state => Some(
            value_to_struct(state)
                .ok_or_else(|| CoreError::decode("step state must be a JSON object"))?,
        ),
    };
    let commands = commands
        .into_iter()
        .map(|StepCommand { command, data }| {
            let data = match data {
                Value::Null => None,
                data => Some(value_to_struct(data).ok_or_else(|| {
                    CoreError::decode(format!(
                        "data of step command '{command}' must be a JSON object"
                    ))
                })?),
            };
            Ok(Command {
                r#type: command,
                data,
                uploads: Default::default(),
            })
        })
        .collect::<Result<_, CoreError>>()?;
    Ok(RequestStep {
        state,
        commands,
        inputs,
    })
}

// ── Input buffering ─────────────────────────────────────────────────────────

/// Input frames pushed since the last step, per track.
///
/// Tracks keep the order of their first push, and frames the order they were
/// pushed in. [`StepInputs::take`] empties the buffer for a step;
/// [`StepInputs::restore`] puts frames back in front when that step could not
/// be sent, so nothing pushed in the meantime is lost or reordered.
#[derive(Default)]
pub struct StepInputs {
    tracks: Mutex<Vec<TrackFrames>>,
}

impl StepInputs {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn push(&self, track: &str, kind: WireTrackKind, frame: Frame) {
        let mut tracks = self.tracks.lock().unwrap();
        match tracks.iter_mut().find(|t| t.track == track) {
            Some(existing) => existing.frames.push(frame),
            None => tracks.push(TrackFrames {
                track: track.to_string(),
                kind: kind as i32,
                frames: vec![frame],
            }),
        }
    }

    /// Everything buffered, leaving the buffer empty.
    pub fn take(&self) -> Vec<TrackFrames> {
        std::mem::take(&mut *self.tracks.lock().unwrap())
    }

    /// Put `taken` back ahead of whatever was pushed since it was taken.
    pub fn restore(&self, taken: Vec<TrackFrames>) {
        let mut tracks = self.tracks.lock().unwrap();
        let newer = std::mem::replace(&mut *tracks, taken);
        for mut later in newer {
            match tracks.iter_mut().find(|t| t.track == later.track) {
                Some(existing) => existing.frames.append(&mut later.frames),
                None => tracks.push(later),
            }
        }
    }

    pub fn clear(&self) {
        self.tracks.lock().unwrap().clear();
    }

    /// Number of frames buffered across all tracks.
    pub fn len(&self) -> usize {
        self.tracks
            .lock()
            .unwrap()
            .iter()
            .map(|t| t.frames.len())
            .sum()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }
}

/// A raw video frame from the BGRA pixels the push API takes: RGB `uint8`,
/// shape `(height, width, 3)`. `None` when `bgra` is not `width * height * 4`
/// bytes.
pub fn raw_video_frame(
    bgra: &[u8],
    width: u32,
    height: u32,
    metadata: Option<&[u8]>,
    capture_time_us: Option<i64>,
) -> Option<Frame> {
    let pixels = (width as usize).checked_mul(height as usize)?;
    if bgra.len() != pixels.checked_mul(4)? {
        return None;
    }
    let mut rgb = Vec::with_capacity(pixels * 3);
    for px in bgra.chunks_exact(4) {
        rgb.extend_from_slice(&[px[2], px[1], px[0]]);
    }
    Some(Frame {
        encoding: FrameEncoding::Raw as i32,
        dtype: VIDEO_DTYPE.to_string(),
        shape: vec![height, width, 3],
        data: rgb,
        metadata: metadata.filter(|m| !m.is_empty()).map(<[u8]>::to_vec),
        capture_time_us,
    })
}

/// A raw audio frame from the interleaved PCM the push API takes: `int16`
/// little-endian, planar, shape `(channels, samples)`. `None` when
/// `num_channels` is zero or does not divide the sample count.
///
/// The wire frame has no sample rate; the runtime reads the track at the rate
/// its model declares.
pub fn raw_audio_frame(interleaved: &[i16], num_channels: u32) -> Option<Frame> {
    let channels = num_channels as usize;
    if channels == 0 || !interleaved.len().is_multiple_of(channels) {
        return None;
    }
    let samples = interleaved.len() / channels;
    let mut data = Vec::with_capacity(interleaved.len() * 2);
    for channel in 0..channels {
        for sample in interleaved.iter().skip(channel).step_by(channels) {
            data.extend_from_slice(&sample.to_le_bytes());
        }
    }
    Some(Frame {
        encoding: FrameEncoding::Raw as i32,
        dtype: AUDIO_DTYPE.to_string(),
        shape: vec![num_channels, u32::try_from(samples).ok()?],
        data,
        metadata: None,
        capture_time_us: None,
    })
}

// ── Result decoding ─────────────────────────────────────────────────────────

/// The outcome of one step: what the model produced, per output track.
#[derive(Debug, Clone, PartialEq)]
pub struct StepOutput {
    /// The step's number in the session, counted from 1.
    pub index: u64,
    pub tracks: Vec<TrackOutput>,
    /// Seconds the step's `generate()` took, or the time the model reported
    /// for the step.
    pub elapsed: f64,
    /// The extra files the model kept with the step, in the order the runtime
    /// listed them. Empty when the step kept none, or when its result was not
    /// saved.
    pub files: Vec<StepFile>,
    /// Why the step failed, when it ran and the model reported a failure;
    /// `None` when it worked. A refused step never runs: it gets no result,
    /// and [`crate::reactor::Reactor::step`] fails with
    /// [`CoreError::StepRefused`] instead.
    pub error: Option<String>,
}

/// A reference to one extra file a model kept with a step. The result carries
/// the reference, not the bytes: they are fetched over HTTP from the session's
/// step results (`GET /sessions/{id}/steps/{index}`), by the step's index and
/// the file's name.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StepFile {
    /// The file's name, one path segment.
    pub name: String,
    /// The file's media type, guessed from its name.
    pub content_type: String,
    /// The file's size in bytes.
    pub size: u64,
}

impl From<StepFileRef> for StepFile {
    fn from(file: StepFileRef) -> Self {
        Self {
            name: file.name,
            content_type: file.content_type,
            size: file.size,
        }
    }
}

/// The frames one output track produced in a step, in production order.
#[derive(Debug, Clone, PartialEq)]
pub struct TrackOutput {
    pub track: String,
    pub kind: TrackKind,
    pub frames: Vec<DecodedFrame>,
}

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

    /// Decode a whole [`StepResult`]. Fails on the first frame that cannot be
    /// decoded rather than returning a partial step.
    pub fn decode_result(&self, result: StepResult) -> Result<StepOutput, CoreError> {
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
                })
            })
            .collect::<Result<_, CoreError>>()?;
        Ok(StepOutput {
            index: result.index,
            tracks,
            elapsed: result.elapsed,
            files: result.files.into_iter().map(StepFile::from).collect(),
            error: result.error,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::wire::struct_convert::struct_to_value;
    use serde_json::json;

    fn raw(dtype: &str, shape: Vec<u32>, data: Vec<u8>) -> Frame {
        Frame {
            encoding: FrameEncoding::Raw as i32,
            dtype: dtype.into(),
            shape,
            data,
            metadata: None,
            capture_time_us: None,
        }
    }

    #[test]
    fn builds_state_commands_and_inputs() {
        let inputs = vec![TrackFrames {
            track: "cam".into(),
            kind: WireTrackKind::Video as i32,
            frames: vec![raw("uint8", vec![1, 1, 3], vec![1, 2, 3])],
        }];
        let request = build_request_step(
            json!({"prompt": "a cat"}),
            vec![
                StepCommand::new("reset", Value::Null),
                StepCommand::new("set_style", json!({"style": "ink"})),
            ],
            inputs.clone(),
        )
        .unwrap();
        assert_eq!(
            struct_to_value(request.state.unwrap()),
            json!({"prompt": "a cat"})
        );
        assert_eq!(request.commands.len(), 2);
        assert_eq!(request.commands[0].r#type, "reset");
        assert!(request.commands[0].data.is_none());
        assert_eq!(
            struct_to_value(request.commands[1].data.clone().unwrap()),
            json!({"style": "ink"})
        );
        assert_eq!(request.inputs, inputs);
    }

    #[test]
    fn null_state_is_no_state_and_non_objects_are_rejected() {
        let request = build_request_step(Value::Null, vec![], vec![]).unwrap();
        assert!(request.state.is_none());
        assert!(build_request_step(json!(1), vec![], vec![]).is_err());
        assert!(
            build_request_step(Value::Null, vec![StepCommand::new("x", json!([1]))], vec![])
                .is_err()
        );
    }

    #[test]
    fn inputs_keep_push_order_per_track_and_take_empties() {
        let inputs = StepInputs::new();
        inputs.push("a", WireTrackKind::Video, raw("uint8", vec![1], vec![1]));
        inputs.push(
            "b",
            WireTrackKind::Audio,
            raw("int16", vec![1, 1], vec![0, 0]),
        );
        inputs.push("a", WireTrackKind::Video, raw("uint8", vec![1], vec![2]));
        assert_eq!(inputs.len(), 3);

        let taken = inputs.take();
        assert!(inputs.is_empty());
        assert_eq!(
            taken.iter().map(|t| t.track.as_str()).collect::<Vec<_>>(),
            ["a", "b"]
        );
        assert_eq!(taken[0].kind, WireTrackKind::Video as i32);
        assert_eq!(taken[1].kind, WireTrackKind::Audio as i32);
        assert_eq!(
            taken[0]
                .frames
                .iter()
                .map(|f| f.data.clone())
                .collect::<Vec<_>>(),
            [vec![1], vec![2]]
        );
    }

    #[test]
    fn restore_puts_frames_back_ahead_of_newer_pushes() {
        let inputs = StepInputs::new();
        inputs.push("a", WireTrackKind::Video, raw("uint8", vec![1], vec![1]));
        let taken = inputs.take();
        inputs.push("b", WireTrackKind::Video, raw("uint8", vec![1], vec![9]));
        inputs.push("a", WireTrackKind::Video, raw("uint8", vec![1], vec![2]));
        inputs.restore(taken);

        let all = inputs.take();
        assert_eq!(all[0].track, "a");
        assert_eq!(
            all[0].frames.iter().map(|f| f.data[0]).collect::<Vec<_>>(),
            [1, 2]
        );
        assert_eq!(all[1].track, "b");
    }

    #[test]
    fn video_frames_become_rgb_height_width_3() {
        // 2x1 BGRA: blue pixel, then red pixel.
        let bgra = [255, 0, 0, 255, 0, 0, 255, 255];
        let frame = raw_video_frame(&bgra, 2, 1, Some(b"tag"), Some(42)).unwrap();
        assert_eq!(frame.encoding, FrameEncoding::Raw as i32);
        assert_eq!(frame.dtype, "uint8");
        assert_eq!(frame.shape, [1, 2, 3]);
        assert_eq!(frame.data, [0, 0, 255, 255, 0, 0]);
        assert_eq!(frame.metadata.as_deref(), Some(&b"tag"[..]));
        assert_eq!(frame.capture_time_us, Some(42));

        assert!(raw_video_frame(&bgra, 3, 1, None, None).is_none());
        let untagged = raw_video_frame(&bgra, 2, 1, Some(b""), None).unwrap();
        assert!(untagged.metadata.is_none());
    }

    #[test]
    fn audio_frames_become_planar_channels_by_samples() {
        // Interleaved stereo: (L0, R0), (L1, R1).
        let frame = raw_audio_frame(&[1, -1, 2, -2], 2).unwrap();
        assert_eq!(frame.dtype, "int16");
        assert_eq!(frame.shape, [2, 2]);
        let samples: Vec<i16> = frame
            .data
            .chunks_exact(2)
            .map(|b| i16::from_le_bytes([b[0], b[1]]))
            .collect();
        assert_eq!(samples, [1, 2, -1, -2]);

        assert!(raw_audio_frame(&[1, 2, 3], 2).is_none());
        assert!(raw_audio_frame(&[1], 0).is_none());
    }

    #[test]
    fn decodes_a_step_result_per_track() {
        let result = StepResult {
            index: 3,
            tracks: vec![
                TrackFrames {
                    track: "main_video".into(),
                    kind: WireTrackKind::Video as i32,
                    frames: vec![Frame {
                        metadata: Some(b"m".to_vec()),
                        capture_time_us: Some(7),
                        ..raw("uint8", vec![1, 2, 3], vec![0; 6])
                    }],
                },
                TrackFrames {
                    track: "main_audio".into(),
                    kind: WireTrackKind::Audio as i32,
                    frames: vec![raw("int16", vec![1, 2], vec![0; 4])],
                },
            ],
            elapsed: 0.25,
            files: vec![],
            error: None,
        };
        let output = FrameDecoders::default().decode_result(result).unwrap();
        assert_eq!(output.index, 3);
        assert_eq!(output.elapsed, 0.25);
        assert_eq!(output.tracks[0].track, "main_video");
        assert_eq!(output.tracks[0].kind, TrackKind::Video);
        let frame = &output.tracks[0].frames[0];
        assert_eq!(frame.encoding, FrameEncoding::Raw);
        assert_eq!(frame.shape, [1, 2, 3]);
        assert_eq!(frame.data.len(), 6);
        assert_eq!(frame.metadata.as_deref(), Some(&b"m"[..]));
        assert_eq!(frame.capture_time_us, Some(7));
        assert_eq!(output.tracks[1].kind, TrackKind::Audio);
        assert_eq!(output.tracks[1].frames[0].dtype, "int16");
        assert!(output.files.is_empty());
        assert!(output.error.is_none());
    }

    /// A step that ran and failed is still a result: its frames and files, as
    /// far as it got, with the model's reason beside them.
    #[test]
    fn a_failed_step_is_a_result_that_says_why() {
        let result = StepResult {
            index: 5,
            tracks: vec![],
            elapsed: 0.1,
            files: vec![],
            error: Some("out of memory".into()),
        };
        let output = FrameDecoders::default().decode_result(result).unwrap();
        assert_eq!(output.index, 5);
        assert_eq!(output.error.as_deref(), Some("out of memory"));
    }

    #[test]
    fn decodes_the_files_a_step_kept_in_order() {
        let result = StepResult {
            index: 2,
            tracks: vec![],
            elapsed: 1.5,
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
            index: 1,
            tracks: vec![],
            elapsed: 0.5,
            files: vec![StepFileRef {
                name: "mask.png".into(),
                content_type: "image/png".into(),
                size: 12,
            }],
            error: Some("partial".into()),
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
            index: 1,
            tracks: vec![TrackFrames {
                track: "v".into(),
                kind: WireTrackKind::Video as i32,
                frames: vec![Frame {
                    encoding: 99,
                    ..raw("uint8", vec![1], vec![0])
                }],
            }],
            elapsed: 0.0,
            files: vec![],
            error: None,
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
