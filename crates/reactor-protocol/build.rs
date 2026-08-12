//! Generates `reactor_wire.v1` Rust bindings from the vendored `.proto`
//! sources in `proto/` (see `proto/WIRE_VERSION` for the pinned upstream
//! release and `scripts/fetch-wire-protos.sh` for how to bump it).

use std::path::Path;

const PROTOS: &[&str] = &[
    "proto/reactor_wire/v1/common.proto",
    "proto/reactor_wire/v1/model.proto",
    "proto/reactor_wire/v1/platform.proto",
    "proto/reactor_wire/v1/track.proto",
    "proto/reactor_wire/v1/data.proto",
    "proto/reactor_wire/v1/control.proto",
];

fn main() {
    let protoc = protoc_bin_vendored::protoc_bin_path().expect("vendored protoc binary");
    std::env::set_var("PROTOC", protoc);

    prost_build::Config::new()
        .compile_well_known_types()
        .extern_path(".google.protobuf.Struct", "::prost_types::Struct")
        .compile_protos(PROTOS, &[Path::new("proto")])
        .expect("compile reactor_wire.v1 protos");

    for proto in PROTOS {
        println!("cargo:rerun-if-changed={proto}");
    }
}
