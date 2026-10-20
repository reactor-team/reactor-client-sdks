//! Stepped generation: building a `RequestStep`, buffering its input frames,
//! and decoding the `StepResult` that answers it (`reactor_wire.v1`).
//!
//! A stepped session runs one step per request. The client sends one
//! `RequestStep` on the data channel — state, then commands, then the input
//! frames buffered per track since the previous step (`media_input`) — and
//! gets exactly one reply with the same `request_id`: a `StepResult`, whatever
//! the outcome. Its `failure_code` says which outcome it was, and its `error`
//! says why in words:
//!
//! | `failure_code`     | the step              | [`FrameDecoders::decode_result`]    |
//! |--------------------|-----------------------|-------------------------------------|
//! | `UNSPECIFIED`      | ran and worked        | the output                          |
//! | `FAILED`           | ran, the model failed | the output, with [`StepOutput::error`] |
//! | `REFUSED`          | did not run           | [`CoreError::StepRefused`]          |
//! | `NOT_READY`        | did not run           | [`CoreError::StepNotReady`]         |
//! | `RESULT_TOO_LARGE` | ran, result too big   | [`CoreError::StepResultTooLarge`]   |
//! | `COMMAND_FAILED`   | did not run           | [`CoreError::StepCommandFailed`]    |
//! | `SESSION_ENDED`    | unknown               | [`CoreError::StepSessionEnded`]     |
//!
//! A result names the extra files the model kept with the step
//! ([`StepFile`]) but does not carry them; their bytes are fetched over HTTP
//! from the session's step results (`GET /sessions/{id}/steps/{step}`), as
//! in a streamed session.
//!
//! Frames travel as [`FrameEncoding::Raw`] today. Decoding goes through
//! [`FrameDecoders`], keyed by encoding, so a compressed encoding is a new
//! registry entry rather than a change to the result type.

use std::collections::{HashMap, VecDeque};
use std::sync::Mutex;

use serde_json::Value;

use crate::error::CoreError;
use crate::protocol::session::TrackKind;
use crate::protocol::wire::struct_convert::value_to_struct;
use crate::protocol::wire::v1::model::Command;
use crate::protocol::wire::v1::platform::TrackKind as WireTrackKind;
use crate::protocol::wire::v1::step::{
    Frame, FrameEncoding, RequestStep, StepFailureCode, StepFileRef, StepResult, TrackFrames,
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

/// Build the `RequestStep` for `state`, `commands` and the buffered input
/// frames (`media_input`).
///
/// `state` is a JSON object of the model's state fields (each key runs as the
/// model's `set_<key>` command, before `commands`), or `null` for none.
pub fn build_request_step(
    state: Value,
    commands: Vec<StepCommand>,
    media_input: Vec<TrackFrames>,
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
        media_input,
    })
}

// ── Input buffering ─────────────────────────────────────────────────────────

/// Input frames pushed since the last step, per track.
///
/// Tracks keep the order of their first buffered push, and frames the order
/// they were pushed in. [`StepInputs::take`] empties the buffer for a step.
///
/// The buffer holds at most one message's worth of frames, counted as they
/// encode inside a `RequestStep`: a frame pushed past that budget pushes out
/// the oldest ones, whatever their track, since a step can never send more
/// than one message. It runs inside the
/// app's process, where a camera pushing faster than steps run would
/// otherwise grow it without end.
#[derive(Default)]
pub struct StepInputs {
    buffer: Mutex<Buffered>,
}

#[derive(Default)]
struct Buffered {
    tracks: Vec<TrackFrames>,
    /// The track each buffered frame belongs to, oldest first.
    order: VecDeque<usize>,
    bytes: usize,
    /// Frames pushed out by the budget since the last take.
    dropped: usize,
}

