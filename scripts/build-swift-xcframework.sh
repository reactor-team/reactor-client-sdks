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
# `ios-simulator-only` builds and packages just the last of those. It exists
# for test:swift:integration-tests:ios-simulator, the one caller that only
# ever runs xcodebuild against a Simulator destination (see swift.sh's own
# comment on that suite) — building the other two slices for it is a real
# cargo build plus a multi-GB libwebrtc-prebuilt download each, spent on
# output that suite never links against.
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
# ── tvOS: a fifth target, opt-in ─────────────────────────────────────────────
#
#   tvOS       arm64 (device)
#
# No published libwebrtc covers tvOS yet, so this slice is never built unless
# asked for, and the default run above is exactly what it was without it:
#
#   REACTOR_XCFRAMEWORK_TVOS=1   adds the tvOS slice to the full build
#   tvos-only                    builds and packages only the tvOS slice
#
# Its libwebrtc comes from REACTOR_WEBRTC_TVOS_LIB_DIR — a webrtc-build output
# (`lib/libwebrtc.a` + `include/`). reactor-webrtc-sys reads a single
# REACTOR_WEBRTC_LIB_DIR for whichever target it is building, so exporting that
# to a whole run would hand the tvOS archive to the macOS and iOS slices as
# well. Here it is set on the tvOS slice's own cargo invocations only. In
# `tvos-only`, which builds nothing else, plain REACTOR_WEBRTC_LIB_DIR works too;
# in the full build it is refused, for the reason just given. With neither,
# reactor-webrtc-sys looks for a published tvOS prebuilt, and the slice is
# refused unless it actually linked one (see require_libwebrtc).
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
TVOS_DEVICE_TARGET=aarch64-apple-tvos

# Matches Package.swift's `.tvOS(.v17)`, which says why it is 17. Not exported
# here: it is handed to the tvOS slice's own cargo invocations (see
# build_tvos_slice), so the default run sees exactly the environment it always
# did.
TVOS_FLOOR="${TVOS_DEPLOYMENT_TARGET:-17.0}"

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

# Link a device dynamic library (iOS, or tvOS) against exactly the frameworks
# the manifest declares for that platform.
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
verify_link() {
    local platform="$1" target="$2"
    log "verifying the $platform framework list by linking a dylib against it"

    local flags="" framework
    while IFS= read -r framework; do
        flags="$flags -C link-arg=-framework -C link-arg=$framework"
    done < <(platform_frameworks "$platform")
    flags="$flags -C link-arg=-lc++"

    if ! (cd "$REPO_ROOT" &&
        RUSTFLAGS="$flags" cargo rustc -p reactor-ffi --release \
            --target "$target" --crate-type cdylib); then
        echo "" >&2
        echo "The $platform link failed. Either a framework is missing from Package.swift's" >&2
        echo "linkerSettings — the undefined symbols above say which — or libwebrtc" >&2
        echo "started needing one it did not need before. Add it to the manifest;" >&2
        echo "this check reads the list from there." >&2
        exit 1
    fi
}

print_summary() {
    log "what came out"
    # Sizes and architectures, printed rather than assumed. The archive is large
    # — libwebrtc is whole-archived into it — and what reaches an app binary
    # after dead-stripping is a different, smaller number that only a real app
    # can measure. Saying both here keeps the README's claim measured.
    # Every archive, not `-name libreactor_ffi.a`: xcodebuild keeps the filename
    # it was handed, and only the macOS slice arrives under that name — the iOS
    # ones are still called after their triple. So that spelling silently listed
    # one slice of three and read exactly like two missing ones.
    find "$FRAMEWORK" -name '*.a' -print0 |
        while IFS= read -r -d '' archive; do
            printf '  %-52s %s\n' \
                "${archive#"$FRAMEWORK"/}" \
                "$(du -h "$archive" | cut -f1) — $(lipo -archs "$archive")"
        done

    printf '\n%s\n' "ReactorFFI.xcframework → $FRAMEWORK"
}

