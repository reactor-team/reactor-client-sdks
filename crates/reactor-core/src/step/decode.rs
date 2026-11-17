//! Decoding the `StepResult` that answers a step, through a registry keyed by
//! encoding: frame by frame, or, for an encoding whose frames depend on the
//! ones before them, one stream per output track that spans results.

use std::collections::{HashMap, HashSet};
use std::sync::Mutex;

use super::{StepFile, StepOutput, TrackOutput};
use crate::error::CoreError;
use crate::protocol::session::TrackKind;
use crate::protocol::wire::v1::platform::TrackKind as WireTrackKind;
use crate::protocol::wire::v1::step::{
    Frame, FrameEncoding, StepFailureCode, StepResult, TrackFrames,
};

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

/// Decodes one output track's stream of an encoding whose frames depend on
/// the ones before them (an inter-coded video codec). One is made per output
/// track, from its first keyframe on, and is fed every frame of that track in
/// the order the results arrived, across results.
///
/// An `Err` means the decoder lost its state (a corrupt frame, a hardware
/// decoder reset): it is dropped, the track waits for its next keyframe, and
/// the next step asks the runtime for one (`request_keyframe`).
pub trait TrackDecoder: Send {
    fn decode(&mut self, frame: Frame) -> Result<DecodedFrame, CoreError>;
}

/// Makes a fresh [`TrackDecoder`] for a track whose stream starts, or starts
/// again, at a keyframe.
pub type TrackDecoderFactory = Box<dyn Fn() -> Box<dyn TrackDecoder> + Send + Sync>;

enum Registered {
    /// Each frame decodes alone.
    Frame(Box<dyn FrameDecoder>),
    /// Each track is one stream, decoded by its own decoder.
    Track(TrackDecoderFactory),
}

/// The live per-track decoders of one data channel.
#[derive(Default)]
struct Streams {
    /// Each output track's decoder, with the encoding it decodes.
    live: HashMap<String, (i32, Box<dyn TrackDecoder>)>,
    /// The tracks whose decoder lost its state, or whose stream did not
    /// start at a keyframe: their frames fail until a keyframe comes.
    awaiting_keyframe: HashSet<String>,
    /// Set when the data channel closes, until the next one opens: a result
    /// of the closed connection still queued then starts no stream.
    closed: bool,
}

/// Frame decoders, keyed by [`FrameEncoding`], and the per-track state of
/// the encodings that keep one. [`FrameDecoders::default`] knows
/// [`FrameEncoding::Raw`]; a frame in any other encoding fails the step with
/// [`CoreError::UnsupportedFrameEncoding`].
///
/// The per-track state belongs to one data channel: results are decoded in
/// the order they arrive on it, and [`FrameDecoders::reset`] drops the state
/// when it closes, so the next connection's streams start over at their
/// keyframes.
pub struct FrameDecoders {
    decoders: HashMap<i32, Registered>,
    streams: Mutex<Streams>,
}

impl Default for FrameDecoders {
    fn default() -> Self {
        let mut decoders = Self {
            decoders: HashMap::new(),
            streams: Mutex::default(),
        };
        decoders.register(FrameEncoding::Raw, Box::new(RawFrameDecoder));
        decoders
    }
}

impl FrameDecoders {
    /// Decode `encoding` frame by frame with `decoder`.
    pub fn register(&mut self, encoding: FrameEncoding, decoder: Box<dyn FrameDecoder>) {
        self.decoders
            .insert(encoding as i32, Registered::Frame(decoder));
    }

    /// Decode `encoding` as one stream per output track, with a decoder from
    /// `factory` that each track keeps across results. A track's stream must
    /// start at a keyframe (`Frame.keyframe`), on each connection and after
    /// its decoder lost its state.
    pub fn register_track_decoder(
        &mut self,
        encoding: FrameEncoding,
        factory: TrackDecoderFactory,
    ) {
        self.decoders
            .insert(encoding as i32, Registered::Track(factory));
    }

    /// Decode the next frame of `track`. A frame of a per-track encoding
    /// moves that track's stream on: call it in arrival order.
    pub fn decode_frame(&self, track: &str, frame: Frame) -> Result<DecodedFrame, CoreError> {
        let mut retired = Vec::new();
        let decoded = {
            let mut streams = self.streams.lock().unwrap();
            self.decode_frame_in(&mut streams, &mut retired, track, frame)
        };
        // Dropped once the lock is released: a decoder's teardown may wait
        // on threads or callbacks that come back here.
        drop(retired);
        decoded
    }

