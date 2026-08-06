//! HTTP and auth abstractions.
//!
//! The host supplies an [`HttpClient`] (reqwest on native) and an
//! [`AuthProvider`] that yields short-lived JWTs per request, so the core
//! stays free of TLS stacks and token storage policy.

use serde::de::DeserializeOwned;

use crate::error::CoreError;

/// HTTP method subset used by the Reactor protocol.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Method {
    Get,
    Post,
    Put,
    Delete,
}

impl Method {
    pub fn as_str(&self) -> &'static str {
        match self {
            Method::Get => "GET",
            Method::Post => "POST",
            Method::Put => "PUT",
            Method::Delete => "DELETE",
        }
    }
}

/// A request the host should perform. Bodies are raw bytes so the same type
/// serves JSON calls and presigned binary uploads.
#[derive(Debug, Clone)]
pub struct HttpRequest {
    pub method: Method,
    pub url: String,
    pub headers: Vec<(String, String)>,
    pub body: Option<Vec<u8>>,
}

/// Response surface the core needs: status, headers, body.
#[derive(Debug, Clone)]
pub struct HttpResponse {
    pub status: u16,
    pub headers: Vec<(String, String)>,
    pub body: Vec<u8>,
}

impl HttpResponse {
    pub fn is_success(&self) -> bool {
        (200..300).contains(&self.status)
    }

    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers
            .iter()
            .find(|(k, _)| k.eq_ignore_ascii_case(name))
            .map(|(_, v)| v.as_str())
    }

    pub fn body_text(&self) -> String {
        String::from_utf8_lossy(&self.body).into_owned()
    }

    pub fn json<T: DeserializeOwned>(&self) -> Result<T, CoreError> {
        serde_json::from_slice(&self.body).map_err(CoreError::decode)
    }
}

/// Host-provided HTTP transport.
///
/// Implementations should return `Ok` for any response the server produced
/// (including 4xx/5xx) and `Err` only for transport-level failures
/// (DNS, connect, abort).
#[async_trait::async_trait]
pub trait HttpClient {
    async fn request(&self, request: HttpRequest) -> Result<HttpResponse, CoreError>;
}

/// Yields the JWT to attach to a request, called once per request so hosts
/// can lazily mint short-lived tokens. Return `Ok(None)` for unauthenticated
/// (local development) setups.
#[async_trait::async_trait]
pub trait AuthProvider {
    async fn jwt(&self) -> Result<Option<String>, CoreError>;
}

/// [`AuthProvider`] for a fixed (or absent) token.
#[derive(Debug, Clone, Default)]
pub struct StaticAuth(pub Option<String>);

#[async_trait::async_trait]
impl AuthProvider for StaticAuth {
    async fn jwt(&self) -> Result<Option<String>, CoreError> {
        Ok(self.0.clone())
    }
}

/// Map version-negotiation statuses to [`CoreError::VersionMismatch`] and
/// other non-success statuses to [`CoreError::Status`].
pub fn check_status(response: &HttpResponse, context: &str) -> Result<(), CoreError> {
    match response.status {
        s if (200..300).contains(&s) => Ok(()),
        426 => Err(CoreError::VersionMismatch(format!(
            "{context}: client API version too old (426)"
        ))),
        501 => Err(CoreError::VersionMismatch(format!(
            "{context}: server does not support client API version (501)"
        ))),
        s => Err(CoreError::Status {
            status: s,
            context: context.to_string(),
            body: response.body_text(),
        }),
    }
}
