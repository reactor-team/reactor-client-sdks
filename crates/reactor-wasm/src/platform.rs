//! [`Platform`] implementation backed by browser timers, plus the global-scope
//! lookups the rest of the crate needs.
//!
//! Everything goes through [`global_scope`] rather than `web_sys::window()`:
//! the SDK is expected to run inside a Web Worker as well as on a page, and
//! there is no `window` in a worker.

use std::time::Duration;

use wasm_bindgen::prelude::*;
use wasm_bindgen::JsCast;
use web_sys::{Request, Window, WorkerGlobalScope};

use reactor_core::error::CoreError;
use reactor_core::runtime::Platform;
use reactor_core::BoxFut;

/// Which kind of global this wasm module was instantiated in.
pub enum GlobalScope {
    Window(Window),
    Worker(WorkerGlobalScope),
}

/// Resolve the global scope once per call — cheap, and it avoids caching a JS
/// handle in a `static`.
pub fn global_scope() -> Result<GlobalScope, CoreError> {
    let global = js_sys::global();
    if let Some(window) = global.dyn_ref::<Window>() {
        return Ok(GlobalScope::Window(window.clone()));
    }
    if let Some(worker) = global.dyn_ref::<WorkerGlobalScope>() {
        return Ok(GlobalScope::Worker(worker.clone()));
    }
    Err(CoreError::Http(
        "no browser global scope (neither Window nor WorkerGlobalScope)".into(),
    ))
}

/// `fetch(request)` on whichever global we are in.
pub fn fetch(request: &Request) -> Result<js_sys::Promise, CoreError> {
    Ok(match global_scope()? {
        GlobalScope::Window(window) => window.fetch_with_request(request),
        GlobalScope::Worker(worker) => worker.fetch_with_request(request),
    })
}

/// `setTimeout(resolve, ms)` on whichever global we are in.
fn set_timeout(resolve: &js_sys::Function, ms: i32) -> Result<(), CoreError> {
    let result = match global_scope()? {
        GlobalScope::Window(window) => {
            window.set_timeout_with_callback_and_timeout_and_arguments_0(resolve, ms)
        }
        GlobalScope::Worker(worker) => {
            worker.set_timeout_with_callback_and_timeout_and_arguments_0(resolve, ms)
        }
    };
    result
        .map(|_| ())
        .map_err(|e| CoreError::Http(format!("setTimeout failed: {e:?}")))
}

pub struct WasmPlatform;

impl Platform for WasmPlatform {
    fn sleep(&self, duration: Duration) -> BoxFut<'static, ()> {
        // Saturate rather than wrap: a timer request longer than ~24 days is a
        // bug in the caller, and wrapping it into a negative delay would fire
        // the timer immediately, which is the one outcome a sleep must not do.
        let ms = duration.as_millis().min(i32::MAX as u128) as i32;
        Box::pin(async move {
            let promise = js_sys::Promise::new(&mut |resolve, _reject| {
                if let Err(error) = set_timeout(&resolve, ms) {
                    // Nothing will ever resolve this promise, so the future
                    // would hang. Resolve it now and let the caller's own
                    // timeout/poll logic proceed instead of stalling.
                    log::error!("[reactor-wasm] {error}");
                    let _ = resolve.call0(&JsValue::null());
                }
            });
            let _ = wasm_bindgen_futures::JsFuture::from(promise).await;
        })
    }

    fn now_ms(&self) -> f64 {
        js_sys::Date::now()
    }
}