impl Buffered {
    fn add(&mut self, track: &str, kind: i32, frame: Frame) {
        let index = match self.tracks.iter().position(|t| t.track == track) {
            Some(index) => index,
            None => {
                let frames = TrackFrames {
                    track: track.to_string(),
                    kind,
                    frames: Vec::new(),
                    // A pushed frame carries no rate of its own to declare.
                    rate: 0.0,
                };
                self.bytes += track_bytes(&frames);
                self.tracks.push(frames);
                self.tracks.len() - 1
            }
        };
        self.bytes += frame_bytes(&frame);
        self.tracks[index].frames.push(frame);
        self.order.push_back(index);
    }

    /// Push out the oldest frames until the buffer fits `max_bytes`. The
    /// newest frame stays even alone over budget: the step it rides on then
    /// fails as too large, which names the problem.
    fn trim(&mut self, max_bytes: usize) {
        while self.bytes > max_bytes && self.order.len() > 1 {
            let Some(index) = self.order.pop_front() else {
                break;
            };
            let frame = self.tracks[index].frames.remove(0);
            self.bytes -= frame_bytes(&frame);
            self.dropped += 1;
            // A track left with no frames goes too, so pushes to ever new track
            // names cannot grow the buffer either.
            if self.tracks[index].frames.is_empty() {
                let track = self.tracks.remove(index);
                self.bytes -= track_bytes(&track);
                for later in self.order.iter_mut().filter(|i| **i > index) {
                    *later -= 1;
                }
            }
        }
    }
}

/// Room a step's message needs besides its frames: the envelope, the request
/// id and the field headers around `media_input`. The state and commands are
/// not known until `step()`, so they are not reserved; a large state can still
/// make a step too large.
const REQUEST_STEP_ENVELOPE_BYTES: usize = 64;

/// What a frame costs inside its `TrackFrames`: its encoded size plus the
/// repeated field's tag and length prefix. Never zero, so an empty frame still
/// counts against the budget.
fn frame_bytes(frame: &Frame) -> usize {
    let len = prost::Message::encoded_len(frame);
    1 + prost::length_delimiter_len(len) + len
}

/// What a track's entry costs inside a `RequestStep` before its frames: its
/// own fields, its tag, and a length prefix as long as a message's can be.
fn track_bytes(frames: &TrackFrames) -> usize {
    1 + prost::length_delimiter_len(u32::MAX as usize) + prost::Message::encoded_len(frames)
}

impl StepInputs {
    pub fn new() -> Self {
        Self::default()
    }

    /// Buffer `frame` for the next step, keeping the frames within what a
    /// message of `max_bytes` can carry.
    pub fn push(&self, track: &str, kind: WireTrackKind, frame: Frame, max_bytes: usize) {
        let mut buffer = self.buffer.lock().unwrap();
        buffer.add(track, kind as i32, frame);
        buffer.trim(max_bytes.saturating_sub(REQUEST_STEP_ENVELOPE_BYTES));
    }

    /// Everything buffered, leaving the buffer empty.
    pub fn take(&self) -> Vec<TrackFrames> {
        let taken = std::mem::take(&mut *self.buffer.lock().unwrap());
        if taken.dropped > 0 {
            log::warn!(
                "dropped {} buffered step input frames, oldest first, to keep the step within one message",
                taken.dropped
            );
        }
        taken
            .tracks
            .into_iter()
            .filter(|t| !t.frames.is_empty())
            .collect()
    }

    pub fn clear(&self) {
        *self.buffer.lock().unwrap() = Buffered::default();
    }

    /// Number of frames buffered across all tracks.
    pub fn len(&self) -> usize {
        self.buffer.lock().unwrap().order.len()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }
}

/// The byte layout of pushed video pixels. The push API takes BGRA; a host
/// whose frames come in another layout names it to
/// [`crate::reactor::Reactor::push_step_video_frame`], so they are converted to the
/// step's RGB once rather than to BGRA first.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PixelLayout {
    /// 4 bytes per pixel, B G R A.
    Bgra,
    /// 4 bytes per pixel, R G B A.
    Rgba,
    /// 3 bytes per pixel, R G B: what a step frame holds.
    Rgb,
}

