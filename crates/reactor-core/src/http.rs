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
#[cfg_attr(not(target_family = "wasm"), async_trait::async_trait)]
#[cfg_attr(target_family = "wasm", async_trait::async_trait(?Send))]
pub trait HttpClient {
    async fn request(&self, request: HttpRequest) -> Result<HttpResponse, CoreError>;
}

/// What the request about to be authenticated is for. `session_id` is set for
/// every call that targets an existing session and unset for session creation,
/// so a host that mints a replacement token can bind it to the right session.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct AuthRequest {
    pub session_id: Option<String>,
}

impl AuthRequest {
    pub fn for_session(session_id: &str) -> Self {
        Self {
            session_id: Some(session_id.to_string()),
        }
    }
}

/// Yields the JWT to attach to a request, called once per request so hosts
/// can lazily mint short-lived tokens. Return `Ok(None)` for unauthenticated
/// (local development) setups.
#[cfg_attr(not(target_family = "wasm"), async_trait::async_trait)]
#[cfg_attr(target_family = "wasm", async_trait::async_trait(?Send))]
pub trait AuthProvider {
    async fn jwt(&self, request: &AuthRequest) -> Result<Option<String>, CoreError>;
}

/// [`AuthProvider`] for a fixed (or absent) token.
#[derive(Debug, Clone, Default)]
pub struct StaticAuth(pub Option<String>);

#[cfg_attr(not(target_family = "wasm"), async_trait::async_trait)]
#[cfg_attr(target_family = "wasm", async_trait::async_trait(?Send))]
impl AuthProvider for StaticAuth {
    async fn jwt(&self, _request: &AuthRequest) -> Result<Option<String>, CoreError> {
        Ok(self.0.clone())
    }
}

/// Headers the caller asks to have sent on every request.
///
/// Sorted by name, which also makes a name given twice in the same case
/// impossible.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ExtraHeaders(std::collections::BTreeMap<String, String>);

impl ExtraHeaders {
    pub fn is_empty(&self) -> bool {
        self.0.is_empty()
    }

    /// The headers as the `(name, value)` pairs a request carries.
    pub fn to_pairs(&self) -> Vec<(String, String)> {
        self.0.iter().map(|(k, v)| (k.clone(), v.clone())).collect()
    }

    /// Add these headers after the SDK's own. A name already in `headers`, in any
    /// case, is skipped, so an SDK value always wins whatever the reserved list
    /// says: the clients append rather than replace, and a second copy would
    /// otherwise reach the server beside it.
    pub fn append_to(&self, headers: &mut Vec<(String, String)>) {
        for (name, value) in &self.0 {
            if !headers
                .iter()
                .any(|(own, _)| own.eq_ignore_ascii_case(name))
            {
                headers.push((name.clone(), value.clone()));
            }
        }
    }

    /// See [`validate_extra_headers`].
    pub fn validate(&self) -> Result<(), CoreError> {
        validate_extra_headers(&self.to_pairs())
    }

    /// Names, for a caller that needs to look at them (the browser's own
    /// forbidden-name check does).
    pub fn names(&self) -> impl Iterator<Item = &str> {
        self.0.keys().map(String::as_str)
    }
}

/// Read from a JSON object of strings. A name written twice in the same case is
/// an error rather than a silent last-one-wins, so the duplicate rule holds for
/// JSON that never passes through a map in the caller's language.
impl<'de> serde::Deserialize<'de> for ExtraHeaders {
    fn deserialize<D: serde::Deserializer<'de>>(deserializer: D) -> Result<Self, D::Error> {
        struct Visitor;
        impl<'de> serde::de::Visitor<'de> for Visitor {
            type Value = ExtraHeaders;

            fn expecting(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
                f.write_str("an object of header names to string values")
            }

