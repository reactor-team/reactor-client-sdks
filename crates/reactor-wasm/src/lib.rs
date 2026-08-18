//! WebAssembly binding for `reactor-core`.
//!
//! The JS SDK is the one Reactor client that cannot use `reactor-ffi`: there is
//! no FFI in a browser, and the media engine is the browser's own
//! `RTCPeerConnection` rather than libwebrtc. So the browser gets the same deal
//! the native SDKs get — all of the session logic in `reactor-core`, a thin
//! binding on top — with WebAssembly as the binding target.
//!
//! ```text
//!                        JavaScript / TypeScript
//!            ┌────────────────────────────────────────────┐
//!            │  ReactorClient   (#[wasm_bindgen])         │
//!            └───────┬────────────────────────────────────┘
//!                    │ owns
//!      ┌─────────────┼──────────────────┬─────────────────┐
//!      │             │                  │                 │
//!  Arc<Reactor>  Arc<WasmPeerTransport>  Arc<WasmAuthProvider>
//! (reactor-core)   (this crate)            (this crate)
//!      │             │
//!      │  PeerEvent  │  unbounded channel
//!  pump task ◄───────┘
//!      │
//!  handle_peer_event()
//!      │
//!  ReactorEvent stream
//!      │
//!  dispatch task ─────────────► registered JS callbacks
//! ```
//!
//! Both tasks are wasm microtasks (`spawn_local`). wasm is single-threaded, so
//! the core's mutexes are never contended and no `Send` bound is needed — see
//! the `target_family = "wasm"` split in `reactor-core`'s crate root.
//!
//! # Building
//!
//! ```bash
//! mise run build:wasm      # wasm-pack build --target web → crates/reactor-wasm/pkg
//! ```
//!
//! Under a non-wasm target this crate is deliberately empty, so a host-side
//! `cargo check --workspace` neither builds `web-sys` nor fails on the browser
//! APIs. Lint the real thing with `mise run clippy:wasm`.

#[cfg(target_family = "wasm")]
mod auth;
#[cfg(target_family = "wasm")]
mod client;
#[cfg(target_family = "wasm")]
mod http;
#[cfg(target_family = "wasm")]
mod peer;
#[cfg(target_family = "wasm")]
mod platform;
#[cfg(target_family = "wasm")]
mod types;

#[cfg(target_family = "wasm")]
pub use client::ReactorClient;
