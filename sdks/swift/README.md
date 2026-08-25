# Reactor Swift SDK

A Swift client for [Reactor](https://reactor.inc), for macOS and iOS, built on
`libreactor_ffi` — the same Rust core the [Python SDK](../python) and the
[C++ SDK](../cpp) bind.

> **Status: under construction.** This directory currently holds the package
> setup and nothing else. The object model arrives over the pull requests
> grouped by the **Swift SDK** milestone in
> [Client SDKs based on reactor-webrtc](https://linear.app/reactor-team/project/client-sdks-based-on-reactor-webrtc-c6286c8b64d0);
> `Reactor`, `Track` and the seven examples are not here yet. The full README —
> platform table, quickstart, the API — lands with the release.

## Where the manifest lives

`Package.swift` is at the **repository root**, not in this directory. SwiftPM
has no support for a package in a subdirectory: a git dependency resolves the
manifest at the root of the repository or not at all. Every target in it points
back here with an explicit `path:`, so the code lives beside the other SDKs and
only the manifest sits up top.

```swift
.package(url: "https://github.com/reactor-team/reactor-client-sdks", from: "1.0.0")
```

That is also why Swift releases are tagged `v<version>` while Python's are
tagged `python-v<version>`: SwiftPM recognises `1.0.0` and `v1.0.0` and nothing
else. The exception is written down in
[`CONTRIBUTING.md`](../../CONTRIBUTING.md).

## Building it from this repo

```bash
mise run build:swift       # swift build
mise run build:swift:ios   # compile the libraries for iOS (arm64 device)
mise run test:swift        # swift test
mise run lint:swift        # swift-format lint --strict
mise run fmt:swift         # swift-format --in-place
```

`build:swift:ios` is not a nicety. `swift build` and `swift test` run for the
host, so on a Mac they never compile a single `#if os(iOS)` branch — and the
audio session, the interruption handling and the capture rotation all live in
one. It compiles the library targets only and links nothing, so it needs no Rust
toolchain and no iOS `libreactor_ffi`; it does need the iPhoneOS SDK, so it skips
with a reason where there is none, exactly as the other tasks do.

Formatting uses the `swift-format` that ships inside the Xcode toolchain, so the
version a contributor runs is the one the CI runner's Xcode provides rather than
a separate pin. Off a machine with a Swift toolchain — a Linux contributor
running `mise run lint` — these tasks skip with a reason instead of failing the
whole aggregate. CI sets `REACTOR_REQUIRE_SWIFT=1`, which turns that skip back
into a failure.

## The native library

The tests link `libreactor_ffi`, so a checkout needs one before `test:swift` can
run:

```bash
mise run build:ffi   # cargo build -p reactor-ffi --release
```

It is found in a fixed order, and the same order applies to every binding:

| | Where | |
|---|---|---|
| 1 | `REACTOR_FFI_LIB` | a path to the library file — the same override the Python SDK reads, so one variable points every binding at one build |
| 2 | `target/release/` in this checkout | what `mise run build:ffi` produces |

A release consumer gets the library from the XCFramework instead; this order is
the development loop. The linker flags are passed on the command line by
`scripts/swift.sh` rather than declared in `Package.swift`, because
`unsafeFlags` in a manifest makes a package unusable as anyone's dependency.

**Rebuild the native library after pulling changes under `crates/`.** A
signature that moved in the FFI but not in your build still links and then
corrupts the stack at the call, which looks like a hang rather than a version
error. `reactor_abi_version()` turns that into a message — the SDK compares it
against the header it was compiled with and refuses to run on a mismatch — but
only if the library on disk is the one you think it is.

## Two products, and why

```swift
import Reactor        // the client, tracks, commands, recording
import ReactorMedia   // camera, microphone, speaker, ReactorVideoView
```

`import Reactor` touches no device. That split is the point: on iOS a camera or
microphone usage description is a permission prompt for every user and a line in
App Store review, and an app that only *receives* video should need neither.
`ReactorMedia` is where AVFoundation lives, and only an app that imports it needs
`NSCameraUsageDescription` / `NSMicrophoneUsageDescription` in its Info.plist.

The same split exists in Python as `reactor_sdk.audio_devices`.

`ReactorMedia` also carries the one design asymmetry worth knowing about. Closing
a **capture** device waits for the callback that is running right now, so a lock
shared with that callback deadlocks — `Camera` and `Microphone` therefore flip a
flag, release its lock, and only then stop the device. A **render** path pulls
instead, so `Speaker` may hold a lock across its own teardown. The symmetric fix
deadlocks, and `CaptureGate` is where that discipline lives so it is testable
without a camera.

**visionOS in compatibility mode has no camera.** `Camera` says so by name rather
than reporting a missing permission, because no permission will ever appear.

## The XCFramework

A release carries `libreactor_ffi` as an XCFramework, which is what a consumer
links against — there is no source distribution, because building the native
library needs a Rust toolchain and a libwebrtc download.

```bash
mise run build:xcframework    # four slices, then create-xcframework
mise run test:swift:package   # consume it, move the tree, build and run again
```

`Package.swift` decides where `libreactor_ffi` comes from, and offers three
answers under one target name — `CReactorFFI`, which is what `import CReactorFFI`
resolves and what the XCFramework's own module map declares, so the SDK's code
cannot tell them apart:

| | |
|---|---|
| `REACTOR_XCFRAMEWORK=<path>` | a binary target over an archive on disk. SwiftPM wants the path relative to the package root, which `target/xcframework/` already is |
| `REACTOR_SWIFT_DEV=1` | the crate's header, linking a cargo build. `scripts/swift.sh` sets it, so every `mise run *:swift` is this |
| neither | the XCFramework a release publishes — the only shape that works for an app |

The third has no URL yet: no release exists, and the checksum of an archive has
to be committed beside the version that produces it. Until then it falls back to
the development shape rather than failing, so a bare `swift build` and Xcode
opening this package keep working. Filling in `releasedFFI` is what makes the
consumer's answer the default.

| Slice | Architectures | Minimum |
|---|---|---|
| macOS | arm64 + x86_64 (one lipo'd slice) | 13.0 — libwebrtc's x86_64 build requires it, and one slice cannot claim two minimums |
| iOS device | arm64 | 16.0 |
| iOS simulator | arm64 | 16.0 |

**The Intel-Mac simulator is not supported.** `reactor-webrtc-sys` maps
`x86_64-apple-ios` to the *arm64* simulator archive, so such a build would link
the wrong architecture.

**visionOS** is reached through compatibility mode, on the iOS device slice —
there is no `xros` prebuilt libwebrtc, and a native visionOS target needs one.

### The frameworks a static library cannot carry

`Package.swift` declares the Apple frameworks libwebrtc needs at final link.
A static library carries none of the flags its own build emitted, so the app
that links this package is where they have to be declared — and a missing one
surfaces as undefined symbols in *the consumer's* build.

`Network` is on that list and is **not** among the flags
`reactor-webrtc-sys`'s `build.rs` emits for iOS: libwebrtc's `RTCNetworkMonitor`
calls `nw_path_monitor_create` and friends. `build:xcframework` links an iOS
dylib against exactly the list in the manifest, so a framework going missing
fails there rather than in someone's app.

## Layout

| Path | |
|---|---|
| `Sources/Reactor/` | the SDK; a consumer writes `import Reactor` |
| `Sources/CReactorFFI/` | the module map over `crates/reactor-ffi/include/reactor_ffi.h` — the C ABI, never a copy of it |
| `Tests/ReactorTests/` | swift-testing suites, run by `swift test` |
| `.swift-format` | what `lint:swift` enforces |

## Contributing

See the repo-wide [`CONTRIBUTING.md`](../../CONTRIBUTING.md) for the toolchain,
DCO and commit conventions.
