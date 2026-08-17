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

    // ── Codes a failed operation reports for itself ──────────────────────────
    //
    // The codes above are chosen by the call site that emits the error event, and
    // say what the caller was doing. These are chosen by the error itself, and say
    // what went wrong — a distinction that matters because they answer different
    // questions: CONNECTION_FAILED tells you connect() did not work, UNAUTHORIZED
    // tells you why and what to do about it.

    /// The request never got a reply — DNS, TLS, a refused socket.
    pub const NETWORK_ERROR: &str = "NETWORK_ERROR";
    /// 401 or 403: the token is missing, expired, or not scoped for this.
    pub const UNAUTHORIZED: &str = "UNAUTHORIZED";
    /// 404: no such model, session or upload.
    pub const NOT_FOUND: &str = "NOT_FOUND";
    /// 409: the session is in a state that does not allow this.
    pub const CONFLICT: &str = "CONFLICT";
    /// 429: too many requests. Honour `retry_after_ms` when one is given.
    pub const RATE_LIMITED: &str = "RATE_LIMITED";
    /// 4xx other than the above: the request itself was wrong.
    pub const BAD_REQUEST: &str = "BAD_REQUEST";
    /// 5xx: the coordinator failed, and the same request may work later.
    pub const SERVER_ERROR: &str = "SERVER_ERROR";
    /// 426 or 501: this client and the platform disagree on the protocol.
    pub const VERSION_MISMATCH: &str = "VERSION_MISMATCH";
    /// A reply arrived and could not be understood.
    pub const DECODE_FAILED: &str = "DECODE_FAILED";
    /// The operation is not allowed from the state the client is in.
    pub const INVALID_STATE: &str = "INVALID_STATE";
    /// The session reached a state it cannot leave.
    pub const SESSION_TERMINAL: &str = "SESSION_TERMINAL";
    /// The payload exceeds what the data channel accepts.
    pub const MESSAGE_TOO_LARGE: &str = "MESSAGE_TOO_LARGE";
    /// The WebRTC transport failed.
    pub const PEER_ERROR: &str = "PEER_ERROR";
    /// The operation was abandoned before it finished.
    pub const ABORTED: &str = "ABORTED";
}

