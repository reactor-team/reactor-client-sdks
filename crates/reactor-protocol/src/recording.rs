//! Runtime-scoped message-type labels.
//!
//! These string tags no longer appear on the wire (clip/recording/ping/file-
//! upload traffic is now typed `reactor_wire.v1` control-channel messages,
//! see [`crate::wire::v1`]) — they only label the `"type"` field of the
//! JSON `ReactorEvent::RuntimeMessage` events the core still emits for
//! host/app consumption, so external listeners keep seeing the same shape
//! they always did.
pub mod message_type {
    /// Runtime → client: clip is (or will shortly be) available.
    pub const CLIP_READY: &str = "clipReady";
    /// Runtime → client: clip request failed.
    pub const CLIP_FAILED: &str = "clipFailed";
    /// Runtime → client: a content-moderation verdict.
    pub const MODERATION: &str = "moderation";
}
