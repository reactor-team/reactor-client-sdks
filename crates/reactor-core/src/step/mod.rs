//! Stepped generation: building a `RequestStep`, sending its input frames,
//! and decoding the `StepResult` that answers it (`reactor_wire.v1`).
//!
//! A stepped session runs one step per request. Input frames go to the
//! runtime as they are pushed, each in a `PushFrames` notification on the data
//! channel, the same ordered channel as the commands and the steps: a frame
//! pushed before a step is written to its track before that step runs. The
//! runtime never refuses a push. A runtime older than
//! [`PUSH_FRAMES_RUNTIME_VERSION`] does not take `PushFrames`: against it, and
//! until the session is ready, frames are buffered per track instead and ride
//! the next step as its `media_input`.
//!
//! The client sends one `RequestStep` on the data channel — state, then
//! commands, then any input frames still buffered (`media_input`) — and
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
//! Input frames travel as [`FrameEncoding::Raw`] or, for video, encoded
//! ([`encoded_video_frame`]), which the runtime decodes as they arrive. When
//! a track's pushed frames stop decoding, the runtime says so with an
//! `input_decode_failed` notification naming the track and drops its frames
//! up to the next keyframe. It is raised as an error event whose `track`
//! names the track, so the host encoding it starts its next frame at one.
//! Result frames are decoded through
//! [`FrameDecoders`], keyed by encoding, so a compressed encoding is a new
//! registry entry rather than a change to the result type.
//!
//! An encoded output track is one stream for the whole connection: each
//! result's frames follow on from the previous result's. Its frames are
//! decoded by a [`TrackDecoder`] the track keeps across results, fed in the
//! order the results arrive on the data channel (not the order their steps
//! are awaited in), and dropped when the channel closes, so a new connection
//! starts every stream over at a keyframe. When a track's decoder loses its
//! state, or its stream does not start at a keyframe (`Frame.keyframe`), the
//! track's frames fail until a keyframe starts it again, and the core asks
//! for one by pausing and resuming that track on the control channel: the
//! runtime starts a resumed track's next frames at a keyframe.
//!
//! Which encoding a track's step frames take on a connection is negotiated in
//! the SDP ([`stepped_offer`], [`negotiated_encodings`]): the client prefers
//! codecs per track ([`CodecPreferences`]) among those it can handle
//! ([`LocalStepCodecs`]), and the runtime's answer settles one per track, or
//! raw.
//!
//! With the `webrtc-codecs` feature the core carries those codecs itself, on
//! reactor-webrtc's standalone encoders and decoders: the codecs it offers are
//! the ones they can run here, an output track in a codec is decoded by a
//! `WebrtcTrackDecoder`, and the raw pixels pushed on an input track in a
//! codec are encoded by its `InputEncoders` entry before they are sent. A
//! track's first pushed frame on a connection is a keyframe, and so is the
//! next one after `input_decode_failed` or after frames encoded for it were
//! not sent.
//!
//! A host with codecs of its own (a browser, on WebCodecs) sets
//! [`crate::reactor::ReactorOptions::pass_encoded_step_frames`] instead: an
//! output track's encoded frames then come up as they arrived, through a
//! [`PassThroughFrameDecoder`], and it pushes the frames it encodes itself
//! with their keyframe mark.

use serde_json::Value;

use crate::error::CoreError;
use crate::protocol::session::TrackKind;
use crate::protocol::wire::struct_convert::value_to_struct;
use crate::protocol::wire::v1::common::MessageKind;
use crate::protocol::wire::v1::data::{data_client_message, DataClientMessage};
use crate::protocol::wire::v1::model::Command;
use crate::protocol::wire::v1::step::{
    Frame, FrameEncoding, PushFrames, RequestStep, StepFileRef, TrackFrames,
};

mod decode;
mod inputs;
mod negotiation;