# The headers every slice shares, staged once regardless of which mode below
# runs. The module map's name has to match the SwiftPM target that imports
# it, or `import CReactorFFI` finds nothing.
stage_headers() {
    mkdir -p "$STAGING/include"
    cp "$HEADER" "$STAGING/include/reactor_ffi.h"
    cat > "$STAGING/include/module.modulemap" <<'MODULEMAP'
module CReactorFFI {
    header "reactor_ffi.h"
    export *
}
MODULEMAP
}

# Only the iOS Simulator slice — see this script's header comment for who
# this is for and why. No lipo (one architecture), no macOS/iOS-device
# builds, no verify_link (that check is about the device slice's own
# framework list, which this mode never produces).
build_ios_simulator_only() {
    build_slice "$IOS_SIM_TARGET"

    log "xcodebuild -create-xcframework (iOS Simulator slice only)"
    xcodebuild -create-xcframework \
        -library "$(slice_path "$IOS_SIM_TARGET")" -headers "$STAGING/include" \
        -output "$FRAMEWORK"

    print_summary
}

# Whether the full build includes the tvOS slice. `tvos-only` does not ask.
tvos_requested() {
    [ "${REACTOR_XCFRAMEWORK_TVOS:-}" = 1 ]
}

# The libwebrtc directory the tvOS slice links, or nothing when it should be
# left to reactor-webrtc-sys's own lookup. `mode` is `only` for tvos-only, the
# one mode in which plain REACTOR_WEBRTC_LIB_DIR cannot reach another slice.
#
# Checked before anything is built, so a mistyped path costs nothing.
tvos_lib_dir() {
    local mode="$1" dir="${REACTOR_WEBRTC_TVOS_LIB_DIR:-}"
    # Refused in the full build even beside REACTOR_WEBRTC_TVOS_LIB_DIR: that one
    # replaces it for the tvOS slice only, and the other slices would still see it.
    if [ "$mode" != only ] && [ -n "${REACTOR_WEBRTC_LIB_DIR:-}" ]; then
        echo "REACTOR_WEBRTC_LIB_DIR is set, and reactor-webrtc-sys would link it into" >&2
        echo "the macOS and iOS slices of this build. Unset it and give the tvOS slice" >&2
        echo "its libwebrtc as REACTOR_WEBRTC_TVOS_LIB_DIR, or build that slice alone" >&2
        echo "with \`build-swift-xcframework.sh tvos-only\`." >&2
        exit 1
    fi
    if [ -z "$dir" ]; then
        dir="${REACTOR_WEBRTC_LIB_DIR:-}"
    fi
    if [ -n "$dir" ] && [ ! -f "$dir/lib/libwebrtc.a" ] && [ ! -f "$dir/libwebrtc.a" ]; then
        echo "No libwebrtc.a in $dir (nor in $dir/lib) for the tvOS slice." >&2
        exit 1
    fi
    # Made absolute, because the check above and the build that follows resolve
    # a relative path from different directories: this script from wherever it
    # was started, reactor-webrtc-sys's build script from that crate's own
    # source directory, which is where cargo runs it.
    if [ -n "$dir" ]; then
        dir="$(cd "$dir" && pwd -P)"
    fi
    printf '%s' "$dir"
}

# reactor-webrtc-sys does not fail when it finds no libwebrtc for a target: it
# warns, builds its Rust API alone, and leaves the failure to the final link.
# A static library has no final link, so the result would be a tvOS slice with
# no WebRTC in it that only a consumer's app would notice. The C++ glue the
# crate compiles is the tell — it is built only beside a real libwebrtc, and
# lands in the archive as cc's `<hash>-reactor_webrtc.o`.
#
# grep without -q: an early exit would SIGPIPE `ar`, and pipefail would then
# report a match as a failure.
require_libwebrtc() {
    local archive="$1"
    if ! ar t "$archive" | grep -- '-reactor_webrtc\.o$' > /dev/null; then
        echo "" >&2
        echo "$archive has no libwebrtc in it: reactor-webrtc-sys found none for" >&2
        echo "$TVOS_DEVICE_TARGET and built its Rust API alone (cargo shows that crate's" >&2
        echo "warning only when it is a path dependency). Point REACTOR_WEBRTC_TVOS_LIB_DIR" >&2
        echo "at a tvOS webrtc-build output." >&2
        exit 1
    fi
}