            fn visit_map<A: serde::de::MapAccess<'de>>(
                self,
                mut map: A,
            ) -> Result<Self::Value, A::Error> {
                let mut headers = std::collections::BTreeMap::new();
                while let Some((name, value)) = map.next_entry::<String, String>()? {
                    if headers.contains_key(&name) {
                        return Err(serde::de::Error::custom(format!(
                            "extra header {name:?} is given more than once"
                        )));
                    }
                    headers.insert(name, value);
                }
                Ok(ExtraHeaders(headers))
            }
        }
        deserializer.deserialize_map(Visitor)
    }
}

impl From<std::collections::BTreeMap<String, String>> for ExtraHeaders {
    fn from(headers: std::collections::BTreeMap<String, String>) -> Self {
        Self(headers)
    }
}

/// Headers the SDK or the transport owns, which [`validate_extra_headers`]
/// refuses to let a caller set. The HTTP clients append rather than replace, so a
/// duplicate would go out beside the real one, and a browser drops several of
/// these without a word.
const RESERVED_HEADERS: &[&str] = &[
    "authorization",
    "content-type",
    "content-length",
    "reactor-api-key",
    "reactor-api-version",
    "reactor-api-accept-version",
    "reactor-webrtc-version",
    "host",
    "connection",
    "transfer-encoding",
    "upgrade",
    "accept-encoding",
    "te",
    "trailer",
    "expect",
    "keep-alive",
];

