# `reactor_wire.v1` sources

There are no `.proto` files committed here — `reactor-runtime` is the source
of truth for the wire protocol, and cuts a CalVer release (`wire/v<version>`)
with `buf breaking` compatibility checks on every schema change. `build.rs`
downloads the pinned release's `.proto` sources at build time (see
[`WIRE_VERSION`](WIRE_VERSION)) and compiles them into Rust bindings via
`prost-build`; neither the `.proto` sources nor the generated code are
committed.

`src/wire/v1/mod.rs` re-exports the generated types through
`common`/`data`/`control`/`model`/`platform`/`track` submodules, one per
original `.proto` file, matching `reactor-runtime`'s own layout.

To adopt a newer protocol version: bump [`WIRE_VERSION`](WIRE_VERSION) and
rebuild — `build.rs` fetches the new version's sources automatically.
Building requires network access to `github.com` the first time a given
version is compiled; results are then cached under `OUT_DIR`.

To build against an unreleased wire-protocol change, point
`REACTOR_RUNTIME_PROTO_DIR` at a local `reactor-runtime` checkout's `proto`
directory (the one containing `reactor_wire/v1/*.proto`). `build.rs` then
compiles those files instead of fetching the pinned release, and prints a
cargo warning so the override is never silent. Leave it unset for anything
you commit or release.