# The tvOS device slice, its framework-list link check, and the guard above.
#
# A subshell, so that its environment ends with it: TVOS_DEPLOYMENT_TARGET and
# REACTOR_WEBRTC_LIB_DIR reach this triple's cargo invocations and nothing else.
# rustc and cc both read TVOS_DEPLOYMENT_TARGET for a tvOS target.
build_tvos_slice() {
    local lib_dir="$1"
    (
        export TVOS_DEPLOYMENT_TARGET="$TVOS_FLOOR"
        if [ -n "$lib_dir" ]; then
            export REACTOR_WEBRTC_LIB_DIR="$lib_dir"
        fi
        build_slice "$TVOS_DEVICE_TARGET" keep
        require_libwebrtc "$(slice_path "$TVOS_DEVICE_TARGET")"
        verify_link tvOS "$TVOS_DEVICE_TARGET"
    )
    if [ -n "${REACTOR_XCFRAMEWORK_PRUNE:-}" ]; then
        rm -rf "${REPO_ROOT:?}/target/$TVOS_DEVICE_TARGET"
    fi
}

# Only the tvOS device slice: the shape an Apple TV app links, without the
# four triples (and the libwebrtc downloads) it would never use.
build_tvos_only() {
    # Assigned on its own line: `exit` inside $(…) leaves only the subshell, and
    # it is the assignment's status that lets set -e stop the script.
    local lib_dir
    lib_dir="$(tvos_lib_dir only)"
    build_tvos_slice "$lib_dir"

    log "xcodebuild -create-xcframework (tvOS slice only)"
    xcodebuild -create-xcframework \
        -library "$(slice_path "$TVOS_DEVICE_TARGET")" -headers "$STAGING/include" \
        -output "$FRAMEWORK"

    print_summary
}

build_all_slices() {
    # tvOS first when it is asked for: it is the slice most likely to be
    # refused (no libwebrtc, a bad path), and that should cost seconds, not the
    # four builds below. Empty, and expanded to nothing, when it is not asked
    # for — `${a[@]+"${a[@]}"}` because bash 3.2 calls an empty array unbound
    # under `set -u`.
    local tvos_library=() lib_dir
    if tvos_requested; then
        lib_dir="$(tvos_lib_dir all)"
        build_tvos_slice "$lib_dir"
        tvos_library=(-library "$(slice_path "$TVOS_DEVICE_TARGET")" -headers "$STAGING/include")
    fi

    mkdir -p "$STAGING/macos"

    for target in "${MAC_TARGETS[@]}"; do
        build_slice "$target"
    done

    # `keep`, because the check below links a cdylib out of this triple's tree
    # and so has to run before it is pruned. Passed as an argument rather than as
    # an environment prefix: whether such a prefix survives a *function* call is
    # exactly the kind of shell detail that differs between shells.
    build_slice "$IOS_DEVICE_TARGET" keep
    verify_link iOS "$IOS_DEVICE_TARGET"
    if [ -n "${REACTOR_XCFRAMEWORK_PRUNE:-}" ]; then
        rm -rf "${REPO_ROOT:?}/target/$IOS_DEVICE_TARGET"
    fi

    build_slice "$IOS_SIM_TARGET"

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
        ${tvos_library[@]+"${tvos_library[@]}"} \
        -output "$FRAMEWORK"

    print_summary
}

main() {
    # `frameworks <platform>` prints the manifest's list for that platform, which
    # is how swift-package-check.sh links its probe without a second copy of it.
    if [ "${1:-}" = "frameworks" ]; then
        platform_frameworks "${2:?usage: build-swift-xcframework.sh frameworks <iOS|macOS|tvOS>}"
        return 0
    fi

    rm -rf "$STAGING" "$FRAMEWORK" "$SLICES"
    stage_headers

    if [ "${1:-}" = "ios-simulator-only" ]; then
        build_ios_simulator_only
    elif [ "${1:-}" = "tvos-only" ]; then
        build_tvos_only
    else
        build_all_slices
    fi
}

main "$@"