    /// Decode `frame` with `streams` locked. A decoder it drops goes into
    /// `retired`, for the caller to drop once the lock is released.
    fn decode_frame_in(
        &self,
        streams: &mut Streams,
        retired: &mut Vec<Box<dyn TrackDecoder>>,
        track: &str,
        frame: Frame,
    ) -> Result<DecodedFrame, CoreError> {
        let factory = match self.decoders.get(&frame.encoding) {
            Some(Registered::Frame(decoder)) => return decoder.decode(frame),
            Some(Registered::Track(factory)) => factory,
            None => {
                return Err(CoreError::UnsupportedFrameEncoding {
                    track: track.to_string(),
                    encoding: frame.encoding,
                })
            }
        };
        if streams.closed {
            // A result of a connection that closed, still queued: the next
            // connection's streams must not run on from it.
            return Err(CoreError::decode(format!(
                "track '{track}' frame arrived after its data channel closed"
            )));
        }
        let encoding = frame.encoding;
        let keyframe = frame.keyframe;
        let continues = matches!(streams.live.get(track), Some((live, _)) if *live == encoding);
        if !continues {
            // A new stream, or one in another encoding: it starts here, and
            // only a keyframe can start it.
            retired.extend(streams.live.remove(track).map(|(_, decoder)| decoder));
            if !keyframe {
                streams.awaiting_keyframe.insert(track.to_string());
                return Err(CoreError::decode(format!(
                    "track '{track}' has no decoder state for a frame that is not a keyframe; \
                     waiting for a keyframe"
                )));
            }
            streams
                .live
                .insert(track.to_string(), (encoding, factory()));
        }
        let (_, decoder) = streams.live.get_mut(track).expect("inserted above");
        match decoder.decode(frame) {
            Ok(decoded) => {
                if keyframe {
                    streams.awaiting_keyframe.remove(track);
                }
                Ok(decoded)
            }
            Err(error) => {
                retired.extend(streams.live.remove(track).map(|(_, decoder)| decoder));
                streams.awaiting_keyframe.insert(track.to_string());
                Err(error)
            }
        }
    }

    /// Decode a result's tracks, in the order its results arrived (see
    /// [`FrameDecoders`]). Every frame of every track is fed to its decoder
    /// even after one fails, so the other streams keep up; the first
    /// failure is returned rather than a partial step.
    pub fn decode_tracks(&self, tracks: Vec<TrackFrames>) -> Result<Vec<TrackOutput>, CoreError> {
        let mut retired = Vec::new();
        let decoded = {
            let mut streams = self.streams.lock().unwrap();
            self.decode_tracks_in(&mut streams, &mut retired, tracks)
        };
        // Dropped once the lock is released (see `decode_frame`).
        drop(retired);
        decoded
    }

    fn decode_tracks_in(
        &self,
        streams: &mut Streams,
        retired: &mut Vec<Box<dyn TrackDecoder>>,
        tracks: Vec<TrackFrames>,
    ) -> Result<Vec<TrackOutput>, CoreError> {
        let mut first_error = None;
        let mut outputs = Vec::with_capacity(tracks.len());
        for track in tracks {
            let kind = match WireTrackKind::try_from(track.kind) {
                Ok(WireTrackKind::Video) => Some(TrackKind::Video),
                Ok(WireTrackKind::Audio) => Some(TrackKind::Audio),
                _ => None,
            };
            if kind.is_none() && first_error.is_none() {
                first_error = Some(CoreError::decode(format!(
                    "step result track '{}' has unknown kind {}",
                    track.track, track.kind
                )));
            }
            let mut frames = Vec::with_capacity(track.frames.len());
            for frame in track.frames {
                match self.decode_frame_in(streams, retired, &track.track, frame) {
                    Ok(frame) => frames.push(frame),
                    Err(error) => {
                        first_error.get_or_insert(error);
                    }
                }
            }
            if let Some(kind) = kind {
                outputs.push(TrackOutput {
                    track: track.track,
                    kind,
                    frames,
                    rate: track.rate,
                });
            }
        }
        match first_error {
            Some(error) => Err(error),
            None => Ok(outputs),
        }
    }

