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

use serde_json::Value;

use crate::error::CoreError;
use crate::protocol::session::TrackKind;
use crate::protocol::wire::struct_convert::value_to_struct;
use crate::protocol::wire::v1::model::Command;
use crate::protocol::wire::v1::step::{
    Frame, FrameEncoding, RequestStep, StepFileRef, TrackFrames,
};

mod decode;
mod inputs;

pub use decode::{DecodedFrame, FrameDecoder, FrameDecoders, RawFrameDecoder};
pub use inputs::StepInputs;

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

/// A raw wire frame, for the step tests.
#[cfg(test)]
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::wire::struct_convert::struct_to_value;
    use crate::protocol::wire::v1::platform::TrackKind as WireTrackKind;
    use serde_json::json;

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
}
