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
use crate::http::{check_status, AuthRequest, HttpRequest, Method};
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

    async fn headers(
        &self,
        json_body: bool,
        session_id: Option<&str>,
    ) -> Result<Vec<(String, String)>, CoreError> {
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
        let request = AuthRequest {
            session_id: session_id.map(str::to_string),
        };
        if let Some(jwt) = self.auth.jwt(&request).await? {
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
                headers: self.headers(true, None).await?,
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

    /// Read the session a local runtime is serving, to join one this client did not
    /// create.
    ///
    /// `GET /session` takes no id because a local runtime holds exactly one. The
    /// requested id is still checked against what comes back: adopting a different
    /// session than the caller asked for would be worse than refusing, and the id is
    /// how they said which.
    async fn local_get_session(&self, session_id: &str) -> Result<SessionResponse, CoreError> {
        let response = self
            .http
            .request(HttpRequest {
                method: Method::Get,
                url: format!("{}/session", self.config.api_url),
                headers: self.local_headers(false),
                body: None,
            })
            .await?;
        check_status(&response, "get local session")?;
        let session: SessionResponse = response.json()?;

        if session.session_id != session_id {
            return Err(CoreError::InvalidState(format!(
                "local runtime is serving session {}, not the requested {session_id}",
                session.session_id
            )));
        }
        if session.state.is_terminal() {
            return Err(CoreError::TerminalSession(format!("{:?}", session.state)));
        }

        *self.local_session.lock().unwrap() = Some(session.clone());
        Ok(session)
    }

    pub async fn get_session(&self, session_id: &str) -> Result<SessionResponse, CoreError> {
        let response = self
            .http
            .request(HttpRequest {
                method: Method::Get,
                url: format!("{}/sessions/{session_id}", self.config.api_url),
                headers: self.headers(false, Some(session_id)).await?,
                body: None,
            })
            .await?;
        check_status(&response, "get session")?;
        response.json()
    }

    pub async fn poll_session_ready(&self, session_id: &str) -> Result<SessionResponse, CoreError> {
        if self.config.local {
            // The session this client started, when it started one.
            if let Some(session) = self.local_session.lock().unwrap().clone() {
                return Ok(session);
            }
            // Otherwise adopt the one the runtime is already serving. A local runtime
            // holds a single session and `GET /session` describes it, which is how a
            // second process joins one it did not create — previously this returned
            // "no cached local session" and made joining impossible outside the
            // creating process.
            return self.local_get_session(session_id).await;
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
                headers: self.headers(false, Some(session_id)).await?,
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
                headers: self.headers(true, Some(session_id)).await?,
                body: Some(serde_json::to_vec(request).map_err(CoreError::decode)?),
            })
            .await?;
        check_status(&response, "create upload")?;
        response.json()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::http::testing::{NoSleep, NotFoundHttp, RecordingAuth};
    use std::sync::Arc;

    fn client(auth: Arc<RecordingAuth>) -> CoordinatorClient {
        CoordinatorClient::new(
            Arc::new(NotFoundHttp),
            auth,
            Arc::new(NoSleep),
            CoordinatorConfig {
                api_url: "https://api.reactor.inc".into(),
                model: ModelConfig {
                    name: "reactor/helios".into(),
                    version: None,
                },
                client_info: ClientInfo {
                    sdk_version: "0.0.0".into(),
                    sdk_type: "test".into(),
                },
                extra_args: None,
                poll: PollConfig::session(),
                local: false,
            },
        )
    }

    /// The host's resolver can only bind a replacement token to the right
    /// session if every call names the session it is for, and creation
    /// names none.
    #[tokio::test]
    async fn each_call_tells_the_auth_provider_which_session_it_is_for() {
        let auth = Arc::new(RecordingAuth::default());
        let client = client(auth.clone());

        let _ = client.create_session().await;
        client.terminate_session("sid-1").await.unwrap();
        let _ = client.get_session("sid-2").await;
        let upload = CreateUploadRequest {
            name: "frame.png".into(),
            size: 1,
            mime_type: "image/png".into(),
        };
        let _ = client.create_upload("sid-3", &upload).await;

        let requests = auth.requests.lock().unwrap().clone();
        assert_eq!(
            requests,
            vec![
                AuthRequest::default(),
                AuthRequest::for_session("sid-1"),
                AuthRequest::for_session("sid-2"),
                AuthRequest::for_session("sid-3"),
            ]
        );
    }
}