    /// Decode a whole [`StepResult`], as it arrives (see [`FrameDecoders`]).
    /// A result whose `failure_code` says the step did not run, or ran with
    /// a result too large to send, is that failure, with `error` as its
    /// message (see the module docs). Otherwise fails on the first frame that
    /// cannot be decoded rather than returning a partial step.
    pub fn decode_result(&self, mut result: StepResult) -> Result<StepOutput, CoreError> {
        let tracks = self.decode_tracks(std::mem::take(&mut result.tracks));
        step_output(result, tracks)
    }

    /// Whether a track lost its decoder state and waits for a keyframe: the
    /// next step asks the runtime for one (`request_keyframe`).
    pub fn needs_keyframe(&self) -> bool {
        !self.streams.lock().unwrap().awaiting_keyframe.is_empty()
    }

    /// Drop every track's decoder state, as the data channel it came with
    /// closes. The registered decoders stay. Until [`FrameDecoders::open`],
    /// a frame of a per-track encoding starts no stream: it can only be a
    /// result of the closed connection, still queued.
    pub fn reset(&self) {
        let closed = std::mem::replace(
            &mut *self.streams.lock().unwrap(),
            Streams {
                closed: true,
                ..Streams::default()
            },
        );
        // Dropped once the lock is released (see `decode_frame`).
        drop(closed);
    }

    /// The next data channel opened: its results start their streams.
    pub fn open(&self) {
        self.streams.lock().unwrap().closed = false;
    }

    /// The tracks that hold decoder state, sorted.
    #[cfg(test)]
    fn live_tracks(&self) -> Vec<String> {
        let mut tracks: Vec<_> = self.streams.lock().unwrap().live.keys().cloned().collect();
        tracks.sort();
        tracks
    }
}