pub(crate) use decode::step_output;
#[cfg(feature = "webrtc-codecs")]
pub use decode::WebrtcTrackDecoder;
pub use decode::{
    DecodedFrame, FrameDecoder, FrameDecoders, PassThroughFrameDecoder, RawFrameDecoder,
    TrackDecoder, TrackDecoderFactory,
};
pub use inputs::{pushed_frame, StepInputs};
#[cfg(feature = "webrtc-codecs")]
pub use inputs::{InputEncoders, PushedPicture};
pub use negotiation::{
    may_encode_step_frames, negotiated_encodings, negotiates_encoded_step_frames, stepped_offer,
    CodecPreferences, LocalStepCodecs, StepCodec, ENCODED_STEP_FRAMES,
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

/// The first runtime version that takes `PushFrames`, as the session's
/// `server_info.server_version` reports it: `(major, minor, patch)`.
pub const PUSH_FRAMES_RUNTIME_VERSION: (u64, u64, u64) = (3, 11, 8);

/// The `Error` code of a notification that a track's pushed frames stopped
/// decoding; its `track` names the track.
pub const INPUT_DECODE_FAILED: &str = "input_decode_failed";

/// Whether a runtime reporting `server_version` takes `PushFrames`: at least
/// [`PUSH_FRAMES_RUNTIME_VERSION`], read from the version's leading numbers
/// (`"3.11.8"`, `"3.12.0rc1"`). A version that does not read as one is taken
/// as an older runtime, which still takes the frames in `media_input`.
pub fn runtime_takes_pushed_frames(server_version: &str) -> bool {
    let mut parts = server_version.trim().splitn(3, '.').map(|part| {
        let digits = part.len() - part.trim_start_matches(|c: char| c.is_ascii_digit()).len();
        part[..digits].parse::<u64>().ok()
    });
    let mut next = || parts.next().flatten();
    match (next(), next(), next()) {
        (Some(major), Some(minor), Some(patch)) => {
            (major, minor, patch) >= PUSH_FRAMES_RUNTIME_VERSION
        }
        _ => false,
    }
}

/// The `PushFrames` notification that sends `tracks` to the runtime now, apart
/// from any step. A notification: the runtime answers none.
pub fn push_frames_message(tracks: Vec<TrackFrames>) -> DataClientMessage {
    DataClientMessage {
        request_id: String::new(),
        kind: MessageKind::Notification as i32,
        payload: Some(data_client_message::Payload::PushFrames(PushFrames {
            tracks,
        })),
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
        keyframe: false,
    })
}

/// An encoded video frame: `data` as it is, under `encoding`, with the shape
/// it decodes to, RGB `uint8` `(height, width, 3)`, marked a keyframe when its
/// bitstream says the decoder can start there. The runtime decodes it before
/// the step runs. `None` for an empty frame, a zero dimension, or an
/// encoding that is not a compressed one (a raw frame goes through
/// [`raw_video_frame`]).
pub fn encoded_video_frame(
    encoding: FrameEncoding,
    data: &[u8],
    width: u32,
    height: u32,
    metadata: Option<&[u8]>,
    capture_time_us: Option<i64>,
) -> Option<Frame> {
    if matches!(encoding, FrameEncoding::Unspecified | FrameEncoding::Raw)
        || data.is_empty()
        || width == 0
        || height == 0
    {
        return None;
    }
    Some(Frame {
        encoding: encoding as i32,
        dtype: VIDEO_DTYPE.to_string(),
        shape: vec![height, width, 3],
        data: data.to_vec(),
        metadata: metadata.filter(|m| !m.is_empty()).map(<[u8]>::to_vec),
        capture_time_us,
        keyframe: is_keyframe(encoding, data),
    })
}

/// Whether the decoder can start the stream at `data`, read from its
/// bitstream: an H.264 access unit (Annex B) holding an IDR slice, or a VP8 or
/// VP9 key frame. `false` for anything else, including an encoding whose
/// frames it does not read.
fn is_keyframe(encoding: FrameEncoding, data: &[u8]) -> bool {
    match encoding {
        FrameEncoding::H264 => h264_nal_types(data).any(|nal_type| nal_type == 5),
        // The frame tag's first bit is 0 for a key frame, which carries the
        // start code 9d 01 2a after the tag.
        FrameEncoding::Vp8 => {
            data.len() >= 6 && data[0] & 1 == 0 && data[3..6] == [0x9d, 0x01, 0x2a]
        }
        FrameEncoding::Vp9 => vp9_is_keyframe(data),
        _ => false,
    }
}

/// The `nal_unit_type` of each NAL unit in an Annex B access unit.
fn h264_nal_types(data: &[u8]) -> impl Iterator<Item = u8> + '_ {
    data.windows(4)
        .filter(|w| w[..3] == [0, 0, 1])
        .map(|w| w[3] & 0x1f)
}

