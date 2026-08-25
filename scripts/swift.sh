#!/usr/bin/env bash
#
# The Swift SDK's toolchain entry point: format, lint, build, test.
#
# One script with four subcommands rather than four scripts, because all four
# need the answer to the same question first — is there a Swift toolchain on
# this machine? `mise run lint` and `mise run test` are aggregates a contributor
# runs on Linux, where there usually is not one, so a missing toolchain skips
# with a reason rather than failing the whole aggregate. CI runs the Swift job on
# macOS and sets REACTOR_REQUIRE_SWIFT, which turns that skip back into a
# failure: a gate that quietly checks nothing is worse than no gate. The C++
# SDK's clang-tidy step does the same thing for the same reason.
#
# It lives in scripts/ rather than in mise.toml because a task's `run` is handed
# to /bin/sh, which on a Debian runner is dash and refuses `set -o pipefail` —
# a gate written inline would pass on a laptop and die on its first line in CI.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FORMAT_CONFIG="$REPO_ROOT/sdks/swift/.swift-format"

usage() {
    echo "usage: ${0##*/} <format|lint|build|build-ios|test>" >&2
    exit 2
}

# Print the path to a Swift toolchain executable, or nothing if it is absent.
# `command -v` first so a toolchain on PATH wins (a Linux contributor, or a
# swift.org toolchain on macOS); xcrun second, which is where Xcode keeps its
# own copy of swift-format.
find_tool() {
    local name="$1" path
    if path="$(command -v "$name" 2>/dev/null)"; then
        echo "$path"
        return 0
    fi
    if path="$(xcrun --find "$name" 2>/dev/null)"; then
        echo "$path"
        return 0
    fi
    return 1
}

# Resolve a tool, or decide what a missing one means — and leave the path in
# $TOOL rather than on stdout.
#
# Deliberately not `path="$(require_tool swift)"`: a command substitution runs in
# a subshell, so the `exit 0` below would end only that subshell. The caller
# would then take the skip message for a path and fail with status 127 — the
# skip this exists to provide, turned into the failure it exists to prevent. A
# function body is not a subshell, so called plainly, both exits are this
# script's.
resolve_tool() {
    local name="$1"
    if TOOL="$(find_tool "$name")"; then
        return 0
    fi
    if [ -n "${REACTOR_REQUIRE_SWIFT:-}" ]; then
        echo "swift.sh: $name is missing, and REACTOR_REQUIRE_SWIFT is set." >&2
        echo "  Install Xcode (macOS) or a swift.org toolchain, or unset the variable." >&2
        exit 1
    fi
    echo "swift.sh: skipping - no $name on this machine (set REACTOR_REQUIRE_SWIFT to make this a failure)"
    exit 0
}

# The iPhoneOS SDK, or a decision about its absence. Same shape and same reasons
# as resolve_tool above, including why it is not a command substitution.
resolve_ios_sdk() {
    if IOS_SDK="$(xcrun --sdk iphoneos --show-sdk-path 2>/dev/null)" && [ -d "$IOS_SDK" ]; then
        return 0
    fi
    if [ -n "${REACTOR_REQUIRE_SWIFT:-}" ]; then
        echo "swift.sh: no iPhoneOS SDK, and REACTOR_REQUIRE_SWIFT is set." >&2
        echo "  Install Xcode — the Command Line Tools alone carry only the macOS SDK." >&2
        exit 1
    fi
    echo "swift.sh: skipping - no iPhoneOS SDK on this machine (set REACTOR_REQUIRE_SWIFT to make this a failure)"
    exit 0
}

# The iOS triple to build for, with the floor Package.swift declares rather than
# a number repeated here — a manifest that raises its floor must not leave this
# building against the old one.
ios_triple() {
    local major
    major="$(sed -n 's/.*\.iOS(\.v\([0-9][0-9]*\)).*/\1/p' "$REPO_ROOT/Package.swift" | head -1)"
    if [ -z "$major" ]; then
        echo "swift.sh: Package.swift declares no .iOS platform, so there is nothing to build." >&2
        exit 1
    fi
    echo "arm64-apple-ios$major.0"
}

# The targets behind this manifest's library products, read from the manifest for
# the same reason build-swift-xcframework.sh reads its framework list there: a
# second copy is a second thing to forget, and this one grows with the stack.
#
# Libraries only, and targets rather than products: `swift build --product` also
# links the example executables, and an example is a command-line tool that has
# no business being built for a phone.
library_targets() {
    sed -n 's/.*\.library(name: "[^"]*", targets: \["\([^"]*\)"\].*/\1/p' "$REPO_ROOT/Package.swift"
}

# Every tracked or newly added Swift file the SDK owns. --others so a file that
# is new and not yet `git add`ed is covered too: plain `git ls-files` lists only
# tracked files, which is a false green precisely when a file is most likely to
# need the check.
swift_files() {
    git -C "$REPO_ROOT" ls-files --cached --others --exclude-standard \
        'Package.swift' 'sdks/swift/**/*.swift'
}

collect_files() {
    files=()
    while IFS= read -r file; do
        [ -n "$file" ] && files+=("$file")
    done < <(swift_files)
}

case "${1:-}" in
    format)
        resolve_tool swift-format
        formatter="$TOOL"
        collect_files
        [ ${#files[@]} -eq 0 ] && exit 0
        (cd "$REPO_ROOT" && "$formatter" format --in-place --configuration "$FORMAT_CONFIG" "${files[@]}")
        ;;
    lint)
        resolve_tool swift-format
        formatter="$TOOL"
        collect_files
        [ ${#files[@]} -eq 0 ] && exit 0
        # --strict makes a lint finding an error rather than a warning, which is
        # what makes this a gate.
        (cd "$REPO_ROOT" && "$formatter" lint --strict --configuration "$FORMAT_CONFIG" "${files[@]}")
        ;;
    build)
        resolve_tool swift
        swift_bin="$TOOL"
        "$swift_bin" build --package-path "$REPO_ROOT"
        ;;
    build-ios)
        resolve_tool swift
        swift_bin="$TOOL"
        resolve_ios_sdk
        triple="$(ios_triple)"
        # Compiled, not linked: these are library targets, and an app is what
        # links. So this needs no Rust toolchain and no iOS libreactor_ffi, which
        # is what keeps it a cheap check rather than an XCFramework build.
        #
        # It exists because nothing else compiles the `#if os(iOS)` branches at
        # all — the audio session, the interruption handler, the capture
        # rotation. Building only for the host means that code is reviewed and
        # never once seen by a compiler.
        while IFS= read -r target; do
            [ -n "$target" ] || continue
            echo "swift.sh: building $target for $triple"
            "$swift_bin" build --package-path "$REPO_ROOT" \
                --triple "$triple" --sdk "$IOS_SDK" --target "$target"
        done < <(library_targets)
        ;;
    test)
        resolve_tool swift
        swift_bin="$TOOL"
        "$swift_bin" test --package-path "$REPO_ROOT"
        ;;
    *)
        usage
        ;;
esac
