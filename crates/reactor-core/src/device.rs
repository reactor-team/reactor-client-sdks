//! The device's hardware model, as the system names it, for the client
//! stats environment.

/// The longest model this reports, in bytes. A longer name is cut at a char
/// boundary: a model is a short identifier, and the stats reading it travels
/// in caps the length of every name.
const MAX_LEN: usize = 64;

/// The device's hardware model, e.g. `"iPhone15,2"`, `"Pixel 8"` or
/// `"MacBookPro18,3"`; empty when the system does not expose it.
pub(crate) fn model() -> String {
    clean(read().unwrap_or_default())
}

fn clean(raw: String) -> String {
    let mut model = raw
        .trim_matches(|c: char| c.is_whitespace() || c == '\0')
        .to_string();
    if model.len() > MAX_LEN {
        let mut end = MAX_LEN;
        while !model.is_char_boundary(end) {
            end -= 1;
        }
        model.truncate(end);
    }
    model
}

/// On iOS `hw.machine` is the device identifier; on macOS that is the CPU
/// architecture, and `hw.model` is the identifier. An iOS simulator names the
/// device it simulates in its environment.
#[cfg(target_vendor = "apple")]
fn read() -> Option<String> {
    if cfg!(any(
        target_os = "ios",
        target_os = "tvos",
        target_os = "visionos"
    )) {
        if let Ok(simulated) = std::env::var("SIMULATOR_MODEL_IDENTIFIER") {
            return Some(simulated);
        }
        sysctl(c"hw.machine")
    } else {
        sysctl(c"hw.model")
    }
}

#[cfg(target_vendor = "apple")]
fn sysctl(name: &std::ffi::CStr) -> Option<String> {
    let mut len: libc::size_t = 0;
    // SAFETY: a null buffer asks only for the value's length, written to `len`.
    let rc = unsafe {
        libc::sysctlbyname(
            name.as_ptr(),
            std::ptr::null_mut(),
            &mut len,
            std::ptr::null_mut(),
            0,
        )
    };
    if rc != 0 || len == 0 {
        return None;
    }
    let mut buf = vec![0u8; len];
    // SAFETY: `buf` holds `len` writable bytes, and sysctlbyname writes at
    // most `len` bytes and stores the count it wrote back in `len`.
    let rc = unsafe {
        libc::sysctlbyname(
            name.as_ptr(),
            buf.as_mut_ptr().cast(),
            &mut len,
            std::ptr::null_mut(),
            0,
        )
    };
    if rc != 0 {
        return None;
    }
    buf.truncate(len);
    Some(String::from_utf8_lossy(&buf).into_owned())
}

#[cfg(target_os = "android")]
fn read() -> Option<String> {
    let mut buf = [0u8; libc::PROP_VALUE_MAX as usize];
    // SAFETY: the property value, NUL included, is at most PROP_VALUE_MAX
    // bytes, which `buf` holds.
    let len = unsafe {
        libc::__system_property_get(c"ro.product.model".as_ptr(), buf.as_mut_ptr().cast())
    };
    let len = usize::try_from(len).ok().filter(|&n| n > 0)?;
    Some(String::from_utf8_lossy(&buf[..len.min(buf.len())]).into_owned())
}

/// The firmware's product name on a PC or server, or the device tree's model
/// on a board such as a Raspberry Pi.
#[cfg(target_os = "linux")]
fn read() -> Option<String> {
    [
        "/sys/devices/virtual/dmi/id/product_name",
        "/proc/device-tree/model",
    ]
    .iter()
    .find_map(|path| std::fs::read(path).ok())
    .map(|bytes| String::from_utf8_lossy(&bytes).into_owned())
}

#[cfg(not(any(target_vendor = "apple", target_os = "android", target_os = "linux")))]
fn read() -> Option<String> {
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_model_is_trimmed_of_whitespace_and_nul() {
        assert_eq!(
            clean("Raspberry Pi 4 Model B\0".to_string()),
            "Raspberry Pi 4 Model B"
        );
        assert_eq!(clean("  Pixel 8\n".to_string()), "Pixel 8");
    }

    #[test]
    fn a_long_model_is_cut_at_a_char_boundary() {
        let long = "é".repeat(MAX_LEN);
        let model = clean(long);
        assert!(model.len() <= MAX_LEN);
        assert!(model.chars().all(|c| c == 'é'));
    }

    #[test]
    fn this_system_reports_a_bounded_model() {
        assert!(model().len() <= MAX_LEN);
    }
}