impl PixelLayout {
    pub fn bytes_per_pixel(self) -> usize {
        match self {
            Self::Rgb => 3,
            Self::Bgra | Self::Rgba => 4,
        }
    }
}

/// A raw video frame from pushed pixels in `layout`: RGB `uint8`, shape
/// `(height, width, 3)`, converted in one pass. `None` when `pixels` is not
/// `width * height` pixels of that layout.
pub fn raw_video_frame(
    pixels: &[u8],
    width: u32,
    height: u32,
    layout: PixelLayout,
    metadata: Option<&[u8]>,
    capture_time_us: Option<i64>,
) -> Option<Frame> {
    let count = (width as usize).checked_mul(height as usize)?;
    if pixels.len() != count.checked_mul(layout.bytes_per_pixel())? {
        return None;
    }
    let rgb = match layout {
        PixelLayout::Rgb => pixels.to_vec(),
        PixelLayout::Rgba => pixels
            .chunks_exact(4)
            .flat_map(|px| [px[0], px[1], px[2]])
            .collect(),
        PixelLayout::Bgra => pixels
            .chunks_exact(4)
            .flat_map(|px| [px[2], px[1], px[0]])
            .collect(),
    };
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
    /// The step's number in the session, counted from 1: the `step` of the
    /// session's step results (`GET /sessions/{id}/steps/{step}`).
    pub step: u64,
    pub tracks: Vec<TrackOutput>,
    /// Seconds the step's `generate()` took, or the time the model reported
    /// for the step.
    pub elapsed: f64,
    /// The extra files the model kept with the step, in the order the runtime
    /// listed them. Empty when the step kept none, or when its result was not
    /// saved.
    pub files: Vec<StepFile>,
    /// Why the step failed, when it ran and the model reported a failure;
    /// `None` when it worked. A step that did not run (refused, the session
    /// not ready, or one of its calls failed), whose result was too large, or
    /// whose session ended before the result came has no output:
    /// [`FrameDecoders::decode_result`] fails instead.
    pub error: Option<String>,
}

