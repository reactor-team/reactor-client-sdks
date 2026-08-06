//! HTTP-based WebRTC signaling types
//! (`/sessions/{id}/transport/webrtc/...` endpoints).
//!
//! Flow:
//! 1. `GET  {base}/ice_servers`                       → [`IceServersResponse`]
//! 2. `POST {base}/connections`                       → [`RegisterConnectionResponse`]
//! 3. `POST {base}/connections/{cid}/sdp_params`      ← [`SdpOfferRequest`] (PUT on reconnect)
//! 4. `GET  {base}/connections/{cid}/sdp_params`      → 202 (retry) | 200 [`SdpAnswerResponse`]
//! 5. `POST {base}/connections/{cid}/ice_candidates`  ← [`IceCandidatesRequest`] (trickle)

use serde::{Deserialize, Serialize};

use crate::session::{ClientInfo, TrackDirection, TrackKind};

/// TURN credentials.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct IceCredentials {
    pub username: String,
    pub password: String,
}

/// A STUN/TURN server entry.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct IceServer {
    pub uris: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub credentials: Option<IceCredentials>,
}

/// `GET {base}/ice_servers` response.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct IceServersResponse {
    pub ice_servers: Vec<IceServer>,
}

/// `POST {base}/connections` response.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct RegisterConnectionResponse {
    pub connection_id: u32,
}

/// Maps an SDP media section (`mid`) to a named Reactor track.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct TrackMappingEntry {
    pub name: String,
    pub kind: TrackKind,
    pub direction: TrackDirection,
    pub mid: String,
}

/// `POST/PUT {base}/connections/{cid}/sdp_params` request body.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SdpOfferRequest {
    pub sdp_offer: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub client_info: Option<ClientInfo>,
    pub track_mapping: Vec<TrackMappingEntry>,
}

/// `GET {base}/connections/{cid}/sdp_params` 200 response body.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SdpAnswerResponse {
    pub sdp_answer: String,
    /// Present on multi-connection runtimes when the server reassigns the id.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub connection_id: Option<u32>,
}

/// A single trickled ICE candidate.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct IceCandidate {
    pub candidate: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub sdp_mid: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub sdp_mline_index: Option<u16>,
}

/// `POST {base}/connections/{cid}/ice_candidates` request body.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct IceCandidatesRequest {
    pub candidates: Vec<IceCandidate>,
    /// True once ICE gathering completed; no further batches will follow.
    pub is_final: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub client_info: Option<ClientInfo>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ice_servers_parse() {
        let json = r#"{
            "ice_servers": [
                {"uris": ["stun:stun.example.com:19302"]},
                {"uris": ["turn:turn.example.com:3478"], "credentials": {"username": "u", "password": "p"}}
            ]
        }"#;
        let resp: IceServersResponse = serde_json::from_str(json).unwrap();
        assert_eq!(resp.ice_servers.len(), 2);
        assert!(resp.ice_servers[0].credentials.is_none());
        assert_eq!(
            resp.ice_servers[1].credentials.as_ref().unwrap().username,
            "u"
        );
    }

    #[test]
    fn sdp_offer_wire_shape() {
        let req = SdpOfferRequest {
            sdp_offer: "v=0...".into(),
            client_info: Some(ClientInfo {
                sdk_version: "0.1.0".into(),
                sdk_type: "rust".into(),
            }),
            track_mapping: vec![TrackMappingEntry {
                name: "main_video".into(),
                kind: TrackKind::Video,
                direction: TrackDirection::Recvonly,
                mid: "0".into(),
            }],
        };
        let v = serde_json::to_value(&req).unwrap();
        assert_eq!(v["track_mapping"][0]["mid"], "0");
        assert_eq!(v["track_mapping"][0]["direction"], "recvonly");
        assert_eq!(v["client_info"]["sdk_type"], "rust");
    }
}
