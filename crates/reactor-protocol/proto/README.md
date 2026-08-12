# `reactor_wire.v1` sources

The `.proto` files here are vendored from a `reactor-runtime` release (see
[`WIRE_VERSION`](WIRE_VERSION) for the pinned version) — `reactor-runtime` is
the source of truth for the wire protocol, and cuts a CalVer release
(`wire/v<version>`) with `buf breaking` compatibility checks on every schema
change.

`build.rs` compiles these into Rust bindings at build time via `prost-build`;
the generated code is not committed. `src/wire/v1/mod.rs` re-exports the
generated types through `common`/`data`/`control`/`model`/`platform`/`track`
submodules, one per original `.proto` file, matching `reactor-runtime`'s own
layout.

To adopt a newer protocol version: bump [`WIRE_VERSION`](WIRE_VERSION), then
run `../../../scripts/fetch-wire-protos.sh` from the repo root.
