//! Wire-protocol types for the Reactor real-time platform.
//!
//! This crate is the single source of truth for every JSON shape exchanged
//! between a Reactor client and the platform:
//!
//! * the coordinator HTTP API (session lifecycle, uploads),
//! * the HTTP-based WebRTC signaling protocol (SDP exchange, trickle ICE),
//! * the two-level message envelope carried over the WebRTC data channel,
//! * the control-channel request/response/notification protocol,
//! * the recording (clip) runtime messages.
//!
//! All field names serialize to the exact snake_case wire names used by the
//! existing JS (`@reactor-team/js-sdk`) and Python (`reactor-sdk`) SDKs.
//! The crate is `#![no_std]`-adjacent in spirit: no I/O, no async, no
//! platform dependencies — just `serde` types, so it can be reused by the
//! core, by FFI layers, and by server-side tooling.

pub mod control;
pub mod envelope;
pub mod recording;
pub mod session;
pub mod upload;
pub mod webrtc;

/// Coordinator HTTP API version, sent as [`API_VERSION_HEADER`].
pub const REACTOR_API_VERSION: &str = "1";
/// WebRTC signaling protocol version, sent as [`WEBRTC_VERSION_HEADER`].
pub const REACTOR_WEBRTC_VERSION: &str = "1.0";

/// Header carrying the client's coordinator API version.
pub const API_VERSION_HEADER: &str = "Reactor-API-Version";
/// Header carrying the API versions the client can accept.
pub const API_ACCEPT_VERSION_HEADER: &str = "Reactor-API-Accept-Version";
/// Header carrying the client's WebRTC signaling version.
pub const WEBRTC_VERSION_HEADER: &str = "Reactor-WebRTC-Version";

/// Default maximum size of a serialized data-channel message (negotiated
/// SCTP limit). Enforced client-side before sending.
pub const DEFAULT_MAX_MESSAGE_BYTES: usize = 256 * 1024;

/// Inclusive range of valid client-side WebRTC connection ids.
pub const MIN_CONNECTION_ID: u32 = 1000;
pub const MAX_CONNECTION_ID: u32 = 9999;
