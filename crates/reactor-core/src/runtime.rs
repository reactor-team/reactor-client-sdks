//! Timer / clock abstraction.
//!
//! The core never spawns tasks or threads; it only needs the host to
//! resolve sleep futures and report wall-clock time, which keeps the same
//! code correct on tokio, single-threaded wasm or an FFI host event loop.

use std::future::Future;
use std::time::Duration;

use futures::future::Either;

use crate::error::CoreError;
use crate::{BoxFut, SharedPlatform};

/// Host-provided timers and clock.
pub trait Platform {
    /// Resolve after `duration`.
    fn sleep(&self, duration: Duration) -> BoxFut<'static, ()>;
    /// Current wall-clock time, Unix epoch milliseconds.
    fn now_ms(&self) -> f64;
}

/// Race `fut` against a platform sleep.
pub async fn timeout<T>(
    platform: &SharedPlatform,
    duration: Duration,
    what: &str,
    fut: impl Future<Output = T>,
) -> Result<T, CoreError> {
    let sleep = platform.sleep(duration);
    futures::pin_mut!(fut);
    futures::pin_mut!(sleep);
    match futures::future::select(fut, sleep).await {
        Either::Left((value, _)) => Ok(value),
        Either::Right(_) => Err(CoreError::Timeout(format!(
            "{what} did not complete within {duration:?}"
        ))),
    }
}

/// [`Platform`] implementation backed by tokio timers (feature `tokio-platform`).
#[cfg(feature = "tokio-platform")]
#[derive(Debug, Clone, Copy, Default)]
pub struct TokioPlatform;

#[cfg(feature = "tokio-platform")]
impl Platform for TokioPlatform {
    fn sleep(&self, duration: Duration) -> BoxFut<'static, ()> {
        Box::pin(tokio::time::sleep(duration))
    }

    fn now_ms(&self) -> f64 {
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs_f64() * 1000.0)
            .unwrap_or(0.0)
    }
}
