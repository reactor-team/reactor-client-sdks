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
    /// Milliseconds on a clock that never steps back or jumps, from an
    /// arbitrary start: for timing a duration, which a wall-clock step (NTP)
    /// would corrupt. Only differences between two readings mean anything.
    /// Defaults to [`Platform::now_ms`] for a platform with no such clock.
    fn monotonic_ms(&self) -> f64 {
        self.now_ms()
    }
    /// The system the client runs on, for its stats readings. Every field
    /// defaults to empty: unknown.
    fn info(&self) -> PlatformInfo {
        PlatformInfo::default()
    }
}

/// The system a client runs on, as its [`Platform`] reads it. A field is
/// empty when unknown.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct PlatformInfo {
    /// The operating system, e.g. `"macos"`, `"linux"`, `"windows"`, `"ios"`,
    /// `"android"`, or `"browser"`.
    pub os: String,
    /// The operating system's version, e.g. `"15.1"`.
    pub os_version: String,
    /// The CPU architecture, e.g. `"aarch64"`, `"x86_64"`.
    pub arch: String,
    /// The device's hardware model, e.g. `"iPhone15,2"`, `"Pixel 8"` or
    /// `"MacBookPro18,3"`.
    pub device_model: String,
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
impl TokioPlatform {
    /// The platform, with [`Platform::info`] already read, so no task ever
    /// blocks on the system calls behind it.
    pub fn new() -> Self {
        let platform = Self;
        platform.info();
        platform
    }
}

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

    /// Read once per process, since it cannot change while the process runs:
    /// the first read does blocking system calls, so a host warms it up with
    /// [`TokioPlatform::new`] rather than in a task.
    fn info(&self) -> PlatformInfo {
        static INFO: std::sync::OnceLock<PlatformInfo> = std::sync::OnceLock::new();
        INFO.get_or_init(|| {
            let version = os_info::get().version().to_string();
            PlatformInfo {
                os: std::env::consts::OS.to_string(),
                os_version: if version == "Unknown" {
                    String::new()
                } else {
                    crate::stats::bounded_name(&version)
                },
                arch: std::env::consts::ARCH.to_string(),
                device_model: crate::device::model(),
            }
        })
        .clone()
    }

    fn monotonic_ms(&self) -> f64 {
        static START: std::sync::OnceLock<std::time::Instant> = std::sync::OnceLock::new();
        START
            .get_or_init(std::time::Instant::now)
            .elapsed()
            .as_secs_f64()
            * 1000.0
    }
}