/// The [`StepOutput`] of `result`, whose tracks were decoded as `tracks`
/// (see [`FrameDecoders::decode_tracks`]). A result whose `failure_code`
/// says the step did not run, or ran with a result too large to send, is
/// that failure whatever its tracks.
pub(crate) fn step_output(
    result: StepResult,
    tracks: Result<Vec<TrackOutput>, CoreError>,
) -> Result<StepOutput, CoreError> {
    if let Some(failure) = step_failure(&result) {
        return Err(failure);
    }
    Ok(StepOutput {
        step: result.step,
        tracks: tracks?,
        elapsed: result.elapsed_seconds,
        files: result.files.into_iter().map(StepFile::from).collect(),
        error: result.error,
        data: result.data,
    })
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
    use std::sync::Arc;

    use super::*;
    use crate::protocol::wire::v1::step::StepFileRef;
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
            data: None,
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
            data: None,
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
            data: None,
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
            data: None,
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
            data: None,
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

    /// The data a model returned for the step (`StepResult.data`, field 9)
    /// comes back as the bytes it was, with no track needed to carry it, out
    /// of a real protobuf round trip; a result without it has none, not an
    /// empty buffer.
    #[test]
    fn the_data_a_step_returned_survives_the_wire_encoding() {
        use prost::Message;

        let decode = |result: StepResult| {
            let bytes = result.encode_to_vec();
            FrameDecoders::default()
                .decode_result(StepResult::decode(bytes.as_slice()).unwrap())
                .unwrap()
        };
        let output = decode(StepResult {
            step: 4,
            data: Some(br#"{"actions":[[0.1,0.2]]}"#.to_vec()),
            ..Default::default()
        });
        assert_eq!(output.step, 4);
        assert!(output.tracks.is_empty());
        assert_eq!(
            output.data.as_deref(),
            Some(&br#"{"actions":[[0.1,0.2]]}"#[..])
        );

        let empty = decode(StepResult {
            step: 5,
            data: Some(vec![]),
            ..Default::default()
        });
        assert_eq!(empty.data.as_deref(), Some(&[][..]));

        let none = decode(StepResult {
            step: 6,
            ..Default::default()
        });
        assert_eq!(none.data, None);
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
            data: None,
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

    /// `(decoder id, first byte)` of every frame a [`Counting`] decoded.
    type DecodeLog = Arc<Mutex<Vec<(u8, u8)>>>;

    /// A stateful stand-in for a video decoder: decoder `id` (counted from 1
    /// as the factory makes them) logs each frame's first byte, and decodes
    /// it to `[id, frames it decoded before]`. A frame whose first byte is
    /// `0xFF` is corrupt: the decoder fails on it.
    struct Counting {
        id: u8,
        decoded: u8,
        log: DecodeLog,
    }

    impl TrackDecoder for Counting {
        fn decode(&mut self, frame: Frame) -> Result<DecodedFrame, CoreError> {
            if frame.data[0] == 0xFF {
                return Err(CoreError::decode("corrupt frame"));
            }
            self.log.lock().unwrap().push((self.id, frame.data[0]));
            let decoded = DecodedFrame {
                encoding: FrameEncoding::H264,
                dtype: "uint8".into(),
                shape: vec![1, 2],
                data: vec![self.id, self.decoded],
                metadata: frame.metadata,
                capture_time_us: frame.capture_time_us,
            };
            self.decoded += 1;
            Ok(decoded)
        }
    }

    /// Decoders with H.264 registered as [`Counting`], and its log.
    fn counting_decoders() -> (FrameDecoders, DecodeLog) {
        let log = Arc::new(Mutex::new(vec![]));
        let made = Arc::new(Mutex::new(0u8));
        let mut decoders = FrameDecoders::default();
        let factory_log = log.clone();
        decoders.register_track_decoder(
            FrameEncoding::H264,
            Box::new(move || {
                let mut made = made.lock().unwrap();
                *made += 1;
                Box::new(Counting {
                    id: *made,
                    decoded: 0,
                    log: factory_log.clone(),
                })
            }),
        );
        (decoders, log)
    }

    fn h264(byte: u8, keyframe: bool) -> Frame {
        Frame {
            encoding: FrameEncoding::H264 as i32,
            keyframe,
            ..raw("uint8", vec![1, 1, 3], vec![byte])
        }
    }

    fn video(track: &str, frames: Vec<Frame>) -> TrackFrames {
        TrackFrames {
            track: track.into(),
            kind: WireTrackKind::Video as i32,
            frames,
            rate: 0.0,
        }
    }

    fn result_with(tracks: Vec<TrackFrames>) -> StepResult {
        StepResult {
            step: 1,
            tracks,
            ..Default::default()
        }
    }

    /// Each output track keeps one decoder across results: the second
    /// result's frames follow on from the first's, in the decoder that
    /// started at the first keyframe, and each track has its own.
    #[test]
    fn a_tracks_decoder_runs_on_across_results() {
        let (decoders, log) = counting_decoders();
        let first = decoders
            .decode_result(result_with(vec![
                video("a", vec![h264(1, true), h264(2, false)]),
                video("b", vec![h264(10, true)]),
            ]))
            .unwrap();
        let second = decoders
            .decode_result(result_with(vec![video("a", vec![h264(3, false)])]))
            .unwrap();
        let third = decoders
            .decode_result(result_with(vec![
                video("b", vec![h264(11, false)]),
                video("a", vec![h264(4, false)]),
            ]))
            .unwrap();

        assert_eq!(
            *log.lock().unwrap(),
            [(1, 1), (1, 2), (2, 10), (1, 3), (2, 11), (1, 4)]
        );
        assert_eq!(first.tracks[0].frames[1].data, [1, 1]);
        assert_eq!(second.tracks[0].frames[0].data, [1, 2]);
        assert_eq!(third.tracks[0].track, "b");
        assert_eq!(third.tracks[0].frames[0].data, [2, 1]);
        assert_eq!(third.tracks[1].frames[0].data, [1, 3]);
        assert!(!decoders.needs_keyframe());
    }

    /// A stream starts at a keyframe: a track with no decoder state fails on
    /// any other frame, and asks for a keyframe.
    #[test]
    fn a_stream_that_does_not_start_at_a_keyframe_waits_for_one() {
        let (decoders, log) = counting_decoders();
        let result = decoders.decode_result(result_with(vec![video("a", vec![h264(1, false)])]));
        assert!(matches!(result, Err(CoreError::Decode(_))), "{result:?}");
        assert!(decoders.needs_keyframe());
        assert!(log.lock().unwrap().is_empty());

        decoders
            .decode_result(result_with(vec![video("a", vec![h264(2, true)])]))
            .unwrap();
        assert!(!decoders.needs_keyframe());
        assert_eq!(*log.lock().unwrap(), [(1, 2)]);
    }

    /// A decoder that fails lost its state: it is dropped, the track asks
    /// for a keyframe and fails until one comes, and a fresh decoder starts
    /// there. The other tracks of the same result keep up meanwhile.
    #[test]
    fn a_decode_error_drops_the_tracks_state_until_a_keyframe() {
        let (decoders, log) = counting_decoders();
        decoders
            .decode_result(result_with(vec![
                video("a", vec![h264(1, true)]),
                video("b", vec![h264(10, true)]),
            ]))
            .unwrap();

        let failed = decoders.decode_result(result_with(vec![
            video("a", vec![h264(0xFF, false), h264(2, false)]),
            video("b", vec![h264(11, false)]),
        ]));
        assert!(matches!(failed, Err(CoreError::Decode(_))), "{failed:?}");
        assert!(decoders.needs_keyframe());
        assert_eq!(decoders.live_tracks(), ["b"]);

        let still = decoders.decode_result(result_with(vec![video("a", vec![h264(3, false)])]));
        assert!(still.is_err());
        assert!(decoders.needs_keyframe());

        let recovered = decoders
            .decode_result(result_with(vec![
                video("a", vec![h264(4, true)]),
                video("b", vec![h264(12, false)]),
            ]))
            .unwrap();
        assert!(!decoders.needs_keyframe());
        assert_eq!(recovered.tracks[0].frames[0].data, [3, 0]);
        assert_eq!(recovered.tracks[1].frames[0].data, [2, 2]);
        assert_eq!(
            *log.lock().unwrap(),
            [(1, 1), (2, 10), (2, 11), (3, 4), (2, 12)]
        );
    }

    /// Resetting, as the data channel closes, drops every track's state and
    /// any pending keyframe request: the next connection's streams start
    /// again at their keyframes, with fresh decoders, once it opens.
    #[test]
    fn reset_drops_every_tracks_state() {
        let (decoders, log) = counting_decoders();
        decoders
            .decode_result(result_with(vec![
                video("a", vec![h264(1, true)]),
                video("b", vec![h264(10, true)]),
            ]))
            .unwrap();
        let _ = decoders.decode_result(result_with(vec![video("a", vec![h264(0xFF, false)])]));
        assert_eq!(decoders.live_tracks(), ["b"]);
        assert!(decoders.needs_keyframe());

        decoders.reset();
        assert!(decoders.live_tracks().is_empty());
        assert!(!decoders.needs_keyframe());

        // A result of the closed connection, still queued, starts no stream.
        assert!(decoders
            .decode_result(result_with(vec![video("b", vec![h264(13, true)])]))
            .is_err());
        assert!(decoders.live_tracks().is_empty());
        assert!(!decoders.needs_keyframe());
        decoders.open();

        assert!(decoders
            .decode_result(result_with(vec![video("b", vec![h264(11, false)])]))
            .is_err());
        let fresh = decoders
            .decode_result(result_with(vec![video("b", vec![h264(12, true)])]))
            .unwrap();
        assert_eq!(fresh.tracks[0].frames[0].data, [3, 0]);
        assert_eq!(*log.lock().unwrap(), [(1, 1), (2, 10), (3, 12)]);
    }

    /// Raw frames keep no state: a raw track never needs a keyframe, and a
    /// bad raw frame asks for none.
    #[test]
    fn raw_tracks_keep_no_state() {
        let decoders = FrameDecoders::default();
        let bad = decoders.decode_result(result_with(vec![video(
            "v",
            vec![raw("int16", vec![2, 2], vec![0; 6])],
        )]));
        assert!(matches!(bad, Err(CoreError::Decode(_))));
        assert!(!decoders.needs_keyframe());
        assert!(decoders.live_tracks().is_empty());
    }

    /// A result that says its step did not run is that failure, whatever
    /// its tracks; its frames still move the streams on.
    #[test]
    fn a_failure_code_wins_over_its_tracks_which_still_decode() {
        let (decoders, log) = counting_decoders();
        let result = decoders.decode_result(StepResult {
            failure_code: StepFailureCode::ResultTooLarge as i32,
            ..result_with(vec![video("a", vec![h264(1, true)])])
        });
        assert!(matches!(result, Err(CoreError::StepResultTooLarge { .. })));
        assert_eq!(*log.lock().unwrap(), [(1, 1)]);
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
