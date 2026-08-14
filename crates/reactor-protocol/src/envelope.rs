//! Routing scope of a data-channel command.

use serde::{Deserialize, Serialize};

/// Which logical audience a `send_command` call targets.
///
/// `Application` commands are model-defined traffic on the data channel
/// (`reactor_wire.v1` `Command`/`ModelMessage`). `Runtime` commands are
/// platform-level control (ping, moderation, recording, track control) —
/// each one has its own typed `reactor_wire.v1` control-channel message; the
/// FFI's generic runtime-command entry point is otherwise unsupported (see
/// [`crate::wire::v1::control`]).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum MessageScope {
    Application,
    Runtime,
}

impl MessageScope {
    pub fn as_str(&self) -> &'static str {
        match self {
            MessageScope::Application => "application",
            MessageScope::Runtime => "runtime",
        }
    }
}
