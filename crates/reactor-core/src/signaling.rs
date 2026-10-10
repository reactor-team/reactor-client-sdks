//! HTTP-based WebRTC signaling client.
//!
//! Reactor signals over plain HTTP against
//! `/sessions/{id}/transport/webrtc/...` instead of a WebSocket.

use crate::backoff::PollConfig;
use crate::error::CoreError;
use crate::http::{check_status, AuthRequest, HttpRequest, Method};
use crate::protocol::session::ClientInfo;
use crate::protocol::webrtc::{
    IceCandidate, IceCandidatesRequest, IceServer, IceServersResponse, RegisterConnectionResponse,
    SdpAnswerResponse, SdpOfferRequest, TrackMappingEntry,
};
use crate::protocol::{
    API_ACCEPT_VERSION_HEADER, API_VERSION_HEADER, REACTOR_API_VERSION, REACTOR_WEBRTC_VERSION,
    WEBRTC_VERSION_HEADER,
};
use crate::{SharedAuth, SharedHttp, SharedPlatform};

/// Signaling client bound to one session's WebRTC transport base URL.
pub struct WebRtcSignaling {
    http: SharedHttp,
    auth: SharedAuth,
    platform: SharedPlatform,
    session_id: String,
    base_url: String,
    client_info: ClientInfo,
    poll: PollConfig,
}

impl WebRtcSignaling {
    pub fn new(
        http: SharedHttp,
        auth: SharedAuth,
        platform: SharedPlatform,
        session_id: String,
        base_url: String,
        client_info: ClientInfo,
        poll: PollConfig,
    ) -> Self {
        Self {
            http,
            auth,
            platform,
            session_id,
            base_url: base_url.trim_end_matches('/').to_string(),
            client_info,
            poll,
        }
    }

    async fn headers(&self, json_body: bool) -> Result<Vec<(String, String)>, CoreError> {
        let mut headers = vec![
            (
                API_VERSION_HEADER.to_string(),
                REACTOR_API_VERSION.to_string(),
            ),
            (
                API_ACCEPT_VERSION_HEADER.to_string(),
                REACTOR_API_VERSION.to_string(),
            ),
            (
                WEBRTC_VERSION_HEADER.to_string(),
                REACTOR_WEBRTC_VERSION.to_string(),
            ),
        ];
        if json_body {
            headers.push(("Content-Type".to_string(), "application/json".to_string()));
        }
        let request = AuthRequest::for_session(&self.session_id);
        if let Some(jwt) = self.auth.jwt(&request).await? {
            headers.push(("Authorization".to_string(), format!("Bearer {jwt}")));
        }
        Ok(headers)
    }

    pub async fn ice_servers(&self) -> Result<Vec<IceServer>, CoreError> {
        let response = self
            .http
            .request(HttpRequest {
                method: Method::Get,
                url: format!("{}/ice_servers", self.base_url),
                headers: self.headers(false).await?,
                body: None,
            })
            .await?;
        check_status(&response, "ice servers")?;
        Ok(response.json::<IceServersResponse>()?.ice_servers)
    }

    pub async fn register_connection(&self) -> Result<u32, CoreError> {
        let response = self
            .http
            .request(HttpRequest {
                method: Method::Post,
                url: format!("{}/connections", self.base_url),
                headers: self.headers(true).await?,
                body: Some(b"{}".to_vec()),
            })
            .await?;
        check_status(&response, "register connection")?;
        Ok(response.json::<RegisterConnectionResponse>()?.connection_id)
    }

    pub async fn send_sdp_offer(
        &self,
        connection_id: u32,
        sdp_offer: &str,
        track_mapping: &[TrackMappingEntry],
        reconnect: bool,
    ) -> Result<(), CoreError> {
        let body = SdpOfferRequest {
            sdp_offer: sdp_offer.to_string(),
            client_info: Some(self.client_info.clone()),
            track_mapping: track_mapping.to_vec(),
        };
        let response = self
            .http
            .request(HttpRequest {
                method: if reconnect { Method::Put } else { Method::Post },
                url: format!("{}/connections/{connection_id}/sdp_params", self.base_url),
                headers: self.headers(true).await?,
                body: Some(serde_json::to_vec(&body).map_err(CoreError::decode)?),
            })
            .await?;
        check_status(&response, "send sdp offer")
    }

    pub async fn poll_sdp_answer(
        &self,
        connection_id: u32,
    ) -> Result<SdpAnswerResponse, CoreError> {
        let url = format!("{}/connections/{connection_id}/sdp_params", self.base_url);
        let mut backoff = self.poll.backoff();
        for attempt in 1..=self.poll.max_attempts {
            let response = self
                .http
                .request(HttpRequest {
                    method: Method::Get,
                    url: url.clone(),
                    headers: self.headers(false).await?,
                    body: None,
                })
                .await?;
            if response.status == 202 {
                self.platform.sleep(backoff.next_delay()).await;
                continue;
            }
            check_status(&response, "poll sdp answer")?;
            log::debug!("sdp answer received after {attempt} poll(s)");
            return response.json();
        }
        Err(CoreError::Timeout(format!(
            "no SDP answer after {} polls",
            self.poll.max_attempts
        )))
    }

    pub async fn send_ice_candidates(
        &self,
        connection_id: u32,
        candidates: &[IceCandidate],
        is_final: bool,
    ) -> Result<(), CoreError> {
        let body = IceCandidatesRequest {
            candidates: candidates.to_vec(),
            is_final,
            client_info: Some(self.client_info.clone()),
        };
        let response = self
            .http
            .request(HttpRequest {
                method: Method::Post,
                url: format!(
                    "{}/connections/{connection_id}/ice_candidates",
                    self.base_url
                ),
                headers: self.headers(true).await?,
                body: Some(serde_json::to_vec(&body).map_err(CoreError::decode)?),
            })
            .await?;
        check_status(&response, "send ice candidates")
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::http::testing::{NoSleep, NotFoundHttp, RecordingAuth};
    use std::sync::Arc;

    /// Every signaling call is for the session the client was built for, so
    /// the host's resolver can keep answering with a token bound to it.
    #[tokio::test]
    async fn every_call_names_the_signaling_session() {
        let auth = Arc::new(RecordingAuth::default());
        let signaling = WebRtcSignaling::new(
            Arc::new(NotFoundHttp),
            auth.clone(),
            Arc::new(NoSleep),
            "sid-1".into(),
            "https://api.reactor.inc/sessions/sid-1/transport/webrtc".into(),
            ClientInfo {
                sdk_version: "0.0.0".into(),
                sdk_type: "test".into(),
            },
            PollConfig::session(),
        );

        let _ = signaling.ice_servers().await;
        let _ = signaling.register_connection().await;

        assert_eq!(
            auth.requests.lock().unwrap().clone(),
            vec![
                AuthRequest::for_session("sid-1"),
                AuthRequest::for_session("sid-1"),
            ]
        );
    }
}
