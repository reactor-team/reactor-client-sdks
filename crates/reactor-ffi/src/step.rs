//! The owned side of `reactor_step`: what a step's completion hands the host,
//! and the parsing of what the host hands in.
//!
//! A [`ReactorStepResult`] is built once, on the tokio worker that decoded the
//! step, and given to the host as an opaque pointer it frees with
//! `reactor_step_result_free`. Every accessor borrows from it, so a frame's
//! bytes are moved in from the core's [`DecodedFrame`] and never copied again:
//! reading `data` twice returns the same pointer.

use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_int};

use reactor_core::error::CoreError;
use reactor_core::protocol::session::TrackKind;
use reactor_core::protocol::wire::v1::step::FrameEncoding;
use reactor_core::step::{DecodedFrame, StepCommand, StepFile, StepOutput, TrackOutput};
use serde_json::Value;

/// One step's output, owned by the host between the completion and
/// `reactor_step_result_free`.
pub struct ReactorStepResult {
    pub(crate) step: u64,
    pub(crate) tracks: Vec<StepTrack>,
    pub(crate) elapsed: f64,
    pub(crate) files: Vec<StepFileEntry>,
    pub(crate) error: Option<CString>,
}

/// A reference to an extra file the model kept with the step; the bytes are
/// fetched over HTTP, not carried here.
pub(crate) struct StepFileEntry {
    pub(crate) name: CString,
    pub(crate) content_type: CString,
    pub(crate) size: u64,
}

pub(crate) struct StepTrack {
    pub(crate) name: CString,
    pub(crate) kind: &'static CStr,
    pub(crate) rate: f64,
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
        FrameEncoding::H264 => c"h264",
        FrameEncoding::Unspecified => c"unspecified",
    }
}

impl From<StepOutput> for ReactorStepResult {
    fn from(output: StepOutput) -> Self {
        Self {
            step: output.step,
            tracks: output
                .tracks
                .into_iter()
                .map(
                    |TrackOutput {
                         track,
                         kind,
                         frames,
                         rate,
                     }| StepTrack {
                        name: c_string(track),
                        kind: kind_str(kind),
                        rate,
                        frames: frames.into_iter().map(StepFrame::from).collect(),
                    },
                )
                .collect(),
            elapsed: output.elapsed,
            files: output.files.into_iter().map(StepFileEntry::from).collect(),
            error: output.error.map(c_string),
        }
    }
}

