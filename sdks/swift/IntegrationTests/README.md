# Swift SDK integration tests

Real `Reactor` clients — real FFI, real WebRTC — against a real model in production
(`reactor/echo` by default — its source lives in the `reactor-models` repo, not this one).
Nothing here is mocked; that's the point. Mirrors
[`sdks/python/integration-tests/`](../../python/integration-tests/) and
[`sdks/cpp/integration-tests/`](../../cpp/integration-tests/) — same env vars, same pacing,
same model, so pointing one suite at a local runtime instead of production reads the same way
as pointing any other.

This is deliberately **not** part of `swift.sh test` (the fast, hermetic `FakeLibrary`-based
unit suite in [`sdks/swift/Tests/`](../Tests/)) — it's a separate target, filtered out of the
default run and filtered in by its own commands, same separation
`sdks/js/integration-tests/` and `sdks/python/integration-tests/` keep from their own unit
suites.

## Running it

```bash
export INTEGRATION_TESTS_REACTOR_API_KEY=rk_...   # a dedicated key, not REACTOR_API_KEY
mise run build:ffi                                # the native library the suite links (dev build)

mise run test:swift:integration-tests             # macOS
```

`REACTOR_LOCAL=1` runs against a coordinator on `localhost:8080` instead, and needs no API key.

### iOS Simulator

```bash
mise run test:swift:integration-tests:ios-simulator
```

The same suite, same source, run through `xcodebuild` against an iOS Simulator destination
instead of a plain `swift test` — a real, hardware-free check that the iOS slice of the
XCFramework actually links and behaves like the macOS slice against a live session. This
target depends on `build:xcframework`, which cross-compiles all four Apple slices (minutes,
~13 GB of build tree) — see that task's own description.

Two things specific to this path, both found the hard way while wiring it up:

- **A Simulator test bundle runs as its own sandboxed process and does not inherit this
  shell's environment.** Neither `INTEGRATION_TESTS_REACTOR_API_KEY=... xcodebuild test` nor
  the commonly-cited `SIMCTL_CHILD_*` prefix reaches it. `scripts/swift.sh`'s
  `integration-tests-ios-simulator` case works around this by building the test bundle first
  (`xcodebuild build-for-testing`), injecting the variable into its own `.xctestrun` file via
  `PlistBuddy`, then running from that file (`xcodebuild test-without-building`) — the one
  mechanism that actually reaches the process.
- **Any binary linking this SDK on iOS Simulator or device needs `-ObjC` in its linker
  flags**, or it crashes on load with `+[UIDevice maxSupportedH264Profile]: unrecognized
  selector` — a libwebrtc Objective-C++ category (`RTCH264ProfileLevelId.mm`) whose defining
  object file the linker drops unless something forces it in. This suite's own test target
  carries `.unsafeFlags(["-Xlinker", "-ObjC"])` in `Package.swift` (safe there: a test target
  is never resolved as a dependency); a real consumer app needs the same flag added to its own
  Xcode project.

## Why `reactor/echo`, not the examples' `reactor/helios`/`xmax/x2`

`Examples/` and REA-5586's own hand-run verification use `reactor/helios` (and `xmax/x2` for
the one scenario that publishes). This suite uses `reactor/echo` instead, deliberately, for two
reasons an automated, CI-gating suite cares about that a hand-run demo does not:

- **Exact, assertable pixel output.** `echo`'s effects (`set_effect`, `set_overlay_image`) are
  deterministic transforms of whatever you push — invert, grayscale, an overlay at a given
  strength — so a test can assert a specific resulting colour. `helios`'s generative output can
  only be sampled and eyeballed.
- **Capacity provisioned for automated load.** `helios` is a shared, capacity-limited model —
  this suite's first draft, built against it, hit `"no available servers to handle the
  request"` under nothing more than its own test runs. `reactor/echo` is what every other SDK's
  integration suite already runs against for the same reason.

`echo` declares one sendonly track, `webcam`, mirrored onto a recvonly `main_video` — so,
unlike `helios`, **nothing arrives on `main_video` until something has been pushed into
`webcam`**. Every scenario here publishes and pumps `webcam` before it expects output.

## Session-creation pacing

`reactor/echo`'s session-creation quota is shared across every suite hitting it — this one,
the other three SDKs' own suites, and anyone else's traffic against the same model. `Fixtures.swift`'s
`SessionPacer` paces this suite's own connects to ~86/min, and `pacedConnect` retries up to
three times with backoff on a rate limit or a transient capacity error from the platform —
belt and suspenders, not the primary defense. A `RATE_LIMITED`/"no available capacity" failure
that survives four attempts is a real, transient platform condition, not this suite's bug —
rerun rather than debug the harness.

## The seven scenarios

Ported from [`Examples/`](../Examples/), one file each, same numbering:

| # | File | What it pins |
|---|---|---|
| 01 | `01_ConnectAndReceiveTests.swift` | `get_status` round-trips with real data (REA-5973); nothing arrives before `webcam` is pushed |
| 02 | `02_UploadImageTests.swift` | An uploaded `FileRef` travels via `uploads:`, not embedded in the command's JSON; `set_overlay_image` visibly takes over the frame |
| 03 | `03_PauseAndResumeTests.swift` | Nothing is delivered while paused (after a short transport-level grace window); resuming restarts delivery |
| 04 | `04_PublishTrackTests.swift` | Pushing before `publish()` is refused; a published, pushed frame reaches `main_video` |
| 05 | `05_MultiConnectionTests.swift` | A session-scoped token cannot adopt a session it did not create — both clients share one minted token; the joiner observes state the creator set before it connected |
| 06 | `06_RecordClipTests.swift` | A clip of generated media downloads to a real fragmented-MP4 file |
| 07 | `07_FrameMetadataTests.swift` | A tag pushed on `webcam` loops back on `main_video`'s `userData` (REA-5972) — `frameID`/`captureTimeUs` are read but not asserted on content, since Python's own suite found `echo`'s values for both unreliable across independent live runs |

## Fixtures

- `Fixtures.swift` — `IntegrationConfig` (env, model, key), `SessionPacer`/`pacedConnect`,
  `withConnectedReactor` (create, connect, run, always disconnect and close), `waitUntil`.
- `MediaFixtures.swift` — `pumpFrames` (push solid-colour frames into a track until cancelled),
  `solidBGRAFrame`, `sineWaveSamples`, `assertDominantColor`.

Depends on `ExampleSupport` (the library the examples share) for `FrameCounter`,
`makeGradientPNG`, and env-reading conveniences — this suite adds only what `ExampleSupport`
doesn't already have: pacing, a connected-client wrapper with guaranteed teardown, and
solid-colour media fixtures.
