#!/usr/bin/env bash
# Build the C++ SDK's Linux install tree inside a manylinux container.
#
# Run by .github/workflows/release-cpp.yml, with the repository mounted at /io.
# Not meant to run on a normal host: it installs packages and a Rust toolchain into
# the container it finds itself in. The Python SDK's Linux wheel is built the same
# way, by scripts/build-manylinux-wheel.sh, and for the same reason.
#
# Why a container at all: the archive's README promises glibc 2.34 — Ubuntu 22.04,
# Debian 12, RHEL 9, Amazon Linux 2023. Building on the runner's own Ubuntu makes
# that promise about whatever glibc the runner happens to ship (2.39 today), so the
# archive fails to load on every distribution the table names. AlmaLinux 9's glibc
# 2.34 is what earns it, and it is what reactor-webrtc publishes against.
#
# One compiler builds both halves here, which is not true on a developer's machine:
# the pinned conda clang++ that `mise run build:ffi` uses brings a sysroot whose
# glibc cannot resolve what the resulting .so needs, so the SDK has to be built with
# the platform compiler afterwards. The container's clang is the platform compiler,
# so the split does not apply.
#
# Optional environment:
#   GLIBC_FLOOR  the oldest glibc the archive may require (default 2.34)
#   HOST_UID     uid to hand /io/stage back to, so the host can move and tar it
#   HOST_GID     gid, likewise
#
# Leaves the install tree in /io/stage, and removes the cargo output it linked
# against — the host relocates and packages what is left.
set -euo pipefail

GLIBC_FLOOR="${GLIBC_FLOOR:-2.34}"

ARCH=$(uname -m)
echo "::group::Container: $(cat /etc/redhat-release 2>/dev/null || echo unknown), ${ARCH}, glibc $(getconf GNU_LIBC_VERSION)"
echo "Archive may require no glibc newer than: ${GLIBC_FLOOR}"
echo "::endgroup::"

# ── Toolchain ────────────────────────────────────────────────────────────────
# clang, because the libwebrtc glue compiles against the archive's bundled
# trunk-libc++ headers and the distro g++ cannot parse them.
#
# lld, because libwebrtc.a may contain LLVM ThinLTO bitcode objects that GNU ld
# rejects outright; lld handles both those and native ELF.
#
# cmake to build the SDK, binutils for the glibc check below, git because
# miniaudio is fetched from its repository rather than a release tarball.
echo "::group::Install clang, lld, cmake, binutils, git"
dnf install -y clang lld zstd cmake binutils git
cmake --version | head -1
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

# ── The native library ───────────────────────────────────────────────────────
# No --target: this is a native build, so the output lands in target/release and
# the paths below say so. reactor-webrtc-sys downloads the prebuilt libwebrtc for
# this triple itself, into OUT_DIR under /io/target — inside the mount, so nothing
# crosses the host boundary.
echo "::group::Build libreactor_ffi"
cargo build -p reactor-ffi --release
FFI=/io/target/release/libreactor_ffi.so
ls -lh "${FFI}"
echo "::endgroup::"

# ── The SDK, and the install tree that becomes the archive ───────────────────
echo "::group::Build and install the SDK"
cmake -S /io/sdks/cpp -B /io/build-manylinux \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX=/io/stage \
    -DREACTOR_FFI_LIB_DIR=/io/target/release \
    -DREACTOR_SDK_BUILD_TESTS=OFF \
    -DREACTOR_SDK_BUILD_EXAMPLES=OFF
cmake --build /io/build-manylinux --parallel "$(nproc)"
cmake --install /io/build-manylinux
echo "::endgroup::"

# ── The floor, checked rather than claimed ───────────────────────────────────
# auditwheel does this for the wheel and refuses a tag the binary has not earned.
# There is no auditwheel for a tar.gz, so the same question is asked directly: what
# is the newest glibc any shipped ELF asks for? A symbol versioned above the floor
# is a load failure on exactly the distributions the README's table promises.
echo "::group::glibc floor"
newest="0.0"
for elf in /io/stage/lib/*.so; do
    [ -e "${elf}" ] || continue
    required=$(objdump -T "${elf}" | grep -oE 'GLIBC_[0-9]+(\.[0-9]+)+' | sed 's/GLIBC_//' | sort -V | tail -1)
    echo "$(basename "${elf}"): requires glibc ${required:-none}"
    if [ -n "${required}" ] && [ "$(printf '%s\n%s\n' "${newest}" "${required}" | sort -V | tail -1)" = "${required}" ]; then
        newest="${required}"
    fi
done

if [ "$(printf '%s\n%s\n' "${GLIBC_FLOOR}" "${newest}" | sort -V | tail -1)" != "${GLIBC_FLOOR}" ]; then
    echo "::error::the archive requires glibc ${newest}, above the ${GLIBC_FLOOR} the README promises"
    exit 1
fi
echo "Highest requirement ${newest}, at or below the ${GLIBC_FLOOR} floor"
echo "::endgroup::"

# ── Hand it back ─────────────────────────────────────────────────────────────
# The cargo output the SDK linked against, gone: the host moves the install tree
# and builds a consumer against it, and that check is only worth anything if the
# library it was linked against is no longer where the build left it.
rm -rf /io/target/release /io/build-manylinux

# Everything here ran as root inside the container. Without this the host cannot
# move or tar what it produced.
if [ -n "${HOST_UID:-}" ]; then
    chown -R "${HOST_UID}:${HOST_GID:-${HOST_UID}}" /io/stage
fi

echo "Install tree ready in /io/stage"
