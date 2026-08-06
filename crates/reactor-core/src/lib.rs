//! Platform-agnostic Reactor client SDK.
//!
//! All business logic lives here behind two async traits — [`HttpClient`] and
//! [`PeerTransport`] — so the same state machine runs on native (tokio +
//! reactor-webrtc) and, in the future, in the browser (WASM + WebAPIs).
