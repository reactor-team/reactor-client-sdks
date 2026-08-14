//! Fetches the pinned `reactor_wire.v1` `.proto` sources from a
//! `reactor-runtime` release at build time (see `proto/WIRE_VERSION` for the
//! pinned release) and generates Rust bindings from them via `prost-build`.
//! Nothing is vendored into this repo — building requires network access
//! the first time a given `WIRE_VERSION` is compiled; results are cached
//! under `OUT_DIR` for subsequent builds.

use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};

const REPO: &str = "reactor-team/reactor-runtime";
const PROTO_FILES: &[&str] = &["common", "model", "platform", "track", "data", "control"];

fn main() {
    let protoc = protoc_bin_vendored::protoc_bin_path().expect("vendored protoc binary");
    std::env::set_var("PROTOC", protoc);

    let version = fs::read_to_string("proto/WIRE_VERSION")
        .expect("read proto/WIRE_VERSION")
        .trim()
        .to_string();

    let out_dir = PathBuf::from(std::env::var("OUT_DIR").expect("OUT_DIR set by cargo"));
    let proto_root = out_dir.join("reactor_wire_protos").join(&version);
    let proto_dir = proto_root.join("reactor_wire/v1");

    let protos: Vec<PathBuf> = PROTO_FILES
        .iter()
        .map(|name| proto_dir.join(format!("{name}.proto")))
        .collect();

    if !protos.iter().all(|p| p.is_file()) {
        fetch_protos(&version, &proto_dir);
    }

    prost_build::Config::new()
        .compile_well_known_types()
        .extern_path(".google.protobuf.Struct", "::prost_types::Struct")
        .compile_protos(&protos, &[proto_root])
        .expect("compile reactor_wire.v1 protos");

    println!("cargo:rerun-if-changed=proto/WIRE_VERSION");
}

/// Downloads `reactor-wire-<version>-protos.tar.gz` from the pinned
/// `reactor-runtime` release tag and extracts the `reactor_wire/v1/*.proto`
/// files it contains into `dest`.
fn fetch_protos(version: &str, dest: &Path) {
    let tag = format!("wire/v{version}");
    let asset = format!("reactor-wire-{version}-protos.tar.gz");
    let url = format!("https://github.com/{REPO}/releases/download/{tag}/{asset}");

    let response = ureq::get(&url)
        .call()
        .unwrap_or_else(|e| panic!("fetch {url}: {e}"));

    let mut body = Vec::new();
    response
        .into_reader()
        .read_to_end(&mut body)
        .expect("read release asset body");

    fs::create_dir_all(dest).expect("create proto cache dir");

    let tar = flate2::read::GzDecoder::new(body.as_slice());
    let mut archive = tar::Archive::new(tar);
    for entry in archive.entries().expect("read tar entries") {
        let mut entry = entry.expect("read tar entry");
        let path = entry.path().expect("entry path").into_owned();
        let is_wanted = path.starts_with("proto/reactor_wire/v1")
            && path.extension().is_some_and(|ext| ext == "proto");
        if !is_wanted {
            continue;
        }
        let name = path.file_name().expect("proto file name");
        let mut contents = Vec::new();
        entry.read_to_end(&mut contents).expect("read proto entry");
        fs::write(dest.join(name), contents).expect("write proto file");
    }

    for name in PROTO_FILES {
        assert!(
            dest.join(format!("{name}.proto")).is_file(),
            "{asset} did not contain reactor_wire/v1/{name}.proto"
        );
    }
}
