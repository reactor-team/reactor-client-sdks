//! Pixel layouts a pushed video frame may arrive in, converted to the BGRA
//! the core's push API takes.
//!
//! Plain Rust with no browser dependency, unlike the rest of this crate, so
//! it compiles — and its tests run — on the host too.

// Only the wasm client calls into this; on the host only the tests do.
#![cfg_attr(not(target_family = "wasm"), allow(dead_code))]

/// The byte layout of a pushed video frame. The core takes BGRA, which is
/// what no browser API hands out by default, so the binding converts.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum PixelFormat {
    /// 4 bytes per pixel, R G B A — `ImageData`, `VideoFrame.copyTo` with
    /// `format: "RGBA"`.
    Rgba,
    /// 3 bytes per pixel, R G B — the layout a step's own video output has.
    Rgb,
    /// 4 bytes per pixel, B G R A — what the core takes as is.
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

/// `data` as the BGRA frame the core's push API takes, or why it cannot be.
pub(crate) fn to_bgra(
    data: &[u8],
    width: u32,
    height: u32,
    format: PixelFormat,
) -> Result<Vec<u8>, String> {
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
    Ok(match format {
        PixelFormat::Bgra => data.to_vec(),
        PixelFormat::Rgba => {
            let mut bgra = data.to_vec();
            for px in bgra.chunks_exact_mut(4) {
                px.swap(0, 2);
            }
            bgra
        }
        PixelFormat::Rgb => {
            let mut bgra = Vec::with_capacity(expected / 3 * 4);
            for px in data.chunks_exact(3) {
                bgra.extend_from_slice(&[px[2], px[1], px[0], 0xff]);
            }
            bgra
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rgba_swaps_red_and_blue() {
        let bgra = to_bgra(&[1, 2, 3, 4, 5, 6, 7, 8], 2, 1, PixelFormat::Rgba).unwrap();
        assert_eq!(bgra, vec![3, 2, 1, 4, 7, 6, 5, 8]);
    }

    #[test]
    fn rgb_gains_an_opaque_alpha() {
        let bgra = to_bgra(&[1, 2, 3], 1, 1, PixelFormat::Rgb).unwrap();
        assert_eq!(bgra, vec![3, 2, 1, 0xff]);
    }

    #[test]
    fn bgra_passes_through() {
        assert_eq!(
            to_bgra(&[1, 2, 3, 4], 1, 1, PixelFormat::Bgra).unwrap(),
            vec![1, 2, 3, 4]
        );
    }

    #[test]
    fn a_size_that_does_not_match_the_dimensions_is_refused() {
        assert!(to_bgra(&[0; 7], 1, 2, PixelFormat::Rgba).is_err());
        assert!(to_bgra(&[], 0, 0, PixelFormat::Rgba).is_err());
    }

    #[test]
    fn pixel_formats_parse_with_rgba_as_the_default() {
        assert_eq!(PixelFormat::parse(None), Ok(PixelFormat::Rgba));
        assert_eq!(PixelFormat::parse(Some("rgb")), Ok(PixelFormat::Rgb));
        assert!(PixelFormat::parse(Some("yuv")).is_err());
    }
}
