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

/// Boxed future.
pub type BoxFut<'a, T> = futures::future::BoxFuture<'a, T>;
pub type SharedHttp = Arc<dyn crate::http::HttpClient + Send + Sync>;
pub type SharedAuth = Arc<dyn crate::http::AuthProvider + Send + Sync>;
pub type SharedPlatform = Arc<dyn crate::runtime::Platform + Send + Sync>;
pub type SharedPeer = Arc<dyn crate::peer::PeerTransport + Send + Sync>;

/// SDK type reported in `client_info` when the binding does not override it.
pub const DEFAULT_SDK_TYPE: &str = "rust";
/// Core crate version, reported in `client_info` by default.
pub const CORE_VERSION: &str = env!("CARGO_PKG_VERSION");
