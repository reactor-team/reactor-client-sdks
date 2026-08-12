//! Hand-written Rust bindings for the `reactor_wire` protobuf schema owned by
//! `reactor-runtime` (`proto/reactor_wire/v1/*.proto`), pinned to release
//! `wire/v1.20260722.6`.
//!
//! These structs are written by hand (no `.proto` file, no `prost-build`
//! step) rather than generated, but they mirror exactly what `prost-build`
//! would emit for that schema — same field/oneof/module shape — so that
//! swapping to real codegen later is a near-zero-diff replacement of this
//! module tree, not a rewrite of anything that depends on it.

pub mod struct_convert;
pub mod v1;
