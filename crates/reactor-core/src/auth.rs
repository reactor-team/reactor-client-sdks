//! Exchanging an API key for a JWT.
//!
//! Everything else in this crate takes a JWT and has no notion of an API key: a
//! token is short-lived and scoped, a key is neither, and the session protocol
//! only ever sees the former. Callers who hold a key rather than a token need the
//! exchange done for them, and it is a single POST.
//!
//! It lives here rather than in each binding because each binding paid for it
//! separately: the Python SDK carries its own `urllib` copy, and a binding in a
//! language with no HTTP client in its standard library — C++, most obviously —
//! would otherwise have to take on a TLS stack for one request. Here it reaches
//! every host through the [`HttpClient`] the host already provides, including the
//! browser one.

use serde::Deserialize;

use crate::error::CoreError;
use crate::http::{check_status, HttpRequest, Method};
use crate::SharedHttp;

/// Where the coordinator mints tokens.
const TOKENS_PATH: &str = "/tokens";

/// The header the coordinator reads the key from. Deliberately not
/// `Authorization`: a key is not a bearer token, and sending it as one invites a
/// proxy or a log scrubber to treat the two the same.
const API_KEY_HEADER: &str = "Reactor-API-Key";

/// What a token may do, when the caller wants less than the key allows.
///
/// The default — every field `None` — mints a token carrying the full set of
/// permissions the key's roles allow. That is fine server-to-server and wrong to
/// hand to a client you do not control, which is what [`TokenRequest::scoped`]
/// exists for.
///
/// Unknown fields are **rejected** when this is deserialised, which is the
/// unusual choice and the deliberate one: a misspelt `model` where `models` was
/// meant would otherwise be dropped in silence and mint the unscoped token the
/// caller was explicitly trying to avoid. A parse error is the cheap failure here.
#[derive(Debug, Clone, Default, PartialEq, Eq, Deserialize)]
#[serde(default, deny_unknown_fields)]
pub struct TokenRequest {
    /// Restrict the token to these models, as `owner/name`. Present makes the
    /// token **session-scoped**: it can only create and operate sessions on these,
    /// so a leak is worth a handful of sessions rather than everything the key can
    /// reach.
    pub models: Option<Vec<String>>,
    /// How many sessions a scoped token may ever create. Ignored by the server for
    /// an unscoped one, so setting it without `models` buys nothing.
    pub max_sessions: Option<u32>,
    /// Lifetime in seconds. The server clamps it to its own ceiling, so asking for
    /// a year gets whatever the ceiling is rather than an error.
    pub expires_after: Option<u64>,
}

impl TokenRequest {
    /// A token that can only reach `models`.
    pub fn scoped(models: impl IntoIterator<Item = impl Into<String>>) -> Self {
        Self {
            models: Some(models.into_iter().map(Into::into).collect()),
            ..Self::default()
        }
    }

    /// The request body, or `None` when there is nothing to constrain.
    ///
    /// An unconstrained request sends the JSON literal `null` rather than `{}` —
    /// that is what the coordinator expects, and `{}` is rejected.
    fn body(&self) -> Option<serde_json::Value> {
        let mut body = serde_json::Map::new();

        if let Some(models) = &self.models {
            let mut detail = serde_json::json!({
                "type": "session",
                "resources": { "models": { "match": models } },
            });
            if let Some(max_sessions) = self.max_sessions {
                detail["constraints"] = serde_json::json!({ "max_sessions": max_sessions });
            }
            body.insert(
                "authorization_details".into(),
                serde_json::Value::Array(vec![detail]),
            );
        }

        if let Some(expires_after) = self.expires_after {
            body.insert("expires_after".into(), expires_after.into());
        }

        (!body.is_empty()).then_some(serde_json::Value::Object(body))
    }
}

#[derive(Deserialize)]
struct TokenResponse {
    #[serde(default)]
    jwt: String,
}

