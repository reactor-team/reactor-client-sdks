//! [`AuthProvider`] backed by a JavaScript token source.
//!
//! The JS SDK accepts either a fixed JWT or a resolver function called before
//! every authenticated request, because the tokens apps hand us are typically
//! short-lived — a session token from the app's own identity layer, or a JWT a
//! backend mints per visit. A provider that only took a string would force the
//! SDK to reconnect to refresh a token, so this one calls back into JS per
//! request and awaits a promise if it gets one.

use std::cell::RefCell;

use wasm_bindgen::prelude::*;
use wasm_bindgen_futures::JsFuture;

use reactor_core::error::CoreError;
use reactor_core::http::AuthProvider;

use crate::http::describe;

/// Where the JWT comes from.
enum JwtSource {
    /// No token: local development against an unauthenticated runtime.
    None,
    Static(String),
    /// `() => string | Promise<string>`
    Resolver(js_sys::Function),
}

pub struct WasmAuthProvider {
    source: RefCell<JwtSource>,
}

impl WasmAuthProvider {
    pub fn new() -> Self {
        Self {
            source: RefCell::new(JwtSource::None),
        }
    }

    /// Set (or replace) the token source: a string, a function, or
    /// `null`/`undefined` to go unauthenticated.
    ///
    /// Replacing it takes effect on the next request, so a client can be
    /// constructed before the app knows its token — which is how the JS SDK
    /// works today, where the JWT is an argument to `connect()`.
    pub fn set(&self, value: JsValue) -> Result<(), CoreError> {
        let source = if value.is_null() || value.is_undefined() {
            JwtSource::None
        } else if let Some(token) = value.as_string() {
            JwtSource::Static(token)
        } else if value.is_function() {
            JwtSource::Resolver(value.unchecked_into())
        } else {
            return Err(CoreError::InvalidState(
                "jwt must be a string, a function returning one, or null".into(),
            ));
        };
        *self.source.borrow_mut() = source;
        Ok(())
    }
}

impl Default for WasmAuthProvider {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait::async_trait(?Send)]
impl AuthProvider for WasmAuthProvider {
    async fn jwt(&self) -> Result<Option<String>, CoreError> {
        // Clone the resolver out and drop the borrow before awaiting: the JS
        // callback can call back into this client (a `setJwt` from inside a
        // resolver is legal), and a live `RefCell` borrow would panic.
        let resolver = match &*self.source.borrow() {
            JwtSource::None => return Ok(None),
            JwtSource::Static(token) => return Ok(non_empty(token.clone())),
            JwtSource::Resolver(function) => function.clone(),
        };

        let resolved = resolver
            .call0(&JsValue::null())
            .map_err(|e| CoreError::Http(format!("jwt resolver threw: {}", describe(&e))))?;

        let value = if resolved.has_type::<js_sys::Promise>() {
            JsFuture::from(js_sys::Promise::from(resolved))
                .await
                .map_err(|e| CoreError::Http(format!("jwt resolver rejected: {}", describe(&e))))?
        } else {
            resolved
        };

        match value.as_string() {
            Some(token) => Ok(non_empty(token)),
            None if value.is_null() || value.is_undefined() => Ok(None),
            None => Err(CoreError::Http(
                "jwt resolver must resolve with a string".into(),
            )),
        }
    }
}

/// An empty token means "send no Authorization header" — the same contract the
/// JS SDK's `JwtResolver` has documented, kept so an app that returns `""`
/// while signed out keeps working instead of sending `Bearer `.
fn non_empty(token: String) -> Option<String> {
    (!token.is_empty()).then_some(token)
}