/// Whether a VP9 frame's uncompressed header says `KEY_FRAME`: past the frame
/// marker and profile, a frame that is not a repeat of a shown one with a
/// `frame_type` of 0.
fn vp9_is_keyframe(data: &[u8]) -> bool {
    let Some(&first) = data.first() else {
        return false;
    };
    let bit = |n: u32| (first >> (7 - n)) & 1;
    if bit(0) != 1 || bit(1) != 0 {
        return false;
    }
    let profile = (bit(3) << 1) | bit(2);
    // Profile 3 has a reserved bit before `show_existing_frame`.
    let at = if profile == 3 { 5 } else { 4 };
    // `show_existing_frame`, then `frame_type`.
    bit(at) == 0 && bit(at + 1) == 0
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
        keyframe: false,
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
    /// What the model returned for the step besides its media, opaque to the
    /// transport: the bytes it returned as they are, or a dict it returned
    /// encoded as JSON, the same rule as a frame's metadata. It arrives with
    /// the step's frames, so a model whose output is data alone needs no
    /// output track. `None` when the model returned none.
    pub data: Option<Vec<u8>>,
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
        keyframe: false,
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
    fn runtimes_from_the_push_frames_release_take_pushed_frames() {
        for version in [
            "3.11.8",
            "3.12.0",
            "4.0.0",
            "3.11.10",
            "3.12.0rc1",
            " 3.11.8 ",
        ] {
            assert!(runtime_takes_pushed_frames(version), "{version}");
        }
        for version in [
            "3.11.7", "3.9.20", "2.99.99", "0.0.0", "", "3.11", "dev", "x.y.z",
        ] {
            assert!(!runtime_takes_pushed_frames(version), "{version}");
        }
    }

    #[test]
    fn a_push_is_a_notification_carrying_its_tracks() {
        let tracks = vec![pushed_frame(
            "cam",
            WireTrackKind::Video,
            raw("uint8", vec![1, 1, 3], vec![1, 2, 3]),
        )];
        let message = push_frames_message(tracks.clone());
        assert_eq!(message.kind, MessageKind::Notification as i32);
        assert!(message.request_id.is_empty());
        assert_eq!(
            message.payload,
            Some(data_client_message::Payload::PushFrames(PushFrames {
                tracks
            }))
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
    fn an_encoded_frame_carries_its_bytes_as_they_are_and_the_shape_it_decodes_to() {
        let access_unit = [0, 0, 0, 1, 0x65, 0x88, 0x84];
        let frame = encoded_video_frame(
            FrameEncoding::H264,
            &access_unit,
            1280,
            720,
            Some(b"tag"),
            Some(42),
        )
        .unwrap();
        assert_eq!(frame.encoding, FrameEncoding::H264 as i32);
        assert_eq!(frame.dtype, VIDEO_DTYPE);
        assert_eq!(frame.shape, [720, 1280, 3]);
        assert_eq!(frame.data, access_unit);
        assert_eq!(frame.metadata.as_deref(), Some(&b"tag"[..]));
        assert_eq!(frame.capture_time_us, Some(42));
        assert!(frame.keyframe);

        let untagged =
            encoded_video_frame(FrameEncoding::H264, &access_unit, 2, 2, Some(b""), None).unwrap();
        assert!(untagged.metadata.is_none());
        assert!(encoded_video_frame(FrameEncoding::H264, &[], 2, 2, None, None).is_none());
        assert!(encoded_video_frame(FrameEncoding::H264, &access_unit, 0, 2, None, None).is_none());
        for not_compressed in [FrameEncoding::Raw, FrameEncoding::Unspecified] {
            assert!(
                encoded_video_frame(not_compressed, &access_unit, 2, 2, None, None).is_none(),
                "{not_compressed:?}"
            );
        }
    }

    #[test]
    fn an_encoded_frame_is_a_keyframe_when_its_bitstream_says_so() {
        let keyframe = |encoding, data: &[u8]| {
            encoded_video_frame(encoding, data, 2, 2, None, None)
                .unwrap()
                .keyframe
        };
        // H.264: an access unit with an IDR slice, after SPS and PPS, with
        // 4- and 3-byte start codes; a P slice alone is a delta frame.
        let idr = [
            0, 0, 0, 1, 0x67, 0x42, 0, 0, 1, 0x68, 0xce, 0, 0, 1, 0x65, 0x88,
        ];
        assert!(keyframe(FrameEncoding::H264, &idr));
        assert!(!keyframe(FrameEncoding::H264, &[0, 0, 0, 1, 0x41, 0x9a]));
        assert!(!keyframe(FrameEncoding::H264, &[0x65, 0x88]));
        // VP8: the frame tag's first bit, and the key frame's start code.
        let vp8_key = [0x10, 0x02, 0x00, 0x9d, 0x01, 0x2a, 0x02, 0x00];
        assert!(keyframe(FrameEncoding::Vp8, &vp8_key));
        assert!(!keyframe(FrameEncoding::Vp8, &[0x11, 0x02, 0x00, 0x00]));
        // VP9: frame marker 10, profile 0, a shown frame of type KEY_FRAME
        // (0) or NON_KEY_FRAME (1); profile 3 has a reserved bit first.
        assert!(keyframe(FrameEncoding::Vp9, &[0b1000_0010]));
        assert!(!keyframe(FrameEncoding::Vp9, &[0b1000_0110]));
        assert!(!keyframe(FrameEncoding::Vp9, &[0b1000_1000]));
        assert!(keyframe(FrameEncoding::Vp9, &[0b1011_0000]));
        assert!(!keyframe(FrameEncoding::Vp9, &[0b1011_0010]));
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
