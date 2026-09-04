#!/usr/bin/env bash
#
# Build ReactorFFI.xcframework: libreactor_ffi for every Apple slice the Swift
# SDK ships, plus the header and module map Swift imports it through.
#
# Four Rust targets, which is exactly what reactor-webrtc-sys publishes a
# prebuilt libwebrtc for on Apple platforms, becoming three XCFramework slices:
#
#   macOS      arm64 + x86_64, lipo'd into one slice
#   iOS        arm64 (device)
#   iOS        arm64 (simulator)
#
# Intel Macs are supported and are the x86_64 half of that first slice: a Mac of
# either architecture links the same macOS library. It is also why the macOS
# floor is 13 rather than 11 — see Package.swift.
#
# The one Apple target deliberately absent is x86_64-apple-ios, which is the
# Intel *simulator* and not an Intel Mac: prebuilt_platform() maps it to the
# *arm64* simulator archive, so such a build would link the wrong architecture.
# The simulator slice is arm64 only, and the README says so.
#
# ── Why a static library, and why it does not link here ──────────────────────
#
# Each slice is built with `--crate-type staticlib`, which archives objects and
# links nothing. That is not a shortcut around a problem: an app is what links,
# and the symbols libwebrtc needs from Apple's frameworks are resolved then,
# against the framework list Package.swift declares. Building the cdylib instead
# would link here, in a context no consumer has, and its failures would be about
# this script rather than about the package.
#
# It is also much faster: no link step, and no 22 MB dylib nobody ships.
#
# ── Deployment targets ───────────────────────────────────────────────────────
#
# Set explicitly, matching Package.swift's platforms. rustc otherwise picks a
# floor old enough that the linker warns the prebuilt libwebrtc objects were
# "built for newer iOS version than being linked" — a warning that turns into a
# real mismatch the moment something in the archive uses an API from in between.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT_DIR="${REACTOR_XCFRAMEWORK_OUT:-$REPO_ROOT/target/xcframework}"
STAGING="$OUT_DIR/staging"
# Archives copied out of the per-triple build trees, so a slice survives its
# build tree being pruned (see build_slice).
SLICES="$OUT_DIR/slices"
FRAMEWORK="$OUT_DIR/ReactorFFI.xcframework"
HEADER="$REPO_ROOT/crates/reactor-ffi/include/reactor_ffi.h"

# Matches Package.swift. macOS x86_64 needs 13 because libwebrtc's x86_64 build
# does; arm64 is fine at 11, and one floor for both keeps the lipo'd slice honest
# about what it requires.
export MACOSX_DEPLOYMENT_TARGET="${MACOSX_DEPLOYMENT_TARGET:-13.0}"
export IPHONEOS_DEPLOYMENT_TARGET="${IPHONEOS_DEPLOYMENT_TARGET:-16.0}"

MAC_TARGETS=(aarch64-apple-darwin x86_64-apple-darwin)
IOS_DEVICE_TARGET=aarch64-apple-ios
IOS_SIM_TARGET=aarch64-apple-ios-sim

log() { printf '\n▸ %s\n' "$1"; }

build_slice() {
    local target="$1" keep="${2:-}"
    log "building libreactor_ffi.a for $target"
    # --crate-type staticlib rather than the manifest's [cdylib, staticlib]: see
    # the header comment. `cargo rustc` is what lets a single crate type be
    # selected for one build without editing Cargo.toml.
    (cd "$REPO_ROOT" && cargo rustc -p reactor-ffi --release --target "$target" --crate-type staticlib)

    mkdir -p "$SLICES"
    cp "$REPO_ROOT/target/$target/release/libreactor_ffi.a" "$SLICES/$target.a"

    # Four target triples cost about 13 GB of build tree between them — the iOS
    # device one alone is 8 GB — and a macOS CI runner has roughly 14 GB free.
    # Keeping only the archive that was just copied out puts the peak at one
    # triple instead of four. Off by default, because on a laptop this is a cache
    # somebody wants to keep.
    if [ -n "${REACTOR_XCFRAMEWORK_PRUNE:-}" ] && [ -z "$keep" ]; then
        log "pruning target/$target (REACTOR_XCFRAMEWORK_PRUNE)"
        rm -rf "${REPO_ROOT:?}/target/$target"
    fi
}

slice_path() {
    echo "$SLICES/$1.a"
}

# The frameworks Package.swift declares for one platform: the unconditional
# entries plus the `.when(platforms: [.<platform>])` ones.
#
# Parsed out of the manifest rather than repeated here, because a second copy of
# this list is a second thing to forget. The manifest is where a consumer's link
# gets its flags, so the manifest is the list — and
# `scripts/swift-package-check.sh` reads it from here for the same reason, via
# the `frameworks` subcommand.
platform_frameworks() {
    local platform="$1" line name
    while IFS= read -r line; do
        case "$line" in
        *".linkedFramework("*) ;;
        *) continue ;;
        esac
        name="${line#*.linkedFramework(\"}"
        name="${name%%\"*}"
        # A conditional entry counts only if the condition names this platform.
        case "$line" in
        *".when("*)
            case "$line" in
            *".$platform"*) ;;
            *) continue ;;
            esac
            ;;
        esac
        printf '%s\n' "$name"
    done < "$REPO_ROOT/Package.swift"
}

