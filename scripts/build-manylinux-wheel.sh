#!/usr/bin/env bash
# Build the Python SDK wheel inside a manylinux container.
#
# Run by .github/workflows/release-python.yml, with the repository mounted at /io.
# Not meant to run on a normal host: it installs packages and a Rust toolchain into
# the container it finds itself in.
#
# Why a container at all: PyPI rejects a bare `linux_x86_64` tag, so a Linux wheel
# needs a manylinux tag — and a tag is a claim about the oldest glibc the binary
# works against. Building on the runner's own Ubuntu would make that claim about
# whatever glibc the runner happens to ship (2.39 today), excluding everything older
# for no reason. Building against AlmaLinux 9's glibc 2.34 is what earns the tag, and
# it matches what reactor-webrtc publishes.
#
# Required environment:
#   MANYLINUX_PLAT  the policy to repair to, e.g. manylinux_2_34_x86_64
#
# Leaves the repaired wheel in /io/dist.
set -euo pipefail

: "${MANYLINUX_PLAT:?MANYLINUX_PLAT must be set (e.g. manylinux_2_34_x86_64)}"

ARCH=$(uname -m)
echo "::group::Container: $(cat /etc/redhat-release 2>/dev/null || echo unknown), ${ARCH}, glibc $(getconf GNU_LIBC_VERSION)"
echo "Repairing to: ${MANYLINUX_PLAT}"
echo "::endgroup::"

# ── Toolchain ────────────────────────────────────────────────────────────────
# clang, because the libwebrtc glue compiles against the archive's bundled
# trunk-libc++ headers and the distro g++ cannot parse them.
#
# lld, because libwebrtc.a may contain LLVM ThinLTO bitcode objects that GNU ld
# rejects outright; lld handles both those and native ELF. Learned the hard way in
# reactor-webrtc, and it applies identically here.
echo "::group::Install clang, lld, zstd"
dnf install -y clang lld zstd
echo "::endgroup::"

echo "::group::Install Rust"
# The pinned toolchain comes from rust-toolchain.toml, which rustup reads on first
# use inside /io — no version is named here, so the container cannot drift from the
# rest of the build.
curl --proto '=https' --tlsv1.2 -sSf --retry 3 https://sh.rustup.rs \
    | sh -s -- -y --profile minimal --default-toolchain none
# shellcheck source=/dev/null
source "${HOME}/.cargo/env"
cd /io
rustc --version
echo "::endgroup::"

export CXX=clang++
export RUSTFLAGS="-C link-arg=-fuse-ld=lld"
if [ "${ARCH}" = "aarch64" ]; then
    export CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_LINKER=clang
else
    export CARGO_TARGET_X86_64_UNKNOWN_LINUX_GNU_LINKER=clang
fi

# ── Native library ───────────────────────────────────────────────────────────
# reactor-webrtc-sys downloads the prebuilt libwebrtc for this triple itself, into
# OUT_DIR under /io/target — inside the mount, so nothing crosses the host boundary.
echo "::group::Build libreactor_ffi"
cargo build -p reactor-ffi --release
LIB=/io/target/release/libreactor_ffi.so
ls -lh "${LIB}"
echo "::endgroup::"

# ── Wheel ────────────────────────────────────────────────────────────────────
# Any of the container's interpreters will do: the wheel is py3-none, because the
# SDK reaches the library through ctypes and links no libpython.
PYTHON=$(echo /opt/python/cp31*-cp31*/bin/python | tr ' ' '\n' | sort | head -1)
echo "::group::Build wheel with ${PYTHON}"
"${PYTHON}" -m pip install --quiet --upgrade pip uv
cd /io/sdks/python
# Deliberately no REACTOR_WHEEL_PLATFORM_TAG: hatchling tags this `linux_<arch>`,
# and auditwheel below replaces that with the policy the binary has actually earned.
# auditwheel is the authority on that, not this script.
REACTOR_FFI_LIB="${LIB}" "${PYTHON}" -m uv build --wheel --out-dir /io/dist-unrepaired
ls -lh /io/dist-unrepaired
echo "::endgroup::"

# ── Repair ───────────────────────────────────────────────────────────────────
# `show` first, and kept in the log: it names the policy the binary qualifies for
# and lists the external libraries that will be vendored in. Worth reading rather
# than trusting — this is where an unexpected dependency surfaces.
# There should be nothing to vendor: TLS is rustls, so no libssl or libcrypto, and
# libwebrtc is linked statically. Anything listed here is a surprise worth chasing —
# a vendored library is a copy this project then owns the patching of.
echo "::group::auditwheel show"
"${PYTHON}" -m pip install --quiet auditwheel
"${PYTHON}" -m auditwheel show /io/dist-unrepaired/*.whl
echo "::endgroup::"

echo "::group::auditwheel repair → ${MANYLINUX_PLAT}"
mkdir -p /io/dist
"${PYTHON}" -m auditwheel repair \
    --plat "${MANYLINUX_PLAT}" \
    --wheel-dir /io/dist \
    /io/dist-unrepaired/*.whl
ls -lh /io/dist
echo "::endgroup::"

# auditwheel renames on repair. Fail loudly rather than shipping a wheel still
# claiming `linux_*`, which PyPI would reject and which would mean the repair
# silently did nothing.
for wheel in /io/dist/*.whl; do
    case "$(basename "${wheel}")" in
    *linux_"${ARCH}"*.whl)
        echo "::error::${wheel} is still tagged linux_${ARCH}; auditwheel did not repair it"
        exit 1
        ;;
    esac
done

echo "Wheel repaired to ${MANYLINUX_PLAT}"
