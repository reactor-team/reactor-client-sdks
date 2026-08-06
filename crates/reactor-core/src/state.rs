//! Client-visible status enums.

use serde::{Deserialize, Serialize};

/// Top-level client state machine.
///
/// ```text
/// Disconnected --connect()--> Connecting --session created--> Waiting
///     ^                                                          |
///     |                         transport ready (peer connected  |
///     |                         + data & control channels open)  v
///     +---- disconnect() / fatal transport error ------------- Ready
/// ```
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum ReactorStatus {
    #[default]
    Disconnected,
    /// Session is being created / adopted.
    Connecting,
    /// Session exists; waiting for the runtime and negotiating transport.
    Waiting,
    /// Transport is up; commands can be sent and tracks flow.
    Ready,
}

impl ReactorStatus {
    pub fn as_str(&self) -> &'static str {
        match self {
            ReactorStatus::Disconnected => "disconnected",
            ReactorStatus::Connecting => "connecting",
            ReactorStatus::Waiting => "waiting",
            ReactorStatus::Ready => "ready",
        }
    }
}

impl std::fmt::Display for ReactorStatus {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.as_str())
    }
}