# Link an iOS dynamic library against exactly the frameworks the manifest
# declares.
#
# A static library links nothing, so nothing here would otherwise notice a
# framework missing from that list — the failure would surface in a consumer's
# app, naming symbols they have never heard of. This is the cheap way to find out
# now: if the list is complete, an iOS cdylib links; if it is not, the linker
# names what is missing.
#
# It found one already. libwebrtc's RTCNetworkMonitor calls nw_path_monitor_*,
# and `Network` is absent from what reactor-webrtc-sys's build.rs emits for iOS,
# so the first iOS link failed with nine undefined `_nw_*` symbols.
verify_ios_link() {
    log "verifying the iOS framework list by linking a dylib against it"

    local flags="" framework
    while IFS= read -r framework; do
        flags="$flags -C link-arg=-framework -C link-arg=$framework"
    done < <(platform_frameworks iOS)
    flags="$flags -C link-arg=-lc++"

    if ! (cd "$REPO_ROOT" &&
        RUSTFLAGS="$flags" cargo rustc -p reactor-ffi --release \
            --target "$IOS_DEVICE_TARGET" --crate-type cdylib); then
        echo "" >&2
        echo "The iOS link failed. Either a framework is missing from Package.swift's" >&2
        echo "linkerSettings — the undefined symbols above say which — or libwebrtc" >&2
        echo "started needing one it did not need before. Add it to the manifest;" >&2
        echo "this check reads the list from there." >&2
        exit 1
    fi
}

main() {
    # `frameworks <platform>` prints the manifest's list for that platform, which
    # is how swift-package-check.sh links its probe without a second copy of it.
    if [ "${1:-}" = "frameworks" ]; then
        platform_frameworks "${2:?usage: build-swift-xcframework.sh frameworks <iOS|macOS>}"
        return 0
    fi

    rm -rf "$STAGING" "$FRAMEWORK" "$SLICES"

    for target in "${MAC_TARGETS[@]}"; do
        build_slice "$target"
    done

    # `keep`, because the check below links a cdylib out of this triple's tree
    # and so has to run before it is pruned. Passed as an argument rather than as
    # an environment prefix: whether such a prefix survives a *function* call is
    # exactly the kind of shell detail that differs between shells.
    build_slice "$IOS_DEVICE_TARGET" keep
    verify_ios_link
    if [ -n "${REACTOR_XCFRAMEWORK_PRUNE:-}" ]; then
        rm -rf "${REPO_ROOT:?}/target/$IOS_DEVICE_TARGET"
    fi

    build_slice "$IOS_SIM_TARGET"

    mkdir -p "$STAGING/include" "$STAGING/macos"

    # The headers every slice shares. The module map's name has to match the
    # SwiftPM target that imports it, or `import CReactorFFI` finds nothing.
    cp "$HEADER" "$STAGING/include/reactor_ffi.h"
    cat > "$STAGING/include/module.modulemap" <<'MODULEMAP'
module CReactorFFI {
    header "reactor_ffi.h"
    export *
}
MODULEMAP

    log "lipo: one macOS slice from arm64 + x86_64"
    lipo -create \
        "$(slice_path "${MAC_TARGETS[0]}")" \
        "$(slice_path "${MAC_TARGETS[1]}")" \
        -output "$STAGING/macos/libreactor_ffi.a"

    log "xcodebuild -create-xcframework"
    xcodebuild -create-xcframework \
        -library "$STAGING/macos/libreactor_ffi.a" -headers "$STAGING/include" \
        -library "$(slice_path "$IOS_DEVICE_TARGET")" -headers "$STAGING/include" \
        -library "$(slice_path "$IOS_SIM_TARGET")" -headers "$STAGING/include" \
        -output "$FRAMEWORK"

    log "what came out"
    # Sizes and architectures, printed rather than assumed. The archive is large
    # — libwebrtc is whole-archived into it — and what reaches an app binary
    # after dead-stripping is a different, smaller number that only a real app
    # can measure. Saying both here keeps the README's claim measured.
    find "$FRAMEWORK" -name 'libreactor_ffi.a' -print0 |
        while IFS= read -r -d '' archive; do
            printf '  %-52s %s\n' \
                "${archive#"$FRAMEWORK"/}" \
                "$(du -h "$archive" | cut -f1) — $(lipo -archs "$archive")"
        done

    printf '\n%s\n' "ReactorFFI.xcframework → $FRAMEWORK"
}

main "$@"