/// Exchange `api_key` for a JWT.
///
/// `api_url` is the coordinator base, e.g. `https://api.reactor.inc`; a trailing
/// slash is tolerated.
///
/// Failures arrive as ordinary [`CoreError`]s, so a rejected key is
/// `UNAUTHORIZED` and an unreachable coordinator is `NETWORK_ERROR` — the same
/// codes, and the same recoverability, that every other call reports. A 200 that
/// carries no token is [`CoreError::Decode`]: the server answered, and the answer
/// was unusable, which is a different thing from being turned away.
pub async fn fetch_jwt(
    http: &SharedHttp,
    api_url: &str,
    api_key: &str,
    request: &TokenRequest,
) -> Result<String, CoreError> {
    let url = format!("{}{}", api_url.trim_end_matches('/'), TOKENS_PATH);

    let body = serde_json::to_vec(&request.body()).map_err(CoreError::decode)?;

    let response = http
        .request(HttpRequest {
            method: Method::Post,
            url: url.clone(),
            headers: vec![
                (API_KEY_HEADER.to_string(), api_key.to_string()),
                ("Content-Type".to_string(), "application/json".to_string()),
            ],
            body: Some(body),
        })
        .await?;

    check_status(&response, "fetch token")?;

    let token: TokenResponse = response.json()?;
    if token.jwt.is_empty() {
        return Err(CoreError::Decode(format!("{url} returned no token")));
    }
    Ok(token.jwt)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::error::codes;
    use crate::http::{HttpClient, HttpResponse};
    use std::sync::{Arc, Mutex};

    /// Records the one request it is given and replays a canned response.
    struct FakeHttp {
        response: HttpResponse,
        seen: Mutex<Option<HttpRequest>>,
    }

    impl FakeHttp {
        fn returning(status: u16, body: &str) -> Arc<Self> {
            Arc::new(Self {
                response: HttpResponse {
                    status,
                    headers: Vec::new(),
                    body: body.as_bytes().to_vec(),
                },
                seen: Mutex::new(None),
            })
        }

        fn request(&self) -> HttpRequest {
            self.seen.lock().unwrap().clone().expect("no request made")
        }

        fn body_json(&self) -> serde_json::Value {
            serde_json::from_slice(&self.request().body.expect("no body sent")).unwrap()
        }
    }

    #[async_trait::async_trait]
    impl HttpClient for FakeHttp {
        async fn request(&self, request: HttpRequest) -> Result<HttpResponse, CoreError> {
            *self.seen.lock().unwrap() = Some(request);
            Ok(self.response.clone())
        }
    }

    /// Errs the way a transport errs: no response at all, which is what a refused
    /// socket or a DNS failure looks like from here.
    struct DeadHttp;

    #[async_trait::async_trait]
    impl HttpClient for DeadHttp {
        async fn request(&self, _request: HttpRequest) -> Result<HttpResponse, CoreError> {
            Err(CoreError::Http("connection refused".into()))
        }
    }

    fn block_on<F: std::future::Future>(future: F) -> F::Output {
        tokio::runtime::Builder::new_current_thread()
            .build()
            .unwrap()
            .block_on(future)
    }

    #[test]
    fn the_key_travels_in_its_own_header_and_never_as_a_bearer_token() {
        let http = FakeHttp::returning(200, r#"{"jwt":"token"}"#);

        let jwt = block_on(fetch_jwt(
            &(http.clone() as SharedHttp),
            "https://api.reactor.inc",
            "key-123",
            &TokenRequest::default(),
        ))
        .unwrap();

        assert_eq!(jwt, "token");
        let request = http.request();
        assert_eq!(request.method, Method::Post);
        assert_eq!(request.url, "https://api.reactor.inc/tokens");
        assert!(request
            .headers
            .contains(&("Reactor-API-Key".into(), "key-123".into())));
        assert!(!request
            .headers
            .iter()
            .any(|(name, _)| name.eq_ignore_ascii_case("authorization")));
    }

    /// `{}` is rejected by the coordinator, so an unconstrained request has to send
    /// the JSON literal `null`. Easy to "fix" into an empty object by someone
    /// tidying up, hence the test.
    #[test]
    fn an_unconstrained_request_sends_null_not_an_empty_object() {
        let http = FakeHttp::returning(200, r#"{"jwt":"token"}"#);

        block_on(fetch_jwt(
            &(http.clone() as SharedHttp),
            "https://api.reactor.inc/",
            "key",
            &TokenRequest::default(),
        ))
        .unwrap();

        assert_eq!(http.body_json(), serde_json::Value::Null);
        // The trailing slash above must not produce `//tokens`.
        assert_eq!(http.request().url, "https://api.reactor.inc/tokens");
    }

    #[test]
    fn a_scoped_request_names_its_models_and_carries_the_session_cap() {
        let http = FakeHttp::returning(200, r#"{"jwt":"token"}"#);

        block_on(fetch_jwt(
            &(http.clone() as SharedHttp),
            "https://api.reactor.inc",
            "key",
            &TokenRequest {
                max_sessions: Some(3),
                expires_after: Some(600),
                ..TokenRequest::scoped(["reactor/helios"])
            },
        ))
        .unwrap();

        assert_eq!(
            http.body_json(),
            serde_json::json!({
                "authorization_details": [{
                    "type": "session",
                    "resources": { "models": { "match": ["reactor/helios"] } },
                    "constraints": { "max_sessions": 3 },
                }],
                "expires_after": 600,
            })
        );
    }

    /// The cap only means something for a scoped token, and the server ignores it
    /// otherwise — so it must not smuggle an `authorization_details` into an
    /// unscoped request, which would silently scope it to nothing.
    #[test]
    fn a_session_cap_without_models_constrains_nothing() {
        let http = FakeHttp::returning(200, r#"{"jwt":"token"}"#);

        block_on(fetch_jwt(
            &(http.clone() as SharedHttp),
            "https://api.reactor.inc",
            "key",
            &TokenRequest {
                max_sessions: Some(3),
                ..TokenRequest::default()
            },
        ))
        .unwrap();

        assert_eq!(http.body_json(), serde_json::Value::Null);
    }

    #[test]
    fn a_rejected_key_is_unauthorized_and_not_worth_retrying() {
        let http = FakeHttp::returning(401, r#"{"error":"unknown key"}"#);

        let error = block_on(fetch_jwt(
            &(http.clone() as SharedHttp),
            "https://api.reactor.inc",
            "nope",
            &TokenRequest::default(),
        ))
        .expect_err("401 is an error");

        assert_eq!(error.code(), codes::UNAUTHORIZED);
        assert!(!error.recoverable());
        // The body usually says which of key, model or permission was the problem,
        // so it is worth more than the status line alone.
        assert!(error.to_string().contains("unknown key"));
    }

    #[test]
    fn an_unreachable_coordinator_is_a_network_error_and_is_worth_retrying() {
        let error = block_on(fetch_jwt(
            &(Arc::new(DeadHttp) as SharedHttp),
            "https://api.reactor.inc",
            "key",
            &TokenRequest::default(),
        ))
        .expect_err("a dead transport is an error");

        assert_eq!(error.code(), codes::NETWORK_ERROR);
        assert!(error.recoverable());
    }

    /// A binding hands these fields over as JSON it built from its own caller's
    /// arguments, so a name can be wrong. Scoping is the one place where dropping
    /// an unrecognised key would hand back a *more* powerful token than was asked
    /// for, so it has to be an error.
    #[test]
    fn a_misspelt_field_is_rejected_rather_than_ignored() {
        let error = serde_json::from_str::<TokenRequest>(r#"{"model":["reactor/helios"]}"#)
            .expect_err("an unknown field is an error");
        assert!(error.to_string().contains("model"));

        assert_eq!(
            serde_json::from_str::<TokenRequest>(r#"{"models":["reactor/helios"]}"#).unwrap(),
            TokenRequest::scoped(["reactor/helios"])
        );
        assert_eq!(
            serde_json::from_str::<TokenRequest>("{}").unwrap(),
            TokenRequest::default()
        );
    }

    /// A 200 with no token is not a rejection: the server answered, the answer was
    /// unusable. Reporting it as `UNAUTHORIZED` would send a caller off to fix a
    /// key that is fine.
    #[test]
    fn a_success_carrying_no_token_is_a_decode_failure() {
        for body in [r#"{}"#, r#"{"jwt":""}"#, r#"not json"#] {
            let http = FakeHttp::returning(200, body);
            let error = block_on(fetch_jwt(
                &(http.clone() as SharedHttp),
                "https://api.reactor.inc",
                "key",
                &TokenRequest::default(),
            ))
            .expect_err("a tokenless success is an error");
            assert_eq!(error.code(), codes::DECODE_FAILED, "body: {body}");
        }
    }
}
