# Changelog

All notable changes to the Reactor Swift SDK are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/);
versioning follows [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Fixed

- A transient WebRTC `disconnected` peer connection state — a brief network
  blip the engine often recovers from on its own within seconds — no longer
  ends the session immediately. It now gets a 30-second grace period to
  recover before being treated as a fatal disconnect; `failed`/`closed` stay
  immediate, since those are genuinely terminal. Previously, any interruption,
  even one that resolved itself a moment later, tore the session down and
  rejected whatever command was in flight.

## [1.0.1] - 2026-09-10

### Fixed

- `Track.pushFrame()` now preserves the PCM capture rate and channel count through
  the shared FFI instead of treating every buffer as 48 kHz mono. Supports
  8, 16, 24, 32, 44.1 and 48 kHz input in mono or stereo, with local WebRTC
  resampling. Arbitrary chunks are assembled into 10 ms blocks; format changes
  and disconnect discard partial blocks (REA-6193).
- Unsupported capture rates and channel counts now raise a bad-request error
  in the SDK before reaching the FFI, instead of returning successfully while
  the native layer drops the audio.

## [1.0.0] - 2026-09-09

Initial release. Distributed as a SwiftPM binary target — an XCFramework
attached to this release, resolved automatically via
`.package(url: "https://github.com/reactor-team/reactor-client-sdks", from: "1.0.0")` —
for macOS (arm64, x86_64) and iOS 16+ (arm64 device and simulator).

### Added

- `Reactor`: connect/disconnect/reconnect/close, with status and error
  events. Commands (`sendCommand`), the request/reply message channel, and
  file/byte uploads.
- `Track` / `TrackList`: publish, unpublish, pause, and resume; receiving
  frames and the end-of-track trailer; `pushFrame` for a sendonly track,
  including its refusal cases (paused, unpublished, wrong direction). One
  `onFrame`/`pushFrame` pair for both media kinds, overloaded for
  `VideoFrame`/`AudioFrame` and for BGRA/`Samples` — `Track.kind` decides
  which overload applies, matching Python and C++.
- Recording: request a clip and download it once ready.
- Auth: exchange an API key for a session JWT.
- `ReactorMedia`, a separate product from `Reactor` so an app that only
  receives video needs no camera/microphone entitlement: `Camera`,
  `Microphone`, `Speaker`, and `ReactorVideoView`. Handles iOS capture
  orientation, the `visionOS` compatibility-mode no-camera case, and the
  capture-vs-render lock-ordering asymmetry documented in
  `sdks/swift/README.md`.
- Seven runnable examples under `Examples/`, matching the other three SDKs'
  capability matrix: connect and receive, upload an image, pause and
  resume, publish a track, multiple connections, record a clip, and frame
  metadata.
- An integration-test suite against a live `reactor/echo` model, run in CI
  both for the development build and, separately, against the exact
  XCFramework a release ships (including on the iOS Simulator).
