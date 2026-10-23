# Changelog

All notable changes to the Reactor Android SDK are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

`release-android.yml` refuses to publish a version bump that has no matching
`## [x.y.z]` heading below, so the entry is written by hand in the same pull
request as the bump.

## [Unreleased]

### Added

- Stepped generation, as the other SDKs have it. `ReactorOptions(generation =
  Generation.STEPPED)` asks for a session that generates one step at a time,
  and `reactor.step(state, commands)` runs one and returns a `StepResult`: per
  output track, the frames it produced as arrays (`StepFrame`: `dtype`,
  `shape`, little-endian `data()`, metadata and capture time) and the track's
  `rate`; `elapsed`; the `files` the model kept with the step; and `error`,
  set when the step ran but the model reported it failed — such a step
  returns, it does not throw. `state` is a JSON object as a string, and
  `StepCommand(name, data)` one command. In a stepped session
  `Track.pushFrame` buffers into the next step instead of sending, and
  `clearStepInputs()` drops what is buffered; `onFrame` is refused, since
  nothing streams. `ReactorOptions.stepTimeoutSeconds` bounds a step.
  `connect()` fails with `GenerationModeMismatchException` against a runtime
  that does not start the session stepped, and with
  `DataChannelChunkingRequiredException` when the data channel did not
  negotiate chunking. A step that does not run throws `StepRefusedException`,
  `StepNotReadyException` or `StepCommandFailedException`, and
  `ReactorException.entry` and `commandCode` say which part of the request
  failed and why.

- An `x86_64` ABI beside `arm64-v8a`, so the SDK runs on emulators on x86_64
  hosts.
- Published to Maven Central as `inc.reactor:reactor-sdk-android` and
  `inc.reactor:reactor-sdk-android-media`, with sources and javadoc, signed.
- `Reactor.SDK_VERSION` is the AAR's own version, which is what the
  coordinator records as `client_info.sdk_version`.
- `Reactor.fetchJwt(apiUrl, apiKey, options, local)`, the standalone key→token
  exchange Java, Swift and Python already expose. Scope tokens with
  `options = """{"models":["owner/name"]}"""` before handing them to a client
  you do not control; an unrecognised option key is an error, a rejected key
  reports UNAUTHORIZED, and an unreachable coordinator NETWORK_ERROR.

### Fixed

- `kotlinx-coroutines-android` is an `api` dependency. The public surface is
  `StateFlow` and `suspend`, and as an implementation dependency an app that
  did not already depend on coroutines could not compile against it.
- `ReactorOptions.apiKey` no longer connects with no credentials. The option was
  accepted but never read, so an app holding only a key connected without a
  token and the platform answered 401 "Missing Authorization header". `connect()`
  now does the key→token exchange the other SDKs do: minting a token scoped to
  what the connect reaches (the model when creating a session, the session when
  adopting one), re-minting when the scope changes, and rebuilding the native
  client around the new token. A caller-supplied `jwt` still wins, and `local`
  still never authenticates. `downloadClip()` likewise passes the resolved
  token, not the (possibly absent) options one.
