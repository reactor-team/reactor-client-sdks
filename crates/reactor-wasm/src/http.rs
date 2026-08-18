//! [`HttpClient`] implementation backed by the browser `fetch()` API.

use wasm_bindgen::JsCast;
use wasm_bindgen_futures::JsFuture;
use web_sys::{Headers, Request, RequestInit, Response};

use reactor_core::error::CoreError;
use reactor_core::http::{HttpClient, HttpRequest, HttpResponse};

use crate::platform;

pub struct WasmHttpClient;

#[async_trait::async_trait(?Send)]
impl HttpClient for WasmHttpClient {
    async fn request(&self, request: HttpRequest) -> Result<HttpResponse, CoreError> {
        let options = RequestInit::new();
        options.set_method(request.method.as_str());

        if let Some(body) = &request.body {
            // `Uint8Array::from` copies into the JS heap, which is what we want:
            // the wasm memory backing `body` can be reallocated while fetch is in
            // flight, and a view into it would then read someone else's bytes.
            let bytes = js_sys::Uint8Array::from(body.as_slice());
            options.set_body(&bytes);
        }

        let headers = Headers::new().map_err(js_err)?;
        for (name, value) in &request.headers {
            headers.set(name, value).map_err(js_err)?;
        }
        options.set_headers(&headers);

        let js_request = Request::new_with_str_and_init(&request.url, &options).map_err(js_err)?;
        let response: Response = JsFuture::from(platform::fetch(&js_request)?)
            .await
            .map_err(js_err)?
            .dyn_into()
            .map_err(|_| CoreError::Http("fetch did not resolve with a Response".into()))?;

        let body_buffer = JsFuture::from(response.array_buffer().map_err(js_err)?)
            .await
            .map_err(js_err)?;

        Ok(HttpResponse {
            status: response.status(),
            // Kept rather than dropped: `Retry-After` on a 429/503 is what the
            // core's backoff reads, and a response with no headers silently
            // turns a server-directed wait into a client-guessed one.
            headers: response_headers(&response),
            body: js_sys::Uint8Array::new(&body_buffer).to_vec(),
        })
    }
}

/// Read the response headers into the core's `(name, value)` pairs.
///
/// `Headers` is iterable in every browser that supports fetch; if the iteration
/// protocol is missing (a polyfill, a non-browser shim), the response is still
/// returned — without headers rather than as a failure.
fn response_headers(response: &Response) -> Vec<(String, String)> {
    let mut headers = Vec::new();
    let iterable: wasm_bindgen::JsValue = response.headers().into();
    let Ok(Some(entries)) = js_sys::try_iter(&iterable) else {
        return headers;
    };
    for entry in entries.flatten() {
        let Ok(pair) = entry.dyn_into::<js_sys::Array>() else {
            continue;
        };
        if let (Some(name), Some(value)) = (pair.get(0).as_string(), pair.get(1).as_string()) {
            headers.push((name, value));
        }
    }
    headers
}

/// A rejected JS promise or a thrown JS value, as a core transport error.
pub(crate) fn js_err(error: wasm_bindgen::JsValue) -> CoreError {
    CoreError::Http(describe(&error))
}

/// A JS error's `message` when it has one, its string form otherwise —
/// `{:?}` on a `JsValue` prints `JsValue(TypeError: ...)`, which then shows up
/// inside user-facing error messages.
pub(crate) fn describe(error: &wasm_bindgen::JsValue) -> String {
    if let Some(text) = error.as_string() {
        return text;
    }
    if let Some(js_error) = error.dyn_ref::<js_sys::Error>() {
        return js_error.message().into();
    }
    js_sys::JSON::stringify(error)
        .map(String::from)
        .unwrap_or_else(|_| format!("{error:?}"))
}
