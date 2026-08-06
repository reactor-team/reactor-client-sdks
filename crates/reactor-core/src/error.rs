//! Error model.
//!
//! Two layers, mirroring the JS/Python SDKs:
//!
//! * [`CoreError`] — the `Result` error type of core operations.
//! * [`ReactorError`] — the user-facing error record emitted through the
//!   event stream (`code`, `recoverable`, `component`, `retry_after`),
//!   stored as "last error" on the client.

use serde::{Deserialize, Serialize};

/// Which tier of the platform an error originated from.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Component {
    /// Coordinator / HTTP API tier.
    Api,
    /// Runtime / GPU / transport tier.
    Gpu,
}

/// Well-known error codes surfaced through [`ReactorError`].
pub mod codes {
    pub const NOT_READY: &str = "NOT_READY";
    pub const CONNECTION_FAILED: &str = "CONNECTION_FAILED";
    pub const RECONNECTION_FAILED: &str = "RECONNECTION_FAILED";
    pub const GPU_CONNECTION_ERROR: &str = "GPU_CONNECTION_ERROR";
    pub const MESSAGE_SEND_FAILED: &str = "MESSAGE_SEND_FAILED";
    pub const TRACK_PUBLISH_FAILED: &str = "TRACK_PUBLISH_FAILED";
    pub const TRACK_UNPUBLISH_FAILED: &str = "TRACK_UNPUBLISH_FAILED";
    pub const INVALID_DURATION: &str = "INVALID_DURATION";
    pub const DISCONNECTED: &str = "DISCONNECTED";
    pub const REQUEST_TIMEOUT: &str = "REQUEST_TIMEOUT";
    pub const INTERNAL_ERROR: &str = "INTERNAL_ERROR";
}

/// User-facing error record (event payload / `last_error`).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ReactorError {
    pub code: String,
    pub message: String,
    /// Unix epoch milliseconds.
    pub timestamp_ms: f64,
    /// Whether `reconnect()` is expected to succeed.
    pub recoverable: bool,
    pub component: Component,
    /// Backoff hint in milliseconds, when the server provided one.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub retry_after_ms: Option<f64>,
}

/// Error type returned by core operations.
#[derive(Debug, thiserror::Error)]
pub enum CoreError {
    #[error("http transport error: {0}")]
    Http(String),

    #[error("unexpected HTTP status {status} from {context}: {body}")]
    Status {
        status: u16,
        context: String,
        body: String,
    },

    /// HTTP 426 (client too old) or 501 (server too old).
    #[error("protocol version mismatch: {0}")]
    VersionMismatch(String),

    #[error("failed to decode response: {0}")]
    Decode(String),

    #[error("invalid state: {0}")]
    InvalidState(String),

    #[error("timed out: {0}")]
    Timeout(String),

    #[error("session entered terminal state {0}")]
    TerminalSession(String),

    #[error("message too large: {size} bytes exceeds {max}")]
    MessageTooLarge { size: usize, max: usize },

    #[error("peer transport error: {0}")]
    Peer(String),

    #[error("control request '{method}' failed ({code}): {message}")]
    ControlRequest {
        method: String,
        code: String,
        message: String,
    },

    #[error("recording error ({code}): {message}")]
    Recording { code: String, message: String },

    #[error("operation aborted")]
    Aborted,
}

impl CoreError {
    pub fn decode(err: impl std::fmt::Display) -> Self {
        CoreError::Decode(err.to_string())
    }
}