impl From<StepFile> for StepFileEntry {
    fn from(file: StepFile) -> Self {
        Self {
            name: c_string(file.name),
            content_type: c_string(file.content_type),
            size: file.size,
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

    pub(crate) fn file(&self, file: u32) -> Option<&StepFileEntry> {
        self.files.get(file as usize)
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

// ── Accessors of a step result ──────────────────────────────────────────────

/// The step's number in the session, counted from 1. 0 for a null result.
///
/// # Safety
///
/// `result` must be null or a live result from a `reactor_step` completion,
/// not yet passed to [`reactor_step_result_free`]. The same holds for every
/// `reactor_step_result_*` accessor below, and every pointer they return
/// borrows from `result`: valid until it is freed, and never to be freed on
/// its own.
#[no_mangle]
pub unsafe extern "C" fn reactor_step_result_step(result: *const ReactorStepResult) -> u64 {
    result.as_ref().map_or(0, |r| r.step)
}

/// Seconds the step's `generate()` took, or the time the model reported for
/// the step. 0.0 for a null result.
///
/// # Safety
///
/// As [`reactor_step_result_step`].
#[no_mangle]
pub unsafe extern "C" fn reactor_step_result_elapsed(result: *const ReactorStepResult) -> f64 {
    result.as_ref().map_or(0.0, |r| r.elapsed)
}

/// Why the step failed, when it ran and the model reported a failure; null
/// when it worked, and for a null result. A step that did not run never gets
/// here: its completion fails with `step_refused` or `step_not_ready`.
///
/// # Safety
///
/// As [`reactor_step_result_step`].
#[no_mangle]
pub unsafe extern "C" fn reactor_step_result_error(
    result: *const ReactorStepResult,
) -> *const c_char {
    result
        .as_ref()
        .and_then(|r| r.error.as_deref())
        .map_or(std::ptr::null(), CStr::as_ptr)
}

/// How many extra files the model kept with the step, in the order the
/// runtime listed them. 0 when it kept none, when its result was not saved,
/// and for a null result.
///
/// # Safety
///
/// As [`reactor_step_result_step`].
#[no_mangle]
pub unsafe extern "C" fn reactor_step_result_file_count(result: *const ReactorStepResult) -> u32 {
    result.as_ref().map_or(0, |r| r.files.len() as u32)
}

/// The file's name, one path segment — the name to fetch its bytes by from the
/// session's step results. Null when out of range.
///
/// # Safety
///
/// As [`reactor_step_result_step`].
#[no_mangle]
pub unsafe extern "C" fn reactor_step_result_file_name(
    result: *const ReactorStepResult,
    file: u32,
) -> *const c_char {
    result
        .as_ref()
        .and_then(|r| r.file(file))
        .map_or(std::ptr::null(), |f| f.name.as_ptr())
}

/// The file's media type, guessed from its name. Null when out of range.
///
/// # Safety
///
/// As [`reactor_step_result_step`].
#[no_mangle]
pub unsafe extern "C" fn reactor_step_result_file_content_type(
    result: *const ReactorStepResult,
    file: u32,
) -> *const c_char {
    result
        .as_ref()
        .and_then(|r| r.file(file))
        .map_or(std::ptr::null(), |f| f.content_type.as_ptr())
}

/// The file's size in bytes. 0 when out of range.
///
/// # Safety
///
/// As [`reactor_step_result_step`].
#[no_mangle]
pub unsafe extern "C" fn reactor_step_result_file_size(
    result: *const ReactorStepResult,
    file: u32,
) -> u64 {
    result
        .as_ref()
        .and_then(|r| r.file(file))
        .map_or(0, |f| f.size)
}

/// How many output tracks the step produced frames for, in the order the
/// runtime sent them. 0 for a null result.
///
/// # Safety
///
/// As [`reactor_step_result_step`].
#[no_mangle]
pub unsafe extern "C" fn reactor_step_result_track_count(result: *const ReactorStepResult) -> u32 {
    result.as_ref().map_or(0, |r| r.tracks.len() as u32)
}

/// The name of track `track`, or null when out of range.
///
/// # Safety
///
/// As [`reactor_step_result_step`].
#[no_mangle]
pub unsafe extern "C" fn reactor_step_result_track_name(
    result: *const ReactorStepResult,
    track: u32,
) -> *const c_char {
    result
        .as_ref()
        .and_then(|r| r.track(track))
        .map_or(std::ptr::null(), |t| t.name.as_ptr())
}

/// `"video"` or `"audio"` for track `track` — a static string — or null when
/// out of range.
///
/// # Safety
///
/// As [`reactor_step_result_step`].
#[no_mangle]
pub unsafe extern "C" fn reactor_step_result_track_kind(
    result: *const ReactorStepResult,
    track: u32,
) -> *const c_char {
    result
        .as_ref()
        .and_then(|r| r.track(track))
        .map_or(std::ptr::null(), |t| t.kind.as_ptr())
}

/// The rate track `track`'s frames play at, as the runtime reported it:
/// frames per second for video, samples per second for audio. 0.0 when the
/// runtime did not say, or when out of range.
///
/// # Safety
///
/// As [`reactor_step_result_step`].
#[no_mangle]
pub unsafe extern "C" fn reactor_step_result_track_rate(
    result: *const ReactorStepResult,
    track: u32,
) -> f64 {
    result
        .as_ref()
        .and_then(|r| r.track(track))
        .map_or(0.0, |t| t.rate)
}

/// How many frames track `track` produced, in production order. 0 when out of
/// range.
///
/// # Safety
///
/// As [`reactor_step_result_step`].
#[no_mangle]
pub unsafe extern "C" fn reactor_step_result_frame_count(
    result: *const ReactorStepResult,
    track: u32,
) -> u32 {
    result
        .as_ref()
        .and_then(|r| r.track(track))
        .map_or(0, |t| t.frames.len() as u32)
}

/// How the frame travelled on the wire, a static string: `"raw"` today. The
/// bytes from [`reactor_step_result_frame_data`] are already decoded whatever
/// this says. Null when out of range.
///
/// # Safety
///
/// As [`reactor_step_result_step`].
#[no_mangle]
pub unsafe extern "C" fn reactor_step_result_frame_encoding(
    result: *const ReactorStepResult,
    track: u32,
    frame: u32,
) -> *const c_char {
    result
        .as_ref()
        .and_then(|r| r.frame(track, frame))
        .map_or(std::ptr::null(), |f| f.encoding.as_ptr())
}

/// The frame's element type in NumPy notation — `"uint8"` for video, `"int16"`
/// for audio. Null when out of range.
///
/// # Safety
///
/// As [`reactor_step_result_step`].
#[no_mangle]
pub unsafe extern "C" fn reactor_step_result_frame_dtype(
    result: *const ReactorStepResult,
    track: u32,
    frame: u32,
) -> *const c_char {
    result
        .as_ref()
        .and_then(|r| r.frame(track, frame))
        .map_or(std::ptr::null(), |f| f.dtype.as_ptr())
}

/// The frame's shape: `*ndim` dimensions — `(height, width, 3)` for video,
/// `(channels, samples)` for audio. Null with `*ndim` 0 when out of range.
///
/// # Safety
///
/// As [`reactor_step_result_step`]; `ndim` must be writable.
#[no_mangle]
pub unsafe extern "C" fn reactor_step_result_frame_shape(
    result: *const ReactorStepResult,
    track: u32,
    frame: u32,
    ndim: *mut u32,
) -> *const u32 {
    let found = result.as_ref().and_then(|r| r.frame(track, frame));
    if !ndim.is_null() {
        *ndim = found.map_or(0, |f| f.shape.len() as u32);
    }
    found.map_or(std::ptr::null(), |f| f.shape.as_ptr())
}

/// The frame's array bytes, row-major and little-endian, `*len` of them. Null
/// with `*len` 0 when out of range.
///
/// # Safety
///
/// As [`reactor_step_result_step`]; `len` must be writable.
#[no_mangle]
pub unsafe extern "C" fn reactor_step_result_frame_data(
    result: *const ReactorStepResult,
    track: u32,
    frame: u32,
    len: *mut usize,
) -> *const u8 {
    let found = result.as_ref().and_then(|r| r.frame(track, frame));
    if !len.is_null() {
        *len = found.map_or(0, |f| f.data.len());
    }
    found.map_or(std::ptr::null(), |f| f.data.as_ptr())
}

/// What the sender attached to the frame, `*len` bytes, opaque to the
/// transport. Null with `*len` 0 when the frame carries none, or when out of
/// range; an attached but empty tag is non-null with `*len` 0.
///
/// # Safety
///
/// As [`reactor_step_result_step`]; `len` must be writable.
#[no_mangle]
pub unsafe extern "C" fn reactor_step_result_frame_metadata(
    result: *const ReactorStepResult,
    track: u32,
    frame: u32,
    len: *mut usize,
) -> *const u8 {
    let metadata = result
        .as_ref()
        .and_then(|r| r.frame(track, frame))
        .and_then(|f| f.metadata.as_ref());
    if !len.is_null() {
        *len = metadata.map_or(0, Vec::len);
    }
    metadata.map_or(std::ptr::null(), |m| m.as_ptr())
}

/// When the sender says the frame was captured, in microseconds: 1 with the
/// value in `*capture_time_us`, or 0 — leaving it untouched — when the frame
/// carries none or is out of range.
///
/// # Safety
///
/// As [`reactor_step_result_step`]; `capture_time_us` must be writable.
#[no_mangle]
pub unsafe extern "C" fn reactor_step_result_frame_capture_time_us(
    result: *const ReactorStepResult,
    track: u32,
    frame: u32,
    capture_time_us: *mut i64,
) -> c_int {
    let time = result
        .as_ref()
        .and_then(|r| r.frame(track, frame))
        .and_then(|f| f.capture_time_us);
    match time {
        Some(t) if !capture_time_us.is_null() => {
            *capture_time_us = t;
            1
        }
        _ => 0,
    }
}

/// Release a step result, and every pointer its accessors returned. Null is a
/// no-op.
///
/// # Safety
///
/// `result` must be null or a result from a `reactor_step` completion that has
/// not already been freed. It need not outlive the handle: a result is the
/// host's alone once delivered, and may be freed after [`reactor_destroy`](crate::reactor_destroy).
#[no_mangle]
pub unsafe extern "C" fn reactor_step_result_free(result: *mut ReactorStepResult) {
    if !result.is_null() {
        drop(Box::from_raw(result));
    }
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
            step: 3,
            tracks: vec![TrackOutput {
                track: "main_video".into(),
                kind: TrackKind::Video,
                frames: vec![frame(bytes)],
                rate: 24.0,
            }],
            elapsed: 0.75,
            files: vec![StepFile {
                name: "depth.npy".into(),
                content_type: "application/octet-stream".into(),
                size: 4096,
            }],
            error: Some("model said no\0pe".into()),
        });
        assert_eq!(result.step, 3);
        assert_eq!(result.elapsed, 0.75);
        let track = result.track(0).unwrap();
        assert_eq!(track.name.to_str().unwrap(), "main_video");
        assert_eq!(track.kind, c"video");
        assert_eq!(track.rate, 24.0);
        let f = result.frame(0, 0).unwrap();
        assert_eq!(f.data.as_ptr(), address);
        assert_eq!(f.encoding, c"raw");
        assert_eq!(f.dtype.to_str().unwrap(), "uint8");
        assert_eq!(f.shape, vec![1, 2, 3]);
        assert_eq!(f.metadata.as_deref(), Some(&b"tag"[..]));
        assert_eq!(f.capture_time_us, Some(42));
        assert!(result.frame(0, 1).is_none());
        assert!(result.track(1).is_none());
        let file = result.file(0).unwrap();
        assert_eq!(file.name.to_str().unwrap(), "depth.npy");
        assert_eq!(
            file.content_type.to_str().unwrap(),
            "application/octet-stream"
        );
        assert_eq!(file.size, 4096);
        assert!(result.file(1).is_none());
        assert_eq!(
            result.error.as_deref().map(|e| e.to_str().unwrap()),
            Some("model said nope")
        );
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
