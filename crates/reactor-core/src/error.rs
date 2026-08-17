//! Error model.
//!
//! One list of codes, in [`codes`], and two ways to receive one:
//!
//! * [`CoreError`] — the `Result` error type of core operations. Every variant
//!   maps to a code through [`CoreError::code`].
//! * [`ReactorError`] — the record emitted through the event stream and kept as
//!   "last error" on the client.
//!
//! Both report the *same* code for the same failure. They used to disagree: the
//! event's code was chosen by whichever call site emitted it, so a 401 during
//! `connect()` was `CONNECTION_FAILED` and recoverable in one channel and
//! `UNAUTHORIZED` and not recoverable in the other. What the call site knew that
//! the error did not — which call failed — is the `operation` field instead.

use serde::{Deserialize, Serialize};

/// Every error code the SDKs report. One list, no duplicates.
///
/// Each is a distinction a caller can act on. Which tier of the platform a
/// failure came from is deliberately *not* one of them: it is an implementation
/// detail of ours, it does not change what anyone should do about the failure,
/// and splitting the vocabulary by it produced two names for one thing.
///
/// Codes are not exhaustive. A control request, a command or a recording the
/// platform rejects reports the platform's own code, so consumers must treat an
/// unrecognised code as an error they cannot classify rather than as a bug.
pub mod codes {
    /// The call is not allowed from the state the client is in — most often one
    /// needing a live session, made before `connect()` or after `ready` was lost.
    pub const INVALID_STATE: &str = "INVALID_STATE";
    /// The connection went away: dropped while a request was in flight, or lost
    /// after it had been established.
    pub const DISCONNECTED: &str = "DISCONNECTED";
    /// The request never got a reply — DNS, TLS, a refused socket.
    pub const NETWORK_ERROR: &str = "NETWORK_ERROR";
    /// Sent, and nothing came back in time.
    pub const REQUEST_TIMEOUT: &str = "REQUEST_TIMEOUT";
    /// The media transport failed.
    pub const TRANSPORT_ERROR: &str = "TRANSPORT_ERROR";
    /// The token is missing, expired, or not scoped for this (HTTP 401 / 403).
    pub const UNAUTHORIZED: &str = "UNAUTHORIZED";
    /// No such model, session or upload (HTTP 404).
    pub const NOT_FOUND: &str = "NOT_FOUND";
    /// The session is in a state that does not allow this (HTTP 409) — usually
    /// one left orphaned by a run that went away without disconnecting.
    pub const CONFLICT: &str = "CONFLICT";
    /// Too many requests (HTTP 429).
    pub const RATE_LIMITED: &str = "RATE_LIMITED";
    /// The request itself was wrong — a 4xx other than the above, or an argument
    /// rejected here before it was sent.
    pub const BAD_REQUEST: &str = "BAD_REQUEST";
    /// The platform failed, and the same request may work later (HTTP 5xx).
    pub const SERVER_ERROR: &str = "SERVER_ERROR";
    /// This client and the platform disagree on the protocol (HTTP 426 / 501).
    pub const VERSION_MISMATCH: &str = "VERSION_MISMATCH";
    /// A reply arrived and could not be understood.
    pub const DECODE_FAILED: &str = "DECODE_FAILED";
    /// The session reached a state it cannot leave. Start a new one.
    pub const SESSION_TERMINAL: &str = "SESSION_TERMINAL";
    /// The payload exceeds what the data channel accepts.
    pub const MESSAGE_TOO_LARGE: &str = "MESSAGE_TOO_LARGE";
    /// The operation was abandoned before it finished.
    pub const ABORTED: &str = "ABORTED";
    /// A failure that fits none of the above.
    pub const INTERNAL_ERROR: &str = "INTERNAL_ERROR";
}

/// A failure, in the terms a caller can act on.
///
/// This is what [`CoreError`] flattens down to for a binding — a `Display` string
/// is enough to log and not enough to branch on, and every SDK above this was
/// reduced to matching on message text or giving up.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ErrorDetails {
    /// One of [`codes`], or a code the platform sent — which is why this is a
    /// `String` and consumers must tolerate unknown values.
    pub code: String,
    pub message: String,
    /// Whether the same call could succeed later, after a `reconnect()` or a
    /// wait. True is about the moment — a timeout, a 5xx, a transport that
    /// dropped. False is about the request, and repeating it will fail the same.
    pub recoverable: bool,
    /// The HTTP status, when the failure came from one.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub status: Option<u16>,
    /// Which call failed, e.g. `"connect"`, `"send_command"`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub operation: Option<String>,
    /// Backoff hint in milliseconds, when the platform provided one.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub retry_after_ms: Option<f64>,
}

impl ErrorDetails {
    /// A failure that did not come from a [`CoreError`] — a transport that
    /// dropped on its own, or a code the platform sent unprompted.
    pub fn new(code: impl Into<String>, message: impl Into<String>, recoverable: bool) -> Self {
        Self {
            code: code.into(),
            message: message.into(),
            recoverable,
            status: None,
            operation: None,
            retry_after_ms: None,
        }
    }
}

