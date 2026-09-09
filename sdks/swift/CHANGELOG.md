# Changelog

All notable changes to the Reactor Swift SDK are documented here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/);
versioning follows [Semantic Versioning](https://semver.org/).

## [Unreleased]

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
