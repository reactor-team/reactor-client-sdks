//! The largest data-channel message a chunked channel declares and accepts:
//! its default, the range a page may set it in, and the send queue it needs.
//!
//! Plain Rust with no browser dependency, unlike the rest of this crate, so
//! it compiles — and its tests run — on the host too.

// Only the wasm client calls into this; on the host only the tests do.
#![cfg_attr(not(target_family = "wasm"), allow(dead_code))]

const MIB: u64 = 1024 * 1024;

/// The limit when the page sets none: 64 MiB, reactor-webrtc's default.
///
/// A page raises it with `maxMessageSize`. The default stays at 64 MiB in the
/// browser: a message is reassembled in wasm memory and then copied out to
/// JS, so one much larger costs a tab several times its size.
pub(crate) const DEFAULT_MAX_MESSAGE_SIZE: u64 = 64 * MIB;
/// The lowest limit a page may set: 256 KiB, what a channel carries unchunked.
pub(crate) const MIN_MAX_MESSAGE_SIZE: u64 = 256 * 1024;
/// The highest limit a page may set: 1 GiB.
pub(crate) const MAX_MAX_MESSAGE_SIZE: u64 = 1024 * MIB;
/// The least a channel may queue ahead of the wire, reactor-webrtc's default.
const MIN_QUEUE_LIMIT: u64 = 128 * MIB;

/// The limit a page asked for, or [`DEFAULT_MAX_MESSAGE_SIZE`] for none.
///
/// Refuses a size outside [`MIN_MAX_MESSAGE_SIZE`] to [`MAX_MAX_MESSAGE_SIZE`],
/// naming the option.
pub(crate) fn max_message_size(requested: Option<u64>) -> Result<u64, String> {
    let Some(size) = requested else {
        return Ok(DEFAULT_MAX_MESSAGE_SIZE);
    };
    if !(MIN_MAX_MESSAGE_SIZE..=MAX_MAX_MESSAGE_SIZE).contains(&size) {
        return Err(format!(
            "options.maxMessageSize must be from {MIN_MAX_MESSAGE_SIZE} (256 KiB) to \
             {MAX_MAX_MESSAGE_SIZE} (1 GiB) bytes, got {size}"
        ));
    }
    Ok(size)
}

/// What a channel may queue for messages of up to `max_message_size` bytes:
/// twice the limit, so one full message can wait behind another, and never
/// less than reactor-webrtc's default.
pub(crate) fn queue_limit(max_message_size: u64) -> u64 {
    MIN_QUEUE_LIMIT.max(max_message_size.saturating_mul(2))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn no_size_is_the_64_mib_default() {
        assert_eq!(max_message_size(None), Ok(64 * MIB));
    }

    #[test]
    fn a_size_from_256_kib_to_1_gib_is_taken() {
        for size in [MIN_MAX_MESSAGE_SIZE, 256 * MIB, MAX_MAX_MESSAGE_SIZE] {
            assert_eq!(max_message_size(Some(size)), Ok(size));
        }
    }

    #[test]
    fn a_size_outside_the_range_is_refused_naming_the_option() {
        for size in [0, MIN_MAX_MESSAGE_SIZE - 1, MAX_MAX_MESSAGE_SIZE + 1] {
            let refused = max_message_size(Some(size)).unwrap_err();
            assert!(
                refused.starts_with("options.maxMessageSize must be"),
                "{refused}"
            );
        }
    }

    #[test]
    fn the_queue_holds_two_messages_and_never_less_than_the_default() {
        assert_eq!(queue_limit(MIN_MAX_MESSAGE_SIZE), 128 * MIB);
        assert_eq!(queue_limit(DEFAULT_MAX_MESSAGE_SIZE), 128 * MIB);
        assert_eq!(queue_limit(256 * MIB), 512 * MIB);
        assert_eq!(queue_limit(MAX_MAX_MESSAGE_SIZE), 2048 * MIB);
    }
}
