#!/usr/bin/env bash
# Run inside quay.io/pypa/musllinux_1_2_<arch>, with the checkout at /io.
# The native dependency must also be musl; auditwheel cannot convert glibc to musl.
set -euo pipefail

ARCH=$(uname -m)
case "$ARCH" in
    x86_64) PLATFORM=linux-musl-x64 ;;
    aarch64) PLATFORM=linux-musl-arm64 ;;
    *) echo "Unsupported musl architecture: $ARCH" >&2; exit 1 ;;
esac
TARGET="${ARCH}-unknown-linux-musl"
POLICY="musllinux_1_2_${ARCH}"
if [ -z "${REACTOR_WEBRTC_LIB_DIR:-}" ]; then
    : "${REACTOR_WEBRTC_TAG:?Set the release containing the musl prebuilts}"
fi

apk add --no-cache curl zstd build-base
# Alpine 3.22's distro clang is too old for the bundled libc++ headers.
# PyPA verifies the pinned static compiler's checksums and configures musl.
manylinux-install-clang -v 22.1.8.1
export PATH="/opt/clang/bin:$PATH"
curl --proto '=https' --tlsv1.2 -sSf --retry 3 https://sh.rustup.rs \
    | sh -s -- -y --profile minimal --default-toolchain none
# shellcheck source=/dev/null
source "$HOME/.cargo/env"
cd /io
rustup target add "$TARGET"

# reactor-webrtc-sys 0.16 supports automatic musl selection. Resolve the asset
# explicitly here to pin the workflow's native release and verify its libc marker.
# A local prebuilt directory also supports validating unpublished native builds.
if [ -z "${REACTOR_WEBRTC_LIB_DIR:-}" ]; then
    URL="https://github.com/reactor-team/reactor-webrtc/releases/download/${REACTOR_WEBRTC_TAG}"
    ASSET="reactor-webrtc-${PLATFORM}-release.tar.zst"
    PREBUILT=$(mktemp -d)
    curl -fsSL --retry 3 "$URL/$ASSET" -o "$PREBUILT/$ASSET"
    curl -fsSL --retry 3 "$URL/$ASSET.sha256" -o "$PREBUILT/$ASSET.sha256"
    (cd "$PREBUILT" && sha256sum -c "$ASSET.sha256")
    tar --use-compress-program=unzstd -xf "$PREBUILT/$ASSET" -C "$PREBUILT"
    export REACTOR_WEBRTC_LIB_DIR="$PREBUILT"
fi
if [ "$(cat "$REACTOR_WEBRTC_LIB_DIR/lib/linux_libc")" != musl ]; then
    echo "The prebuilt must be compiled against musl" >&2
    exit 1
fi

export CXX=clang++
# Rust defaults to static libc for musl. A ctypes-loaded cdylib must instead
# share the Python process's libc. lld links the bundled C++ archives.
export RUSTFLAGS="-C target-feature=-crt-static -C linker=clang -C link-arg=-fuse-ld=lld"
cargo build --locked -p reactor-ffi --release --target "$TARGET"
LIB="/io/target/$TARGET/release/libreactor_ffi.so"

PYTHON=/opt/python/cp310-cp310/bin/python
"$PYTHON" -m pip install --quiet --upgrade pip uv auditwheel
cd /io/sdks/python
# Let auditwheel inspect the ELF and earn the musllinux tag.
unset REACTOR_WHEEL_PLATFORM_TAG
REACTOR_FFI_LIB="$LIB" "$PYTHON" -m uv build --wheel --out-dir /io/dist-unrepaired
"$PYTHON" -m auditwheel show /io/dist-unrepaired/*.whl
"$PYTHON" -m auditwheel repair --plat "$POLICY" --wheel-dir /io/dist \
    /io/dist-unrepaired/*.whl

# Check the actual tag set, including compressed platform tags.
"$PYTHON" - "$POLICY" <<'PY'
import sys
from pathlib import Path
from packaging.utils import parse_wheel_filename

wheels = list(Path('/io/dist').glob('*.whl'))
assert len(wheels) == 1, wheels
_, _, _, tags = parse_wheel_filename(wheels[0].name)
assert {t.platform for t in tags} == {sys.argv[1]}, tags
assert {(t.interpreter, t.abi) for t in tags} == {('py3', 'none')}, tags
PY
