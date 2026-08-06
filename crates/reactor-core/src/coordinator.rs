//! Coordinator HTTP client: session lifecycle and uploads.
//!
//! Cloud coordinator endpoints:
//! * `POST   /sessions`              — create a session
//! * `GET    /sessions/{id}`         — poll until the runtime accepted it
//! * `DELETE /sessions/{id}`         — terminate (creator only)
//! * `POST   /sessions/{id}/uploads` — create a presigned upload
//!
//! Local HTTP runtime endpoints (when `CoordinatorConfig::local = true`):
//! * `POST   /start_session`         — start session (returns full capabilities immediately)
//! * `GET    /session`               — read session descriptor (no id)
//! * `POST   /stop_session`          — stop session

use std::sync::Mutex;

use serde_json::Value;

use crate::backoff::PollConfig;
use crate::error::CoreError;
use crate::http::{check_status, HttpRequest, Method};
use crate::protocol::session::{
    ClientInfo, CreateSessionRequest, ModelConfig, SessionResponse, TransportDeclaration,
};
use crate::protocol::upload::{CreateUploadRequest, CreateUploadResponse};
use crate::protocol::{API_ACCEPT_VERSION_HEADER, API_VERSION_HEADER, REACTOR_API_VERSION};
use crate::{SharedAuth, SharedHttp, SharedPlatform};

/// Configuration of a [`CoordinatorClient`].
#[derive(Debug, Clone)]
pub struct CoordinatorConfig {
    /// Coordinator base URL, e.g. `https://api.reactor.inc` (trailing slash ok).
    pub api_url: String,
    pub model: ModelConfig,
    pub client_info: ClientInfo,
    /// Free-form model arguments forwarded on session creation.
    pub extra_args: Option<Value>,
    pub poll: PollConfig,
    /// When true, use the local HTTP runtime API (`/start_session`, `/session`,
    /// `/stop_session`) instead of the cloud coordinator API.
    pub local: bool,
}

/// Stateless-per-session coordinator API client.
pub struct CoordinatorClient {
    http: SharedHttp,
    auth: SharedAuth,
    platform: SharedPlatform,
    config: CoordinatorConfig,
    local_session: Mutex<Option<SessionResponse>>,
}

impl CoordinatorClient {
    pub fn new(
        http: SharedHttp,
        auth: SharedAuth,
        platform: SharedPlatform,
        mut config: CoordinatorConfig,
    ) -> Self {
        config.api_url = config.api_url.trim_end_matches('/').to_string();
        Self {
            http,
            auth,
            platform,
            config,
            local_session: Mutex::new(None),
        }
    }

    pub fn api_url(&self) -> &str {
        &self.config.api_url
    }

    pub fn client_info(&self) -> &ClientInfo {
        &self.config.client_info
    }

