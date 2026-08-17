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

/// Read a `Retry-After` header as milliseconds.
///
/// Only the delta-seconds form — `Retry-After: 120` — which is what a 429 or a
/// 503 carries in practice. The other form the RFC allows is an HTTP-date, and
/// turning one into a duration needs a trusted clock that this function does not
/// have; a wrong answer there would be worse than none, since a caller would
/// back off by whatever the client's clock skew happens to be.
///
/// `None` for anything unreadable, which is the same answer as a missing header:
/// the server gave no usable hint, so the caller backs off on its own terms.
pub fn parse_retry_after_ms(value: &str) -> Option<f64> {
    let seconds: f64 = value.trim().parse().ok()?;
    // Negative and non-finite are nonsense, and a caller told to wait NaN
    // milliseconds is worse off than one told nothing.
    (seconds.is_finite() && seconds >= 0.0).then_some(seconds * 1_000.0)
}

/// Map version-negotiation statuses to [`CoreError::VersionMismatch`] and
/// other non-success statuses to [`CoreError::Status`].
///
/// A version mismatch deliberately carries no backoff hint: waiting does not
/// make a client new enough.
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
            retry_after_ms: response
                .header("retry-after")
                .and_then(parse_retry_after_ms),
        }),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn response(status: u16, headers: &[(&str, &str)]) -> HttpResponse {
        HttpResponse {
            status,
            headers: headers
                .iter()
                .map(|(k, v)| (k.to_string(), v.to_string()))
                .collect(),
            body: Vec::new(),
        }
    }

    #[test]
    fn delta_seconds_becomes_milliseconds() {
        assert_eq!(parse_retry_after_ms("120"), Some(120_000.0));
        assert_eq!(parse_retry_after_ms("0"), Some(0.0));
        assert_eq!(parse_retry_after_ms("  30 "), Some(30_000.0));
    }

    /// The HTTP-date form is legal and unreadable here: converting it needs a
    /// trusted clock, and a hint computed from a skewed one is worse than none.
    #[test]
    fn the_http_date_form_yields_nothing_rather_than_a_guess() {
        assert_eq!(parse_retry_after_ms("Wed, 21 Oct 2015 07:28:00 GMT"), None);
    }

    #[test]
    fn nonsense_yields_nothing() {
        assert_eq!(parse_retry_after_ms(""), None);
        assert_eq!(parse_retry_after_ms("soon"), None);
        assert_eq!(parse_retry_after_ms("-5"), None);
        assert_eq!(parse_retry_after_ms("NaN"), None);
        assert_eq!(parse_retry_after_ms("inf"), None);
    }

    /// Header names are case-insensitive on the wire, and servers differ.
    #[test]
    fn the_header_is_found_whatever_its_casing() {
        let error = check_status(&response(429, &[("Retry-After", "5")]), "create session")
            .expect_err("429 is an error");
        assert_eq!(error.retry_after_ms(), Some(5_000.0));

        let error = check_status(&response(503, &[("retry-after", "1")]), "create session")
            .expect_err("503 is an error");
        assert_eq!(error.retry_after_ms(), Some(1_000.0));
    }

    #[test]
    fn a_status_without_the_header_carries_no_hint() {
        let error =
            check_status(&response(500, &[]), "create session").expect_err("500 is an error");
        assert_eq!(error.retry_after_ms(), None);
    }

    /// Waiting does not make a client new enough, so a version mismatch must not
    /// arrive looking like something worth retrying.
    #[test]
    fn a_version_mismatch_carries_no_hint_even_if_one_was_sent() {
        let error = check_status(&response(426, &[("retry-after", "60")]), "create session")
            .expect_err("426 is an error");
        assert_eq!(error.retry_after_ms(), None);
        assert!(!error.recoverable());
    }

    #[test]
    fn a_success_is_not_an_error() {
        assert!(check_status(&response(204, &[]), "terminate session").is_ok());
    }
}
