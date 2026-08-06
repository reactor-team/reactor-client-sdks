//! C ABI shared library for the Reactor client SDK.
//!
//! Exposes `reactor-core` through a stable `extern "C"` surface so Python,
//! Swift, Kotlin, Go, and C++ SDKs can load it via their respective FFI
//! mechanisms without depending on Rust tooling at runtime.
