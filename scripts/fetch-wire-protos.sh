#!/usr/bin/env bash
# Fetch the pinned reactor_wire.v1 .proto sources from a reactor-runtime release
# and vendor them into crates/reactor-protocol/proto/.
#
# reactor-runtime is the source of truth for the wire protocol: every schema
# change cuts a CalVer release (tag wire/v<version>) with buf-breaking checks,
# and publishes a *-protos.tar.gz artifact of the raw .proto sources alongside
# its own Python/TypeScript bindings. We vendor those .proto files (committed
# to git, like reactor-runtime vendors them into its own proto/ directory) and
# generate the Rust bindings from them at build time via build.rs — this script
# only needs to run when bumping the pinned version, not on every build.
#
# To adopt a newer protocol version: edit crates/reactor-protocol/proto/WIRE_VERSION,
# then re-run this script.
set -euo pipefail

REPO="reactor-team/reactor-runtime"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROTO_DIR="$ROOT/crates/reactor-protocol/proto"
VERSION="$(cat "$PROTO_DIR/WIRE_VERSION")"
TAG="wire/v${VERSION}"
ASSET="reactor-wire-${VERSION}-protos.tar.gz"

echo "Fetching ${ASSET} from ${REPO}@${TAG}..."
WORKDIR="$(mktemp -d)"
trap 'rm -rf "$WORKDIR"' EXIT

gh release download "$TAG" --repo "$REPO" --pattern "$ASSET" --dir "$WORKDIR" --clobber
tar -xzf "$WORKDIR/$ASSET" -C "$WORKDIR"

rm -rf "$PROTO_DIR/reactor_wire/v1"
mkdir -p "$PROTO_DIR/reactor_wire/v1"
cp "$WORKDIR"/proto/reactor_wire/v1/*.proto "$PROTO_DIR/reactor_wire/v1/"

echo "Vendored $(ls "$PROTO_DIR/reactor_wire/v1" | wc -l | tr -d ' ') .proto files into $PROTO_DIR/reactor_wire/v1/"
