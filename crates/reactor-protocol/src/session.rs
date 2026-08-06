//! Coordinator session lifecycle types (`POST /sessions`, `GET /sessions/{id}`).

use serde::{Deserialize, Serialize};
use serde_json::Value;

/// Server-side session state, as reported by `GET /sessions/{id}`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SessionState {
    Created,
    Pending,
    Suspended,
    Waiting,
    Active,
    Inactive,
    Closed,
    /// Forward-compatibility: states added by newer servers.
    #[serde(other)]
    Unknown,
}

impl SessionState {
    /// Terminal states: the session can never become usable again.
    pub fn is_terminal(&self) -> bool {
        matches!(self, SessionState::Inactive | SessionState::Closed)
    }
}

/// Identifies the SDK making a request (sent in session creation and all
/// signaling requests).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ClientInfo {
    pub sdk_version: String,
    pub sdk_type: String,
}

/// A transport protocol the client supports / the server selected.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct TransportDeclaration {
    pub protocol: String,
    pub version: String,
}

impl TransportDeclaration {
    pub fn webrtc() -> Self {
        Self {
            protocol: "webrtc".to_string(),
            version: crate::REACTOR_WEBRTC_VERSION.to_string(),
        }
    }
}

/// Model selection in a session-creation request and echo in responses.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ModelConfig {
    pub name: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub version: Option<String>,
}

/// Media kind of a track.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum TrackKind {
    Audio,
    Video,
}

/// Direction of a track from the client's perspective.
/// `Recvonly` tracks are model output; `Sendonly` tracks are client input.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum TrackDirection {
    Recvonly,
    Sendonly,
}

impl TrackDirection {
    pub fn as_str(&self) -> &'static str {
        match self {
            TrackDirection::Recvonly => "recvonly",
            TrackDirection::Sendonly => "sendonly",
        }
    }
}

/// A track the runtime exposes, advertised in [`Capabilities`].
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct TrackCapability {
    pub name: String,
    pub kind: TrackKind,
    pub direction: TrackDirection,
}

/// A command the model accepts, advertised in [`Capabilities`].
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct CommandCapability {
    pub name: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub description: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub schema: Option<Value>,
}

/// Runtime capabilities, populated on the session once the runtime accepted it.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Capabilities {
    pub protocol_version: String,
    pub tracks: Vec<TrackCapability>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub commands: Option<Vec<CommandCapability>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub emission_fps: Option<f64>,
}

/// Server version info echoed on session creation.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ServerInfo {
    pub server_version: String,
}

/// `POST /sessions` request body.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct CreateSessionRequest {
    pub model: ModelConfig,
    pub client_info: ClientInfo,
    pub supported_transports: Vec<TransportDeclaration>,
    /// Free-form model arguments (JS SDK wire name).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub extra_args: Option<Value>,
    /// Free-form model arguments (Python SDK wire name, kept for compatibility).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub extra_configs: Option<Value>,
}

/// Session resource returned by `POST /sessions` (slim) and
/// `GET /sessions/{id}` (progressively populated while the runtime spins up).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SessionResponse {
    pub session_id: String,
    pub state: SessionState,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub model: Option<ModelConfig>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cluster: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub server_info: Option<ServerInfo>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub selected_transport: Option<TransportDeclaration>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub capabilities: Option<Capabilities>,
    /// Unknown / newer fields are preserved rather than rejected.
    #[serde(flatten)]
    pub extra: serde_json::Map<String, Value>,
}

impl SessionResponse {
    /// A session is connectable once the runtime accepted it: a transport was
    /// selected and capabilities are published.
    pub fn is_ready(&self) -> bool {
        self.capabilities.is_some() && self.selected_transport.is_some()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn session_response_round_trip() {
        let json = r#"{
            "session_id": "sess_abc",
            "state": "ACTIVE",
            "cluster": "us-west",
            "selected_transport": {"protocol": "webrtc", "version": "1.0"},
            "capabilities": {
                "protocol_version": "1.0",
                "tracks": [
                    {"name": "main_video", "kind": "video", "direction": "recvonly"},
                    {"name": "input_audio", "kind": "audio", "direction": "sendonly"}
                ],
                "commands": [{"name": "set_prompt", "description": "Set the prompt"}],
                "emission_fps": 30.0
            },
            "some_future_field": {"x": 1}
        }"#;
        let resp: SessionResponse = serde_json::from_str(json).unwrap();
        assert_eq!(resp.state, SessionState::Active);
        assert!(resp.is_ready());
        let caps = resp.capabilities.as_ref().unwrap();
        assert_eq!(caps.tracks[0].direction, TrackDirection::Recvonly);
        assert_eq!(caps.tracks[1].kind, TrackKind::Audio);
        assert!(resp.extra.contains_key("some_future_field"));

        let back = serde_json::to_value(&resp).unwrap();
        assert_eq!(back["some_future_field"]["x"], 1);
    }

    #[test]
    fn terminal_states() {
        assert!(SessionState::Closed.is_terminal());
        assert!(SessionState::Inactive.is_terminal());
        assert!(!SessionState::Active.is_terminal());
        let s: SessionState = serde_json::from_str("\"SOME_NEW_STATE\"").unwrap();
        assert_eq!(s, SessionState::Unknown);
    }

    #[test]
    fn create_session_request_shape() {
        let req = CreateSessionRequest {
            model: ModelConfig {
                name: "my-model".into(),
                version: None,
            },
            client_info: ClientInfo {
                sdk_version: "0.1.0".into(),
                sdk_type: "rust".into(),
            },
            supported_transports: vec![TransportDeclaration::webrtc()],
            extra_args: None,
            extra_configs: None,
        };
        let v = serde_json::to_value(&req).unwrap();
        assert_eq!(v["model"]["name"], "my-model");
        assert_eq!(v["supported_transports"][0]["protocol"], "webrtc");
        assert!(v.get("extra_args").is_none());
    }
}