/// A reference to one extra file a model kept with a step. The result carries
/// the reference, not the bytes: they are fetched over HTTP from the session's
/// step results (`GET /sessions/{id}/steps/{step}`), by the step's number and
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
    /// The rate the frames play at: frames per second for video, samples per
    /// second for audio. A step's video rate is that step's own. `0.0` when
    /// the runtime does not say.
    pub rate: f64,
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
            rate: 0.0,
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
        assert_eq!(request.media_input, inputs);
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
        inputs.push(
            "a",
            WireTrackKind::Video,
            raw("uint8", vec![1], vec![1]),
            usize::MAX,
        );
        inputs.push(
            "b",
            WireTrackKind::Audio,
            raw("int16", vec![1, 1], vec![0, 0]),
            usize::MAX,
        );
        inputs.push(
            "a",
            WireTrackKind::Video,
            raw("uint8", vec![1], vec![2]),
            usize::MAX,
        );
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
    fn pushing_past_the_budget_drops_the_oldest_frames_across_tracks() {
        let inputs = StepInputs::new();
        // Room for both tracks and 3 of their frames, each the same size.
        let track = |name: &str| TrackFrames {
            track: name.into(),
            kind: WireTrackKind::Video as i32,
            frames: vec![],
            rate: 0.0,
        };
        let max = REQUEST_STEP_ENVELOPE_BYTES
            + track_bytes(&track("a"))
            + track_bytes(&track("b"))
            + 3 * frame_bytes(&raw("uint8", vec![2], vec![0, 0]));
        inputs.push(
            "a",
            WireTrackKind::Video,
            raw("uint8", vec![2], vec![1, 1]),
            max,
        );
        inputs.push(
            "b",
            WireTrackKind::Video,
            raw("uint8", vec![2], vec![2, 2]),
            max,
        );
        inputs.push(
            "a",
            WireTrackKind::Video,
            raw("uint8", vec![2], vec![3, 3]),
            max,
        );
        inputs.push(
            "b",
            WireTrackKind::Video,
            raw("uint8", vec![2], vec![4, 4]),
            max,
        );
        assert_eq!(inputs.len(), 3);

        let taken = inputs.take();
        let firsts =
            |track: &TrackFrames| track.frames.iter().map(|f| f.data[0]).collect::<Vec<_>>();
        assert_eq!(taken.len(), 2);
        assert_eq!((taken[0].track.as_str(), firsts(&taken[0])), ("a", vec![3]));
        assert_eq!(
            (taken[1].track.as_str(), firsts(&taken[1])),
            ("b", vec![2, 4])
        );
        assert!(inputs.is_empty());
    }

    #[test]
    fn pushes_to_ever_new_tracks_stay_within_the_budget() {
        let inputs = StepInputs::new();
        for n in 0..10_000 {
            inputs.push(
                &format!("track-{n}"),
                WireTrackKind::Video,
                raw("uint8", vec![1], vec![1]),
                1024,
            );
        }
        let buffer = inputs.buffer.lock().unwrap();
        assert!(buffer.tracks.len() < 1024, "{} tracks", buffer.tracks.len());
        assert!(buffer.bytes <= 1024, "{} bytes", buffer.bytes);
    }

    #[test]
    fn a_frame_alone_over_budget_stays_and_a_track_left_empty_is_not_sent() {
        let inputs = StepInputs::new();
        inputs.push("a", WireTrackKind::Video, raw("uint8", vec![1], vec![1]), 4);
        inputs.push(
            "b",
            WireTrackKind::Video,
            raw("uint8", vec![8], vec![0; 8]),
            4,
        );
        assert_eq!(inputs.len(), 1);

        let taken = inputs.take();
        assert_eq!(taken.len(), 1);
        assert_eq!(taken[0].track, "b");
    }

    #[test]
    fn video_frames_become_rgb_height_width_3() {
        // 2x1 BGRA: blue pixel, then red pixel.
        let bgra = [255, 0, 0, 255, 0, 0, 255, 255];
        let frame =
            raw_video_frame(&bgra, 2, 1, PixelLayout::Bgra, Some(b"tag"), Some(42)).unwrap();
        assert_eq!(frame.encoding, FrameEncoding::Raw as i32);
        assert_eq!(frame.dtype, "uint8");
        assert_eq!(frame.shape, [1, 2, 3]);
        assert_eq!(frame.data, [0, 0, 255, 255, 0, 0]);
        assert_eq!(frame.metadata.as_deref(), Some(&b"tag"[..]));
        assert_eq!(frame.capture_time_us, Some(42));

        assert!(raw_video_frame(&bgra, 3, 1, PixelLayout::Bgra, None, None).is_none());
        let untagged = raw_video_frame(&bgra, 2, 1, PixelLayout::Bgra, Some(b""), None).unwrap();
        assert!(untagged.metadata.is_none());
    }

    #[test]
    fn rgba_and_rgb_pixels_become_the_same_rgb_frame() {
        // 2x1: blue pixel, then red pixel.
        let rgba = [0, 0, 255, 255, 255, 0, 0, 255];
        let rgb = [0, 0, 255, 255, 0, 0];
        for (pixels, layout) in [(&rgba[..], PixelLayout::Rgba), (&rgb[..], PixelLayout::Rgb)] {
            let frame = raw_video_frame(pixels, 2, 1, layout, None, None).unwrap();
            assert_eq!(frame.shape, [1, 2, 3], "{layout:?}");
            assert_eq!(frame.data, rgb, "{layout:?}");
        }
        assert!(raw_video_frame(&rgb, 2, 1, PixelLayout::Rgba, None, None).is_none());
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