/// Check the caller's extra headers, sent on every request to the coordinator
/// and the signaling endpoints.
///
/// A name must be an HTTP token and a value visible ASCII (space and tab
/// allowed), so a bad one fails here instead of as a transport error mid-session.
/// ASCII only because the transports disagree about anything wider: a browser
/// `Headers` and Python's `urllib` refuse or Latin-1 encode it, while the native
/// client sends UTF-8, so the same value would name different bytes on different
/// requests. A name given twice, whatever its case, is refused because the
/// native client sends both and a browser keeps one. A header the SDK sets
/// itself is refused rather than dropped: silently ignoring an `Authorization`
/// the caller meant to send would look like a working setup.
pub fn validate_extra_headers(headers: &[(String, String)]) -> Result<(), CoreError> {
    let mut seen = std::collections::HashSet::new();
    for (name, value) in headers {
        let is_token = !name.is_empty()
            && name
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b"!#$%&'*+-.^_`|~".contains(&b));
        if !is_token {
            return Err(CoreError::InvalidState(format!(
                "invalid extra header name {name:?}"
            )));
        }
        if RESERVED_HEADERS.contains(&name.to_ascii_lowercase().as_str()) {
            return Err(CoreError::InvalidState(format!(
                "extra header {name:?} is set by the SDK and cannot be overridden"
            )));
        }
        if value
            .bytes()
            .any(|b| !(b == b'\t' || (0x20..0x7f).contains(&b)))
        {
            return Err(CoreError::InvalidState(format!(
                "invalid value for extra header {name:?}: only visible ASCII is allowed"
            )));
        }
        if !seen.insert(name.to_ascii_lowercase()) {
            return Err(CoreError::InvalidState(format!(
                "extra header {name:?} is given more than once"
            )));
        }
    }
    Ok(())
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

    fn pairs(headers: &[(&str, &str)]) -> Vec<(String, String)> {
        headers
            .iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect()
    }

    #[test]
    fn extra_headers_accept_ordinary_names_and_values() {
        assert!(validate_extra_headers(&pairs(&[
            ("X-Client-Id", "my-app/2.1.0"),
            ("x_trace.id", "a b\tc"),
        ]))
        .is_ok());
    }

    #[test]
    fn extra_headers_reject_names_the_sdk_sets() {
        for name in [
            "Authorization",
            "content-type",
            "Content-Length",
            "Reactor-API-Version",
            "reactor-api-accept-version",
            "REACTOR-WEBRTC-VERSION",
            "Reactor-API-Key",
            "Host",
            "connection",
            "Transfer-Encoding",
            "upgrade",
            "Accept-Encoding",
            "TE",
            "trailer",
            "Expect",
            "keep-alive",
        ] {
            assert!(
                validate_extra_headers(&pairs(&[(name, "x")])).is_err(),
                "{name} should be refused"
            );
        }
    }

    #[test]
    fn extra_headers_reject_malformed_names_and_values() {
        for (name, value) in [
            ("", "x"),
            ("a b", "x"),
            ("a:b", "x"),
            ("X-A", "a\r\nB: c"),
            ("X-A", "a\0"),
            ("X-A", "caf\u{e9}"),
            ("X-A", "\u{6f22}"),
            ("X-A", "\u{fffd}"),
            ("X-A", "a\x7f"),
        ] {
            assert!(
                validate_extra_headers(&pairs(&[(name, value)])).is_err(),
                "{name:?}: {value:?} should be refused"
            );
        }
    }

    #[test]
    fn appended_extra_headers_never_shadow_the_sdks_own() {
        let extra = ExtraHeaders::from(std::collections::BTreeMap::from([
            ("authorization".to_string(), "Bearer other".to_string()),
            ("X-Tenant".to_string(), "a".to_string()),
        ]));
        let mut headers = vec![("Authorization".to_string(), "Bearer own".to_string())];
        extra.append_to(&mut headers);
        assert_eq!(
            headers,
            vec![
                ("Authorization".to_string(), "Bearer own".to_string()),
                ("X-Tenant".to_string(), "a".to_string()),
            ]
        );
    }

    #[test]
    fn extra_headers_json_rejects_a_repeated_key() {
        let repeated = r#"{"X-Tenant":"a","X-Tenant":"b"}"#;
        assert!(serde_json::from_str::<ExtraHeaders>(repeated).is_err());
        assert!(serde_json::from_str::<ExtraHeaders>(r#"{"X-Tenant":1}"#).is_err());
        let ok = serde_json::from_str::<ExtraHeaders>(r#"{"X-A":"1","X-B":"2"}"#).unwrap();
        assert_eq!(ok.to_pairs().len(), 2);
    }

    #[test]
    fn extra_headers_reject_a_name_given_twice_in_any_case() {
        assert!(validate_extra_headers(&pairs(&[("X-Tenant", "a"), ("x-tenant", "b")])).is_err());
        assert!(validate_extra_headers(&pairs(&[("X-A", "1"), ("X-A", "1")])).is_err());
        assert!(validate_extra_headers(&pairs(&[("X-A", "1"), ("X-B", "1")])).is_ok());
    }
}

/// Doubles shared by the client tests: an auth provider that records what it
/// is asked for, an HTTP client that answers 404, and a platform that never
/// sleeps.
#[cfg(test)]
pub(crate) mod testing {
    use std::sync::Mutex;
    use std::time::Duration;

    use super::{AuthProvider, AuthRequest, HttpClient, HttpRequest, HttpResponse};
    use crate::error::CoreError;
    use crate::runtime::Platform;
    use crate::BoxFut;

    /// Records every `AuthRequest` it is asked for, so a test can see which
    /// session each call said it was for.
    #[derive(Default)]
    pub(crate) struct RecordingAuth {
        pub(crate) requests: Mutex<Vec<AuthRequest>>,
    }

    #[async_trait::async_trait]
    impl AuthProvider for RecordingAuth {
        async fn jwt(&self, request: &AuthRequest) -> Result<Option<String>, CoreError> {
            self.requests.lock().unwrap().push(request.clone());
            Ok(Some("token".into()))
        }
    }

    /// Answers every request with 404: enough to get past the auth step, and
    /// what `terminate_session` treats as already gone.
    pub(crate) struct NotFoundHttp;

    #[async_trait::async_trait]
    impl HttpClient for NotFoundHttp {
        async fn request(&self, _request: HttpRequest) -> Result<HttpResponse, CoreError> {
            Ok(HttpResponse {
                status: 404,
                headers: Vec::new(),
                body: Vec::new(),
            })
        }
    }

    pub(crate) struct NoSleep;

    impl Platform for NoSleep {
        fn sleep(&self, _duration: Duration) -> BoxFut<'static, ()> {
            Box::pin(async {})
        }
        fn now_ms(&self) -> f64 {
            0.0
        }
    }
}