/// The error record on the event stream, and what `last_error` returns.
///
/// The same [`ErrorDetails`] a failed call reports, plus when it happened —
/// flattened on the wire, so consumers see one flat object either way.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ReactorError {
    #[serde(flatten)]
    pub details: ErrorDetails,
    /// Unix epoch milliseconds.
    pub timestamp_ms: f64,
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
        /// The server's `Retry-After`, in milliseconds, when it sent one this
        /// client could read. See `http::parse_retry_after_ms`.
        retry_after_ms: Option<f64>,
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
            CoreError::Peer(_) => codes::TRANSPORT_ERROR,
            CoreError::Aborted => codes::ABORTED,
        }
    }

    /// Whether the same call could succeed later.
    pub fn recoverable(&self) -> bool {
        match self {
            CoreError::Http(_) | CoreError::Timeout(_) | CoreError::Peer(_) => true,
            CoreError::Status { status, .. } => *status == 429 || *status >= 500,
            // A request the platform rejected because the connection went is
            // exactly the kind that passes once it is back.
            CoreError::ControlRequest { code, .. }
            | CoreError::CommandRequest { code, .. }
            | CoreError::Recording { code, .. } => code == codes::DISCONNECTED,
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

    /// How long the server asked us to wait before trying again.
    ///
    /// Only ever present on a status the server attached `Retry-After` to — a
    /// 429 or a 503, in practice. Absent is not "retry immediately": it means the
    /// server said nothing, and a caller that retries should back off on its own
    /// terms.
    pub fn retry_after_ms(&self) -> Option<f64> {
        match self {
            CoreError::Status { retry_after_ms, .. } => *retry_after_ms,
            _ => None,
        }
    }

    /// Everything above, in the shape a binding hands to its caller.
    pub fn details(&self, operation: Option<&str>) -> ErrorDetails {
        ErrorDetails {
            code: self.code().to_string(),
            message: self.to_string(),
            recoverable: self.recoverable(),
            status: self.status(),
            operation: operation.map(str::to_string),
            retry_after_ms: self.retry_after_ms(),
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
            retry_after_ms: None,
        }
    }

    fn throttled(retry_after_ms: Option<f64>) -> CoreError {
        CoreError::Status {
            status: 429,
            context: "POST /sessions".into(),
            body: String::new(),
            retry_after_ms,
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

    /// A request that failed because the connection went is the archetype of one
    /// worth retrying — and it arrives wearing a platform code, not a variant.
    #[test]
    fn a_request_lost_to_a_disconnect_is_recoverable() {
        let error = CoreError::ControlRequest {
            method: "request".into(),
            code: codes::DISCONNECTED.into(),
            message: "connection closed".into(),
        };
        assert!(error.recoverable());
    }

    /// The contradiction this replaced: `connect()` failing on a 401 raised
    /// UNAUTHORIZED/not-recoverable to the caller while the event said
    /// CONNECTION_FAILED/recoverable — so anything listening to the event
    /// reconnected in a loop against a token that would never work.
    #[test]
    fn the_event_and_the_failed_call_report_the_same_thing() {
        let error = status(401);
        let details = error.details(Some("connect"));
        let event = ReactorError {
            details: error.details(Some("connect")),
            timestamp_ms: 0.0,
        };

        assert_eq!(event.details.code, details.code);
        assert_eq!(event.details.recoverable, details.recoverable);
        assert_eq!(event.details.code, codes::UNAUTHORIZED);
        assert!(!event.details.recoverable);
    }

    /// The hint is the difference between backing off for as long as the server
    /// asked and guessing — and a caller can only honour it if it survives the
    /// trip out to them.
    #[test]
    fn a_backoff_hint_reaches_the_caller() {
        let details = throttled(Some(2_000.0)).details(Some("connect"));
        assert_eq!(details.code, codes::RATE_LIMITED);
        assert_eq!(details.retry_after_ms, Some(2_000.0));

        // Absent means the server said nothing, not "retry now".
        assert_eq!(throttled(None).details(None).retry_after_ms, None);
        assert_eq!(CoreError::Aborted.details(None).retry_after_ms, None);
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
        assert_eq!(json["recoverable"], false);
        // Absent rather than null, so a binding can tell "no status" from "status
        // was reported as nothing".
        assert!(json.get("status").is_none());
        assert!(json.get("operation").is_none());
        assert!(json.get("retry_after_ms").is_none());
        // Gone on purpose: which tier failed is our implementation detail, it
        // changes nothing a caller would do, and having it split the vocabulary
        // is what produced two names for one failure.
        assert!(json.get("component").is_none());
    }

    /// Flattened, so a consumer of the event sees one flat object rather than a
    /// nested `details` — the shape it has always had, plus the new fields.
    #[test]
    fn the_event_serialises_flat() {
        let event = ReactorError {
            details: status(409).details(Some("connect")),
            timestamp_ms: 1234.0,
        };
        let json = serde_json::to_value(&event).unwrap();
        assert_eq!(json["code"], codes::CONFLICT);
        assert_eq!(json["timestamp_ms"], 1234.0);
        assert_eq!(json["operation"], "connect");
        assert!(json.get("details").is_none());
    }
}
