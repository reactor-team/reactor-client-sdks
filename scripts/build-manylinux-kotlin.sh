#!/usr/bin/env bash
# Used only inside the manylinux_2_34 build container.
set -euo pipefail
dnf install -y clang lld cmake zstd git java-17-openjdk-devel
curl --proto '=https' --tlsv1.2 -sSf --retry 3 https://sh.rustup.rs \
  | sh -s -- -y --profile minimal --default-toolchain none
# shellcheck source=/dev/null
source "${HOME}/.cargo/env"
export JAVA_HOME
JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
export CXX=clang++
export RUSTFLAGS='-C link-arg=-fuse-ld=lld'
export CARGO_TARGET_X86_64_UNKNOWN_LINUX_GNU_LINKER=clang
export CARGO_TARGET_AARCH64_UNKNOWN_LINUX_GNU_LINKER=clang
cd /io
bash scripts/kotlin-distribution-ci.sh "${1:?Pass linux-x64 or linux-arm64}"
