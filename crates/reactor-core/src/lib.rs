//! Platform-agnostic core of the Reactor client SDK.
//!
//! This crate holds **all business logic** shared by every Reactor client
//! SDK — connection/session lifecycle, the HTTP-based WebRTC signaling
//! protocol, data/control channel messaging, recording correlation, error
//! semantics and reconnection — so that language SDKs (Python, Swift,
//! Kotlin, Go, ...) become thin shells.
//!
//! Platform concerns are abstracted behind three small traits the host
//! environment implements:
//!
//! * [`http::HttpClient`] — HTTP requests (reqwest on native)
//! * [`runtime::Platform`] — timers and wall-clock time
//! * [`peer::PeerTransport`] — the WebRTC engine (libwebrtc via reactor-webrtc)
//!
//! The host pushes WebRTC engine events into the core via
//! [`reactor::Reactor::handle_peer_event`]; the core pushes state changes,
//! messages and errors back out through [`events::ReactorEvent`] subscriptions.

pub mod backoff;
pub mod control;
pub mod coordinator;
pub mod data;
pub mod error;
pub mod events;
pub mod http;
pub mod messaging;
pub mod peer;
pub mod reactor;
pub mod recording;
pub mod runtime;
pub mod signaling;
pub mod state;

/// Re-export of the wire-protocol crate.
pub use reactor_protocol as protocol;

/// Re-exported so trait implementors use the same `#[async_trait]` macro
/// (with matching `Send` bounds) as the trait definitions.
pub use async_trait::async_trait;

use std::sync::Arc;

// ── Send bounds: on for native hosts, off for wasm ───────────────────────────
//
// A native host runs the core on a work-stealing runtime, so every host future
// must be able to move between threads and the trait objects must be shareable:
// `Send + Sync` throughout.
//
// The browser has neither property and needs neither. `RtcPeerConnection`, a
// `MediaStreamTrack`, a `js_sys::Function` — none of them are `Send`, because a
// JS value belongs to the agent that created it. Requiring `Send` there would
// not make anything safer; it would make the browser transport unwritable
// except through a wrapper that lies about it. wasm is single-threaded, one
// microtask at a time, so the guarantee the bound exists to provide already
// holds structurally.
//
// Hence the split below, which is invisible to native builds: the FFI, and the
// SDKs on top of it, see exactly the bounds they saw before. Host traits pair
// this with `#[cfg_attr(target_family = "wasm", async_trait(?Send))]`, and a
// wasm implementor must use the same `(?Send)` form.

/// Boxed future — `Send` on native hosts, thread-local under wasm.
#[cfg(not(target_family = "wasm"))]
pub type BoxFut<'a, T> = futures::future::BoxFuture<'a, T>;
/// Boxed future — `Send` on native hosts, thread-local under wasm.
#[cfg(target_family = "wasm")]
pub type BoxFut<'a, T> = futures::future::LocalBoxFuture<'a, T>;

#[cfg(not(target_family = "wasm"))]
pub type SharedHttp = Arc<dyn crate::http::HttpClient + Send + Sync>;
#[cfg(not(target_family = "wasm"))]
pub type SharedAuth = Arc<dyn crate::http::AuthProvider + Send + Sync>;
#[cfg(not(target_family = "wasm"))]
pub type SharedPlatform = Arc<dyn crate::runtime::Platform + Send + Sync>;
#[cfg(not(target_family = "wasm"))]
pub type SharedPeer = Arc<dyn crate::peer::PeerTransport + Send + Sync>;

#[cfg(target_family = "wasm")]
pub type SharedHttp = Arc<dyn crate::http::HttpClient>;
#[cfg(target_family = "wasm")]
pub type SharedAuth = Arc<dyn crate::http::AuthProvider>;
#[cfg(target_family = "wasm")]
pub type SharedPlatform = Arc<dyn crate::runtime::Platform>;
#[cfg(target_family = "wasm")]
pub type SharedPeer = Arc<dyn crate::peer::PeerTransport>;

/// SDK type reported in `client_info` when the binding does not override it.
pub const DEFAULT_SDK_TYPE: &str = "rust";
/// Core crate version, reported in `client_info` by default.
pub const CORE_VERSION: &str = env!("CARGO_PKG_VERSION");
