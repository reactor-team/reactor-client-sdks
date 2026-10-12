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
    ClientInfo, CreateSessionRequest, GenerationMode, ModelConfig, SessionResponse,
    TransportDeclaration, GENERATION_KEY,
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
    /// Generation mode to request. [`GenerationMode::Streamed`] sends nothing,
    /// exactly as before the mode existed; [`GenerationMode::Stepped`] asks for
    /// it under [`GENERATION_KEY`].
    pub generation: GenerationMode,
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
            extra_configs: self.extra_configs(),
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

    /// The platform's `extra_configs` for this session: the requested
    /// generation mode, when it is not the default. The coordinator forwards
    /// `extra_configs` to the runtime as a string map, so the value is the
    /// mode's wire name.
    fn extra_configs(&self) -> Option<Value> {
        match self.config.generation {
            GenerationMode::Streamed => None,
            mode => Some(serde_json::json!({ GENERATION_KEY: mode.as_str() })),
        }
    }

    async fn local_start_session(&self) -> Result<SessionResponse, CoreError> {
        let mut body = serde_json::Map::new();
        if let Some(extra_args) = &self.config.extra_args {
            body.insert("extra_args".to_string(), extra_args.clone());
        }
        if self.config.generation != GenerationMode::Streamed {
            body.insert(
                GENERATION_KEY.to_string(),
                Value::String(self.config.generation.as_str().to_string()),
            );
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
                headers: self.headers(false).await?,
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

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use super::*;
    use crate::http::{AuthProvider, HttpClient, HttpResponse};
    use crate::runtime::Platform;
    use crate::BoxFut;

    struct NoAuth;

    #[async_trait::async_trait]
    impl AuthProvider for NoAuth {
        async fn jwt(&self) -> Result<Option<String>, CoreError> {
            Ok(None)
        }
    }

    struct TestPlatform;

    impl Platform for TestPlatform {
        fn sleep(&self, d: std::time::Duration) -> BoxFut<'static, ()> {
            Box::pin(tokio::time::sleep(d))
        }
        fn now_ms(&self) -> f64 {
            0.0
        }
    }

    /// Answers every request with a minimal session and keeps each body.
    #[derive(Default)]
    struct BodyHttp {
        bodies: Mutex<Vec<(String, Value)>>,
    }

    #[async_trait::async_trait]
    impl HttpClient for BodyHttp {
        async fn request(&self, req: HttpRequest) -> Result<HttpResponse, CoreError> {
            let body = req
                .body
                .as_deref()
                .map(|b| serde_json::from_slice(b).unwrap())
                .unwrap_or(Value::Null);
            self.bodies.lock().unwrap().push((req.url, body));
            Ok(HttpResponse {
                status: 200,
                headers: vec![],
                body: br#"{"session_id": "sess_1", "state": "CREATED"}"#.to_vec(),
            })
        }
    }

    fn client(http: Arc<BodyHttp>, local: bool, generation: GenerationMode) -> CoordinatorClient {
        CoordinatorClient::new(
            http,
            Arc::new(NoAuth),
            Arc::new(TestPlatform),
            CoordinatorConfig {
                api_url: "http://localhost".into(),
                model: ModelConfig {
                    name: "m".into(),
                    version: None,
                },
                client_info: ClientInfo {
                    sdk_version: "0".into(),
                    sdk_type: "rust".into(),
                },
                extra_args: Some(serde_json::json!({"seed": 1})),
                generation,
                poll: PollConfig::session(),
                local,
            },
        )
    }

    fn only_body(http: &BodyHttp) -> (String, Value) {
        let bodies = http.bodies.lock().unwrap();
        assert_eq!(bodies.len(), 1);
        bodies[0].clone()
    }

    #[tokio::test]
    async fn a_stepped_session_asks_the_platform_in_extra_configs() {
        let http = Arc::new(BodyHttp::default());
        client(http.clone(), false, GenerationMode::Stepped)
            .create_session()
            .await
            .unwrap();
        let (url, body) = only_body(&http);
        assert!(url.ends_with("/sessions"));
        assert_eq!(
            body["extra_configs"],
            serde_json::json!({"generation": "stepped"})
        );
        assert_eq!(body["extra_args"], serde_json::json!({"seed": 1}));
    }

    #[tokio::test]
    async fn a_streamed_session_sends_no_extra_configs() {
        let http = Arc::new(BodyHttp::default());
        client(http.clone(), false, GenerationMode::Streamed)
            .create_session()
            .await
            .unwrap();
        let (_, body) = only_body(&http);
        assert!(body.get("extra_configs").is_none());
    }

    #[tokio::test]
    async fn a_stepped_local_session_names_the_mode_at_the_top_of_the_body() {
        let http = Arc::new(BodyHttp::default());
        client(http.clone(), true, GenerationMode::Stepped)
            .create_session()
            .await
            .unwrap();
        let (url, body) = only_body(&http);
        assert!(url.ends_with("/start_session"));
        assert_eq!(
            body,
            serde_json::json!({"extra_args": {"seed": 1}, "generation": "stepped"})
        );
    }

    #[tokio::test]
    async fn a_streamed_local_session_body_is_unchanged() {
        let http = Arc::new(BodyHttp::default());
        client(http.clone(), true, GenerationMode::Streamed)
            .create_session()
            .await
            .unwrap();
        let (_, body) = only_body(&http);
        assert_eq!(body, serde_json::json!({"extra_args": {"seed": 1}}));
    }
}