    pub fn transport_base_url(&self, session_id: &str) -> String {
        format!(
            "{}/sessions/{session_id}/transport/webrtc",
            self.config.api_url
        )
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
        ];
        if json_body {
            headers.push(("Content-Type".to_string(), "application/json".to_string()));
        }
        if let Some(jwt) = self.auth.jwt().await? {
            headers.push(("Authorization".to_string(), format!("Bearer {jwt}")));
        }
        Ok(headers)
    }

    fn local_headers(&self, json_body: bool) -> Vec<(String, String)> {
        let mut headers = vec![
            (
                API_VERSION_HEADER.to_string(),
                REACTOR_API_VERSION.to_string(),
            ),
            (
                API_ACCEPT_VERSION_HEADER.to_string(),
                REACTOR_API_VERSION.to_string(),
            ),
        ];
        if json_body {
            headers.push(("Content-Type".to_string(), "application/json".to_string()));
        }
        headers
    }

    pub async fn create_session(&self) -> Result<SessionResponse, CoreError> {
        if self.config.local {
            return self.local_start_session().await;
        }
        let body = CreateSessionRequest {
            model: self.config.model.clone(),
            client_info: self.config.client_info.clone(),
            supported_transports: vec![TransportDeclaration::webrtc()],
            extra_args: self.config.extra_args.clone(),
            extra_configs: None,
        };
        let response = self
            .http
            .request(HttpRequest {
                method: Method::Post,
                url: format!("{}/sessions", self.config.api_url),
                headers: self.headers(true).await?,
                body: Some(serde_json::to_vec(&body).map_err(CoreError::decode)?),
            })
            .await?;
        check_status(&response, "create session")?;
        response.json()
    }

    async fn local_start_session(&self) -> Result<SessionResponse, CoreError> {
        let mut body = serde_json::Map::new();
        if let Some(extra_args) = &self.config.extra_args {
            body.insert("extra_args".to_string(), extra_args.clone());
        }
        let response = self
            .http
            .request(HttpRequest {
                method: Method::Post,
                url: format!("{}/start_session", self.config.api_url),
                headers: self.local_headers(true),
                body: Some(serde_json::to_vec(&Value::Object(body)).map_err(CoreError::decode)?),
            })
            .await?;
        check_status(&response, "start session")?;
        let session: SessionResponse = response.json()?;
        *self.local_session.lock().unwrap() = Some(session.clone());
        Ok(session)
    }

    pub async fn get_session(&self, session_id: &str) -> Result<SessionResponse, CoreError> {
        let response = self
            .http
            .request(HttpRequest {
                method: Method::Get,
                url: format!("{}/sessions/{session_id}", self.config.api_url),
                headers: self.headers(false).await?,
                body: None,
            })
            .await?;
        check_status(&response, "get session")?;
        response.json()
    }

    pub async fn poll_session_ready(&self, session_id: &str) -> Result<SessionResponse, CoreError> {
        if self.config.local {
            return self.local_session.lock().unwrap().clone().ok_or_else(|| {
                CoreError::InvalidState(
                    "no cached local session — call create_session first".into(),
                )
            });
        }
        let mut backoff = self.config.poll.backoff();
        for attempt in 1..=self.config.poll.max_attempts {
            let session = self.get_session(session_id).await?;
            if session.state.is_terminal() {
                return Err(CoreError::TerminalSession(format!("{:?}", session.state)));
            }
            if session.is_ready() {
                log::debug!("session {session_id} ready after {attempt} poll(s)");
                return Ok(session);
            }
            self.platform.sleep(backoff.next_delay()).await;
        }
        Err(CoreError::Timeout(format!(
            "session {session_id} not ready after {} polls",
            self.config.poll.max_attempts
        )))
    }

    pub async fn terminate_session(&self, session_id: &str) -> Result<(), CoreError> {
        if self.config.local {
            let _ = self
                .http
                .request(HttpRequest {
                    method: Method::Post,
                    url: format!("{}/stop_session", self.config.api_url),
                    headers: self.local_headers(false),
                    body: None,
                })
                .await;
            *self.local_session.lock().unwrap() = None;
            return Ok(());
        }
        let response = self
            .http
            .request(HttpRequest {
                method: Method::Delete,
                url: format!("{}/sessions/{session_id}", self.config.api_url),
                headers: self.headers(false).await?,
                body: None,
            })
            .await?;
        if response.status == 404 {
            return Ok(());
        }
        check_status(&response, "terminate session")
    }

    pub async fn create_upload(
        &self,
        session_id: &str,
        request: &CreateUploadRequest,
    ) -> Result<CreateUploadResponse, CoreError> {
        let response = self
            .http
            .request(HttpRequest {
                method: Method::Post,
                url: format!("{}/sessions/{session_id}/uploads", self.config.api_url),
                headers: self.headers(true).await?,
                body: Some(serde_json::to_vec(request).map_err(CoreError::decode)?),
            })
            .await?;
        check_status(&response, "create upload")?;
        response.json()
    }
}
