//! Pixel layouts a pushed video frame may arrive in, checked here and handed
//! to the core as they are: it converts them to the step's RGB in one pass.
//! Also the codecs a pushed *encoded* frame may carry, checked the same way.
//!
//! Plain Rust with no browser dependency, unlike the rest of this crate, so
//! it compiles — and its tests run — on the host too.

// Only the wasm client calls into this; on the host only the tests do.
#![cfg_attr(not(target_family = "wasm"), allow(dead_code))]

/// The byte layout of a pushed video frame, as JS names it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum PixelFormat {
    /// 4 bytes per pixel, R G B A — `ImageData`, `VideoFrame.copyTo` with
    /// `format: "RGBA"`.
    Rgba,
    /// 3 bytes per pixel, R G B — the layout a step's own video output has.
    Rgb,
    /// 4 bytes per pixel, B G R A.
    Bgra,
}

impl PixelFormat {
    pub(crate) fn parse(name: Option<&str>) -> Result<Self, String> {
        match name.unwrap_or("rgba") {
            "rgba" => Ok(Self::Rgba),
            "rgb" => Ok(Self::Rgb),
            "bgra" => Ok(Self::Bgra),
            other => Err(format!(
                "unknown pixel format '{other}' (expected \"rgba\", \"rgb\" or \"bgra\")"
            )),
        }
    }

    fn bytes_per_pixel(self) -> usize {
        match self {
            Self::Rgb => 3,
            Self::Rgba | Self::Bgra => 4,
        }
    }
}

/// Whether `data` is a whole `width`x`height` frame in `format`, or why it is
/// not: the core would only drop such a frame with a log line, and the caller
/// should see the mistake.
pub(crate) fn check_size(
    data: &[u8],
    width: u32,
    height: u32,
    format: PixelFormat,
) -> Result<(), String> {
    if width == 0 || height == 0 {
        return Err(format!("a {width}x{height} frame has no pixels"));
    }
    let expected = (width as usize)
        .checked_mul(height as usize)
        .and_then(|pixels| pixels.checked_mul(format.bytes_per_pixel()))
        .ok_or_else(|| format!("a {width}x{height} frame is too large"))?;
    if data.len() != expected {
        return Err(format!(
            "{} bytes is not a {width}x{height} {format:?} frame ({expected} bytes)",
            data.len()
        ));
    }
    Ok(())
}

/// The codec of a pushed encoded video frame, as JS names it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum EncodedCodec {
    /// One H.264 access unit, Annex B.
    H264,
}

impl EncodedCodec {
    pub(crate) fn parse(name: Option<&str>) -> Result<Self, String> {
        match name.unwrap_or("h264") {
            "h264" => Ok(Self::H264),
            other => Err(format!("unknown codec '{other}' (expected \"h264\")")),
        }
    }
}

/// Whether `data` can be an encoded frame that decodes to `width`x`height`,
/// or why it cannot: the core would only drop it with a log line. Whether it
/// really decodes is the runtime's to say.
pub(crate) fn check_encoded(data: &[u8], width: u32, height: u32) -> Result<(), String> {
    if data.is_empty() {
        return Err("an encoded frame needs data".into());
    }
    if width == 0 || height == 0 {
        return Err(format!(
            "width and height are the decoded frame's, and a {width}x{height} frame has no pixels"
        ));
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_whole_frame_in_each_format_passes() {
        assert_eq!(check_size(&[0; 8], 2, 1, PixelFormat::Rgba), Ok(()));
        assert_eq!(check_size(&[0; 3], 1, 1, PixelFormat::Rgb), Ok(()));
        assert_eq!(check_size(&[0; 4], 1, 1, PixelFormat::Bgra), Ok(()));
    }

    #[test]
    fn a_size_that_does_not_match_the_dimensions_is_refused() {
        assert!(check_size(&[0; 7], 1, 2, PixelFormat::Rgba).is_err());
        assert!(check_size(&[0; 4], 1, 1, PixelFormat::Rgb).is_err());
        assert!(check_size(&[], 0, 0, PixelFormat::Rgba).is_err());
    }

    #[test]
    fn pixel_formats_parse_with_rgba_as_the_default() {
        assert_eq!(PixelFormat::parse(None), Ok(PixelFormat::Rgba));
        assert_eq!(PixelFormat::parse(Some("rgb")), Ok(PixelFormat::Rgb));
        assert!(PixelFormat::parse(Some("yuv")).is_err());
    }

    #[test]
    fn codecs_parse_with_h264_as_the_default() {
        assert_eq!(EncodedCodec::parse(None), Ok(EncodedCodec::H264));
        assert_eq!(EncodedCodec::parse(Some("h264")), Ok(EncodedCodec::H264));
        assert!(EncodedCodec::parse(Some("vp9")).is_err());
    }

    #[test]
    fn an_encoded_frame_needs_data_and_a_size() {
        assert_eq!(check_encoded(&[0, 0, 1, 0x65], 1280, 720), Ok(()));
        assert!(check_encoded(&[], 2, 2).is_err());
        assert!(check_encoded(&[1], 0, 2).is_err());
        assert!(check_encoded(&[1], 2, 0).is_err());
    }
}
