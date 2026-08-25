#!/usr/bin/env bash
#
# Prove ReactorFFI.xcframework by consuming it the way a consumer will, then
# moving it somewhere else and doing it again.
#
# Checking an archive in place cannot see a baked-in absolute path: on the
# machine that produced it, every such path still resolves. So this copies the
# XCFramework out of the build tree into a throwaway package, builds and *runs* a
# probe against it, moves the whole tree, and builds and runs again. The C++ SDK's
# package check exists for the same reason and found the same class of bug twice.
#
# Running the probe rather than only building it is the point: a link that
# resolves every symbol is what proves the framework list in Package.swift is
# complete, and an executable that starts is what proves the archive is loadable.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FRAMEWORK="${REACTOR_XCFRAMEWORK:-$REPO_ROOT/target/xcframework/ReactorFFI.xcframework}"

if [ ! -d "$FRAMEWORK" ]; then
    echo "swift-package-check: no XCFramework at $FRAMEWORK." >&2
    echo "  Build one first: mise run build:xcframework" >&2
    exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# The frameworks the manifest declares for macOS, read from the manifest itself
# so this probe cannot drift from what a real consumer links. A consumer gets
# them from Package.swift automatically; a bare probe package has to be told.
link_flags=()
while IFS= read -r framework; do
    link_flags+=(-Xlinker -framework -Xlinker "$framework")
done < <("$REPO_ROOT/scripts/build-swift-xcframework.sh" frameworks macOS)
link_flags+=(-Xlinker -lc++)

build_probe() {
    local root="$1" label="$2"
    printf '\n▸ %s: %s\n' "$label" "$root"
    swift run --package-path "$root" "${link_flags[@]}" Probe
}

FIRST="$WORK/first-location"
mkdir -p "$FIRST/Sources/Probe"
cp -R "$FRAMEWORK" "$FIRST/ReactorFFI.xcframework"

cat > "$FIRST/Package.swift" <<'MANIFEST'
// swift-tools-version: 6.0
import PackageDescription

// A consumer, in the smallest shape there is: a binary target over the
// XCFramework and something that calls into it.
let package = Package(
    name: "probe",
    platforms: [.macOS(.v13)],
    targets: [
        .binaryTarget(name: "CReactorFFI", path: "ReactorFFI.xcframework"),
        .executableTarget(name: "Probe", dependencies: ["CReactorFFI"], path: "Sources/Probe"),
    ]
)
MANIFEST

cat > "$FIRST/Sources/Probe/main.swift" <<'PROBE'
import CReactorFFI

// Calling into the library is the whole test: it forces the link to resolve
// every symbol the archive needs, which is what a missing framework breaks.
let version = reactor_abi_version()
guard version >= 1 else {
    fatalError("reactor_abi_version() answered \(version)")
}
print("probe: linked, ABI \(version)")
PROBE

build_probe "$FIRST" "building a consumer where the archive was unpacked"

# The move. Everything SwiftPM cached about the first location is now wrong, and
# anything the archive baked in about it is now a dangling path.
MOVED="$WORK/somewhere-else"
mv "$FIRST" "$MOVED"
rm -rf "$MOVED/.build"

build_probe "$MOVED" "building it again after moving the tree"

printf '\n%s\n' "swift-package-check: the XCFramework survives relocation."