/// A failure, in the terms a caller can act on: what went wrong, where, and
/// whether trying again is worth anything.
///
/// This is what [`CoreError`] flattens down to for a binding — a `Display` string
/// is enough to log and not enough to branch on, and every SDK above this was
/// reduced to matching on message text or giving up.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ErrorDetails {
    /// A stable, matchable code. One of [`codes`], or a code the platform sent —
    /// which is why this is a `String` and consumers must tolerate unknown values.
    pub code: String,
    pub message: String,
    pub component: Component,
    /// Whether the same call could succeed later, after a `reconnect()` or a wait.
    pub recoverable: bool,
    /// The HTTP status, when the failure came from one.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub status: Option<u16>,
    /// Which operation failed, when the caller of the FFI names it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub operation: Option<String>,
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

    #[error("command '{command}' failed ({code}): {message}")]
    CommandRequest {
        command: String,
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

    /// A stable code for this failure.
    ///
    /// The three request variants report the code the platform sent rather than
    /// one of ours: it is more specific than anything derivable here, and passing
    /// it through is what lets a caller handle a model's own rejection reasons.
    /// Empty ones fall back, since a code that is the empty string is worse than
    /// a generic one.
    pub fn code(&self) -> &str {
        match self {
            CoreError::ControlRequest { code, .. }
            | CoreError::CommandRequest { code, .. }
            | CoreError::Recording { code, .. }
                if !code.is_empty() =>
            {
                code
            }
            CoreError::ControlRequest { .. }
            | CoreError::CommandRequest { .. }
            | CoreError::Recording { .. } => codes::INTERNAL_ERROR,
            CoreError::Http(_) => codes::NETWORK_ERROR,
            CoreError::Status { status, .. } => match status {
                401 | 403 => codes::UNAUTHORIZED,
                404 => codes::NOT_FOUND,
                409 => codes::CONFLICT,
                426 | 501 => codes::VERSION_MISMATCH,
                429 => codes::RATE_LIMITED,
                400..=499 => codes::BAD_REQUEST,
                _ => codes::SERVER_ERROR,
            },
            CoreError::VersionMismatch(_) => codes::VERSION_MISMATCH,
            CoreError::Decode(_) => codes::DECODE_FAILED,
            CoreError::InvalidState(_) => codes::INVALID_STATE,
            CoreError::Timeout(_) => codes::REQUEST_TIMEOUT,
            CoreError::TerminalSession(_) => codes::SESSION_TERMINAL,
            CoreError::MessageTooLarge { .. } => codes::MESSAGE_TOO_LARGE,
            CoreError::Peer(_) => codes::PEER_ERROR,
            CoreError::Aborted => codes::ABORTED,
        }
    }

    /// Which tier this came from — the coordinator, or the runtime behind it.
    ///
    /// `Api` covers the coordinator and everything on this side of it, including
    /// the failures that never left the client (`InvalidState`, `Aborted`): the
    /// runtime had no part in those, and attributing them to it would send anyone
    /// reading the logs to the wrong tier.
    pub fn component(&self) -> Component {
        match self {
            CoreError::Http(_)
            | CoreError::Status { .. }
            | CoreError::VersionMismatch(_)
            | CoreError::Decode(_)
            | CoreError::InvalidState(_)
            | CoreError::Aborted => Component::Api,
            _ => Component::Gpu,
        }
    }

    /// Whether the same call could succeed later.
    ///
    /// True means the failure is about the moment — a timeout, a 5xx, a transport
    /// that dropped — so reconnecting or waiting is worth something. False means
    /// it is about the request, and repeating it unchanged will fail the same way.
    pub fn recoverable(&self) -> bool {
        match self {
            CoreError::Http(_) | CoreError::Timeout(_) | CoreError::Peer(_) => true,
            CoreError::Status { status, .. } => *status == 429 || *status >= 500,
            _ => false,
        }
    }

    /// The HTTP status behind this failure, if it came from one.
    pub fn status(&self) -> Option<u16> {
        match self {
            CoreError::Status { status, .. } => Some(*status),
            _ => None,
        }
    }

    /// Everything above, in the shape a binding hands to its caller.
    pub fn details(&self, operation: Option<&str>) -> ErrorDetails {
        ErrorDetails {
            code: self.code().to_string(),
            message: self.to_string(),
            component: self.component(),
            recoverable: self.recoverable(),
            status: self.status(),
            operation: operation.map(str::to_string),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn status(status: u16) -> CoreError {
        CoreError::Status {
            status,
            context: "POST /sessions".into(),
            body: String::new(),
        }
    }

    /// The point of the whole exercise: two failures a caller would handle
    /// differently must not arrive as the same thing. Before this they were both
    /// "unexpected HTTP status …" and nothing else.
    #[test]
    fn statuses_a_caller_would_act_on_differently_get_different_codes() {
        assert_eq!(status(401).code(), codes::UNAUTHORIZED);
        assert_eq!(status(403).code(), codes::UNAUTHORIZED);
        assert_eq!(status(404).code(), codes::NOT_FOUND);
        assert_eq!(status(409).code(), codes::CONFLICT);
        assert_eq!(status(429).code(), codes::RATE_LIMITED);
        assert_eq!(status(422).code(), codes::BAD_REQUEST);
        assert_eq!(status(500).code(), codes::SERVER_ERROR);
        assert_eq!(status(503).code(), codes::SERVER_ERROR);
    }

    /// 426 and 501 mean the same thing from opposite directions — this client is
    /// too old, or the platform is — and both are the version mismatch, not a
    /// generic 4xx/5xx.
    #[test]
    fn the_version_statuses_outrank_their_ranges() {
        assert_eq!(status(426).code(), codes::VERSION_MISMATCH);
        assert_eq!(status(501).code(), codes::VERSION_MISMATCH);
    }

    /// A code the platform sent is more specific than anything derivable here, so
    /// it wins — that is what lets a caller handle a model's own reasons.
    #[test]
    fn a_platform_code_is_passed_through() {
        let error = CoreError::CommandRequest {
            command: "set_prompt".into(),
            code: "PROMPT_REJECTED".into(),
            message: "unsafe content".into(),
        };
        assert_eq!(error.code(), "PROMPT_REJECTED");
    }

    /// An empty code is worse than a generic one: it matches nothing and reads as
    /// a missing field rather than a decision.
    #[test]
    fn an_empty_platform_code_falls_back() {
        let error = CoreError::ControlRequest {
            method: "publish_track".into(),
            code: String::new(),
            message: "no".into(),
        };
        assert_eq!(error.code(), codes::INTERNAL_ERROR);
    }

    /// Recoverable is a promise about retrying, so it has to track what is about
    /// the moment rather than what is about the request.
    #[test]
    fn only_failures_that_could_pass_later_are_recoverable() {
        assert!(status(503).recoverable());
        assert!(status(429).recoverable());
        assert!(CoreError::Timeout("connect".into()).recoverable());
        assert!(CoreError::Peer("ice failed".into()).recoverable());
        assert!(CoreError::Http("dns".into()).recoverable());

        assert!(!status(401).recoverable());
        assert!(!status(404).recoverable());
        assert!(!CoreError::InvalidState("not ready".into()).recoverable());
        assert!(!CoreError::MessageTooLarge { size: 2, max: 1 }.recoverable());
    }

    /// A failure that never left the client is not the runtime's fault, and saying
    /// it is sends whoever reads the log to the wrong tier.
    #[test]
    fn client_side_failures_are_not_attributed_to_the_runtime() {
        assert_eq!(
            CoreError::InvalidState("x".into()).component(),
            Component::Api
        );
        assert_eq!(CoreError::Aborted.component(), Component::Api);
        assert_eq!(status(500).component(), Component::Api);
        assert_eq!(CoreError::Peer("x".into()).component(), Component::Gpu);
    }

    #[test]
    fn details_carry_the_status_and_the_operation() {
        let details = status(429).details(Some("connect"));
        assert_eq!(details.code, codes::RATE_LIMITED);
        assert_eq!(details.status, Some(429));
        assert_eq!(details.operation.as_deref(), Some("connect"));
        assert!(details.recoverable);
        assert!(details.message.contains("429"));
    }

    /// The keys are the wire contract with every binding above, so a rename here
    /// is a break there.
    #[test]
    fn details_serialise_under_the_names_the_bindings_read() {
        let json = serde_json::to_value(CoreError::Aborted.details(None)).unwrap();
        assert_eq!(json["code"], codes::ABORTED);
        assert_eq!(json["component"], "api");
        assert_eq!(json["recoverable"], false);
        // Absent rather than null, so a binding can tell "no status" from "status
        // was reported as nothing".
        assert!(json.get("status").is_none());
        assert!(json.get("operation").is_none());
    }
}
