# Reactor Swift SDK

A Swift client for [Reactor](https://reactor.inc), for macOS and iOS, built on
`libreactor_ffi` — the same Rust core the [Python SDK](../python) and the
[C++ SDK](../cpp) bind.

## Quickstart

```swift
import Reactor

let reactor = try await Reactor(model: "reactor/helios", apiKey: apiKey)

let subscription = try reactor.track("main_video").onFrame { frame in
    // frame.pixels is BGRA, frame.width x frame.height
}

try await reactor.connect()
try await reactor.sendCommand("set_prompt", ["prompt": "a forest at dawn"])
try await reactor.sendCommand("start")
```

`Reactor` is the client — connection, commands, recordings, uploads. Media
lives on `Track`, asked for by name, the way an app that knows its model
does. `reactor.tracks` and its `.withKind`/`.withDirection` filters are for
*discovering* what a session declares, not for everyday use. See
[Examples/](Examples/) for the seven scenarios every Reactor SDK ships.

## Stepped generation

A model that supports it can generate one step at a time instead of streaming.
Ask for it when creating the client, then call `step(state:commands:)`: the
runtime applies the state, then the commands, then every input frame pushed
since the previous step — each as its own call, not as one unit — generates,
and returns what the model produced on each output track. Both arguments are
optional: `step()` runs on the model's current state, which commands sent with
`sendCommand` between steps can change.

```swift
let reactor = try await Reactor(model: "owner/model", apiKey: apiKey, generation: .stepped)

try await reactor.connect()          // generationModeMismatch if the runtime cannot step,
                                     // dataChannelChunkingRequired without chunking

let camera = try reactor.track("camera")
try await camera.publish()
try camera.pushFrame(pixels, width: 640, height: 360)   // buffered for the next step, not sent

let result = try await reactor.step(
    state: ["prompt": "a red fox"],
    commands: [StepCommand("advance", ["frames": 4])])
for frame in try result.track("main_video").frames {
    frame.data      // (height, width, 3) uint8 RGB — frame.shape, frame.dtype
    frame.metadata  // what the sender attached, if anything
    frame.captureTimeUs
}
result.elapsed      // Duration — how long the step's generate() took (or the model reported)
result.error        // String? — set when the step ran but the model reported a failure
for file in result.files {
    file.name; file.contentType; file.size   // bytes: GET /sessions/{id}/steps/{index}
}

let next = try await reactor.step()       // nothing new: run on the current state
```

`Track.pushFrame` keeps its signatures: in a stepped session it buffers the frame
for the next step instead of sending it. The buffer holds at most one
data-channel message's worth of frame bytes, so pushing faster than steps run
drops the oldest frames, across tracks; once the session's tracks are known, a
push to a track the session does not take as input is dropped with a warning.
Audio comes back as planar `(channels, samples)` `int16`. Each output track
carries `rate`, the rate its frames play at as the runtime reported it — frames
per second for video, samples per second for audio, `0` when unspecified. The
result is copied out of the library once and the native copy freed at once, so a
`StepResult` can be kept and passed around freely.

### Encoded video in a step

A step can carry video already encoded as H.264, its bytes as they are: much
smaller than raw pixels, and no base64. The runtime decodes a track's encoded
frames before the step runs, so the model reads RGB frames exactly as if they had
been pushed raw. Give the frames of one step in decode order, starting at a
keyframe — the access units (Annex B) from the last IDR frame to the frame the
step is about:

```swift
let frames = window.map { EncodedFrame($0, width: 1280, height: 720) }
let result = try await reactor.step(media: ["webcam": frames.map(StepMediaFrame.encoded)])

// The same, pushed ahead of the step:
for unit in window {
    try camera.pushEncodedFrame(unit, width: 1280, height: 720)
}
let same = try await reactor.step()
```

`width` and `height` are the frame each one decodes to. `media` takes raw frames
too (`.video(pixels, width:height:)`, or `.video(rgb, width:height:format: .rgb)`,
and `.audio(samples)`), and its frames go after the ones pushed to the same track
since the previous step; they are pushed only once the state, the commands and
every frame are well formed, right before the step takes them, so another step's
frames cannot land in between. RGB frames, pushed or in a step, are converted to
BGRA, opaque: `camera.pushFrame(rgb, format: .rgb, width:, height:)`. `pushEncodedFrame` needs a stepped
client and a published sendonly video track, and refuses an empty frame or a
zero size. A step whose frames do not decode, or that does not start at a
keyframe, throws `ReactorError.stepRefused`, naming the frame in `entry`. A
runtime that predates encoded frames refuses them the same way.

A step that ran but that the model reported as failed returns normally, with
`result.error` saying why. A step the runtime refuses throws
`ReactorError.stepRefused`; one the session cannot take yet throws
`stepNotReady` (neither ran); one whose state key or command handler failed
throws `stepCommandFailed` (the calls before it stay applied, the step did not
run, and the error's `entry` and `commandCode` name the call and its code); one whose session ended before its result was sent throws
`stepSessionEnded` (whether it ran is unknown); one whose output
does not fit in a message throws `stepResultTooLarge`; a request too large to
send throws `messageTooLarge`. A step refused before it goes out (`invalidState`,
`badRequest`) keeps its pushed frames for the next step; past that they belong to
the step whatever its outcome, so one that could not be sent drops them too, and
nothing is resent. `clearStepInputs()` drops what is
buffered now. `stepTimeout:` bounds a step (default 300 s).

`result.files` names the extra files the model kept with the step; it does not
carry them. Fetch their bytes over HTTP from the session's step results,
`GET /sessions/{id}/steps/{index}`, which lists each with a download URL (202
while the step is not stored yet). Files go *into* a step as they go into any
command: upload with `uploadFile(at:)` or `uploadData(_:name:mimeType:)`, then
put the `FileRef` in a command's arguments (`StepCommand(_:arguments:)`) or,
as `JSONValue(fileRef)`, in the state value of a field that takes an upload. See
[`Examples/09_stepped_generation`](Examples/09_stepped_generation/main.swift).

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

**Swift Package Manager only — no CocoaPods.** SPM is Apple's own package
manager, built into Xcode since 2019, with no separate spec repository to
publish to and native support for the one shape this package actually needs:
a prebuilt binary (the XCFramework below) rather than source, since building
`libreactor_ffi` needs a Rust toolchain and a libwebrtc download no consumer
should have to own. CocoaPods remains supported by Apple's tooling but
predates SPM and is the third-party alternative today; this package ships no
`.podspec`.

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

`test:swift` is the fast, hermetic unit suite (`FakeLibrary`, no network) —
`mise run test:swift:integration-tests` and its `:ios-simulator` twin are the
real-FFI, real-model suite, and have their own key and their own README; see
[`IntegrationTests/README.md`](IntegrationTests/README.md).

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

**On iOS the camera follows the device.** A phone's sensor is mounted landscape
and `AVCaptureVideoDataOutput` hands over what it saw, so an upright iPhone would
otherwise push the model a picture lying on its side — well-formed, correctly
sized, and wrong, with nothing downstream able to tell. `Camera` watches the
device's orientation while it captures and asks the capture connection to rotate,
which AVFoundation does in the pipeline rather than costing a rotate per frame.
Face up and face down name no way up, so the last real orientation is kept.

That rotation is also why a padded row stride is now packed rather than refused:
AVFoundation aligns the rows of a rotated buffer, and refusing them would have
traded sideways video for no video at all. Frames whose rows are already
contiguous — every Mac, and a phone held the camera's own way up — still go to
the wire without a copy.

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

**`-ObjC` is not on that list, because it cannot be — and a consumer needs it
anyway.** Add it to your app's own "Other Linker Flags" in Xcode, or it
crashes on launch with `+[UIDevice maxSupportedH264Profile]: unrecognized
selector`. libwebrtc ships an Objective-C++ category the linker silently
drops from a static archive unless something forces every object file in
rather than only the ones another symbol already pulls in — Apple's own
documented answer is `-ObjC` on whatever links it, and `Package.swift` cannot
set that on a consumer's behalf: `unsafeFlags` there would make the whole
package unusable as anyone's dependency. Found on the integration-tests
suite's own iOS Simulator run, not in code review — the crash is on load,
before anything this SDK does runs at all.

## What's still unverified

- **The app-size delta.** Not yet measured. The unlinked static archive is
  roughly 140 MB per slice with libwebrtc whole-archived; what actually
  reaches a shipped binary after dead-stripping is a real number, not this
  one — tracked in
  [REA-5587](https://linear.app/reactor-team/issue/REA-5587), which will
  update this section once the iOS demo app exists to measure it against.
- **visionOS, beyond the compatibility-mode theory above.** Nobody has run
  this package on a Vision Pro yet, simulator or device. Also tracked in
  REA-5587 — this section will name the visionOS version and what was
  actually observed once it has.

## Layout

| Path | |
|---|---|
| `Sources/Reactor/` | the SDK; a consumer writes `import Reactor` |
| `Sources/ReactorMedia/` | camera, microphone, speaker, `ReactorVideoView` — a separate product, see [Two products, and why](#two-products-and-why) |
| `Sources/CReactorFFI/` | the module map over `crates/reactor-ffi/include/reactor_ffi.h` — the C ABI, never a copy of it |
| `Examples/` | the seven scenarios every Reactor SDK ships — parity requirements, not documentation |
| `Tests/ReactorTests/`, `Tests/ReactorMediaTests/` | swift-testing suites, run by `swift test` — `FakeLibrary`, no network |
| `IntegrationTests/` | real FFI, real WebRTC, against a live model — see [`IntegrationTests/README.md`](IntegrationTests/README.md) |
| `.swift-format` | what `lint:swift` enforces |

## Changelog

See [`CHANGELOG.md`](CHANGELOG.md).

## Contributing

See the repo-wide [`CONTRIBUTING.md`](../../CONTRIBUTING.md) for the toolchain,
DCO and commit conventions.
