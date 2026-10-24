//! Stepped generation at the JS boundary: `step()`'s commands in, and a
//! step's result out as a JS object whose frame data are typed arrays.
//!
//! The result is built by hand rather than serialized by serde: serde has no
//! vocabulary for a typed array, and a frame's bytes as a JSON-style array of
//! numbers would cost a JS value per byte. Being hand-built, its fields are
//! camelCase like the rest of the JS API, not the wire's snake_case.

use js_sys::{Array, Object, Reflect};
use serde::Deserialize;
use serde_json::Value;
use wasm_bindgen::prelude::*;

use reactor_core::protocol::session::TrackKind;
use reactor_core::protocol::wire::v1::step::FrameEncoding;
use reactor_core::step::{DecodedFrame, StepCommand, StepFile, StepOutput, TrackOutput};

/// One entry of `step()`'s `commands`, as JS writes it.
#[derive(Debug, Deserialize)]
pub(crate) struct StepCommandJson {
    command: String,
    /// The command's arguments, an object. Omitted or `null` for none.
    #[serde(default)]
    data: Option<Value>,
}

impl From<StepCommandJson> for StepCommand {
    fn from(command: StepCommandJson) -> Self {
        StepCommand::new(command.command, command.data.unwrap_or(Value::Null))
    }
}

// ── Result ──────────────────────────────────────────────────────────────────

/// `StepOutput` as the `StepResult` the TypeScript declarations promise.
pub(crate) fn output_to_js(output: StepOutput) -> Result<JsValue, JsValue> {
    let result = Object::new();
    set(&result, "step", &JsValue::from_f64(output.step as f64))?;
    let tracks = Array::new();
    for track in output.tracks {
        tracks.push(&track_to_js(track)?);
    }
    set(&result, "tracks", &tracks)?;
    set(&result, "elapsed", &JsValue::from_f64(output.elapsed))?;
    let files = Array::new();
    for file in output.files {
        files.push(&file_to_js(file)?);
    }
    set(&result, "files", &files)?;
    if let Some(error) = output.error {
        set(&result, "error", &JsValue::from_str(&error))?;
    }
    Ok(result.into())
}

fn file_to_js(file: StepFile) -> Result<JsValue, JsValue> {
    let object = Object::new();
    set(&object, "name", &JsValue::from_str(&file.name))?;
    set(
        &object,
        "contentType",
        &JsValue::from_str(&file.content_type),
    )?;
    set(&object, "size", &JsValue::from_f64(file.size as f64))?;
    Ok(object.into())
}

fn track_to_js(track: TrackOutput) -> Result<JsValue, JsValue> {
    let object = Object::new();
    set(&object, "track", &JsValue::from_str(&track.track))?;
    let kind = match track.kind {
        TrackKind::Video => "video",
        TrackKind::Audio => "audio",
    };
    set(&object, "kind", &JsValue::from_str(kind))?;
    set(&object, "rate", &JsValue::from_f64(track.rate))?;
    let frames = Array::new();
    for frame in track.frames {
        frames.push(&frame_to_js(frame)?);
    }
    set(&object, "frames", &frames)?;
    Ok(object.into())
}

fn frame_to_js(frame: DecodedFrame) -> Result<JsValue, JsValue> {
    let object = Object::new();
    let encoding = match frame.encoding {
        FrameEncoding::Raw => "raw",
        FrameEncoding::H264 => "h264",
        FrameEncoding::Unspecified => "unspecified",
    };
    set(&object, "encoding", &JsValue::from_str(encoding))?;
    set(&object, "dtype", &JsValue::from_str(&frame.dtype))?;
    let shape: Array = frame
        .shape
        .iter()
        .map(|&d| JsValue::from_f64(f64::from(d)))
        .collect();
    set(&object, "shape", &shape)?;
    set(&object, "data", &typed_array(&frame.dtype, &frame.data))?;
    if let Some(metadata) = frame.metadata {
        set(
            &object,
            "metadata",
            &js_sys::Uint8Array::from(metadata.as_slice()),
        )?;
    }
    if let Some(us) = frame.capture_time_us {
        set(&object, "captureTimeUs", &JsValue::from_f64(us as f64))?;
    }
    Ok(object.into())
}

/// Little-endian array bytes as the typed array their `dtype` names. A dtype
/// with no typed-array counterpart here stays a `Uint8Array` of the raw bytes.
fn typed_array(dtype: &str, bytes: &[u8]) -> JsValue {
    macro_rules! from_le {
        ($ty:ty, $array:ty) => {{
            const SIZE: usize = std::mem::size_of::<$ty>();
            let values: Vec<$ty> = bytes
                .chunks_exact(SIZE)
                .map(|chunk| <$ty>::from_le_bytes(chunk.try_into().expect("chunk is SIZE bytes")))
                .collect();
            <$array>::from(values.as_slice()).into()
        }};
    }
    match dtype {
        "int8" => from_le!(i8, js_sys::Int8Array),
        "uint16" => from_le!(u16, js_sys::Uint16Array),
        "int16" => from_le!(i16, js_sys::Int16Array),
        "uint32" => from_le!(u32, js_sys::Uint32Array),
        "int32" => from_le!(i32, js_sys::Int32Array),
        "float32" => from_le!(f32, js_sys::Float32Array),
        "float64" => from_le!(f64, js_sys::Float64Array),
        _ => js_sys::Uint8Array::from(bytes).into(),
    }
}

fn set(target: &Object, key: &str, value: &JsValue) -> Result<(), JsValue> {
    Reflect::set(target, &JsValue::from_str(key), value).map(|_| ())
}
