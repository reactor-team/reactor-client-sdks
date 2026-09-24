# Reactor Android SDK

A Reactor client for Android, bound to `libreactor_ffi` through a C++ JNI bridge.

Same object model as every other Reactor SDK — a `Reactor`, the `Track`s a model
declares, one flat list of typed errors — spelled in Kotlin, with `StateFlow` for
state and `suspend` for anything that waits.

## Why this is separate from the Java SDK

`inc.reactor:reactor-sdk` is bound through the Foreign Function & Memory API,
which does not exist on Android. It also exposes `java.lang.foreign.MemorySegment`
in its *public* API — `VideoFrame`, `AudioFrame` and `Media` — so an Android
consumer cannot name those types and there is no shared module to extract. This
SDK is an independent binding over the same native core, reached through JNI, with
the same object model spelled in Kotlin.

The Java SDK remains the answer for desktop JVM and desktop Kotlin.

## Requirements

| | |
| --- | --- |
| `minSdk` | **26** |
| `compileSdk` | 36 |
| ABIs | `arm64-v8a` |

`arm64-v8a` only, today. Every Android device shipped in years is arm64, but an
x86_64 slice is what emulators on Intel hosts need — and what would let the
instrumented tests gate a pull request. It waits on reactor-webrtc publishing an
x86_64 Android build (REA-6551).

The AAR is built for **16 KB page sizes** and CI refuses a native library that
is not, because Android 15 devices with 16 KB pages will not load one that is.

## Install

```kotlin
dependencies {
    implementation("inc.reactor:reactor-sdk-android:1.0.0")

    // Optional: microphone and speaker helpers. Left out of the core module on
    // purpose — a library whose audience includes background services must not
    // put a live mic on the wire because a model declared a sendonly track.
    implementation("inc.reactor:reactor-sdk-android-media:1.0.0")
}
```

The SDK declares no permissions of its own. A connecting app needs `INTERNET`;
capturing needs `RECORD_AUDIO` or `CAMERA`, which are yours to request because
only you know when to ask for them.

## Quickstart

```kotlin
// A shipped app mints a short-lived token on its own backend and passes it as `jwt`.
// An API key inside an APK is a key anyone can extract.
val reactor = Reactor(ReactorOptions(jwt = tokenFromYourBackend))

try {
    reactor.connect("reactor/helios")
    reactor.status.first { it == ConnectionStatus.READY }

    reactor.track("main_video").onFrame { frame -> render(frame) }

    reactor.sendCommand("set_prompt", """{"prompt":"a forest at dawn"}""")
    reactor.sendCommand("start")
    // … frames arrive until you stop ingesting them
} finally {
    runCatching { reactor.disconnect() }
    reactor.close()
}
```

Three things that are not obvious and cost an afternoon each:

**`disconnect()` and `close()` are two different things, and you want both.**
`close()` releases the native handle; `disconnect()` ends the session
server-side. A creator that goes away without disconnecting orphans the session,
and the next run cannot start until that lease clears. `use { }` alone gives you
only the first half.

**Nothing arrives until the model's own minimum is met, and that minimum is per
model.** Helios emits no frames until it has both a prompt and a `start`. X2 needs
a prompt and no start. When no frames appear, the model's schema is the first
place to look — `requestSchema()` returns it.

**Model names are `owner/name`.** A bare name resolves under `reactor/`, so it
works by luck of ownership and answers 403 for anybody else's model.

## Frames stay on the FFI thread

Control events — status, errors, session id — are delivered on the dispatcher the
`Reactor` was built with, `Dispatchers.Main.immediate` by default, because an
Android handler usually touches UI.

**One `onFrame` for both kinds.** It is overloaded on the handler's parameter
type — `VideoFrame` or `AudioFrame` — because the object model every Reactor SDK
shares has one frame API and `Track.kind` decides which a handler receives. The
practical consequence is that the parameter usually needs naming:

```kotlin
track.onFrame { frame: VideoFrame -> render(frame) }
```

Kotlin resolves the overload from the declared parameter type, not from what the
body does with it, so `onFrame { frame -> … }` is ambiguous. Passing a function
reference or an already-typed value needs no annotation.

`onFrame` is deliberately the other way from control events: it runs **inline on
the FFI's delivery thread**, and blocking there is the backpressure. The FFI keeps only the newest
video frame while your handler runs, so a slow handler drops frames — bounded, and
visible. Hand them to an unbounded queue instead and you have traded that for
unbounded latency and memory. Convert or encode inline, and post the result.

## Errors

One class per code, each carrying `code`, `message`, `recoverable`, `status`,
`operation` and `retryAfterMs`. **`recoverable` is derived from the code**, never
decided per call site, so no two SDKs can disagree about whether a timeout is
worth retrying.

The same object is what a failed call throws and what the `errors` flow delivers.

This SDK refuses rather than failing quietly. Pushing into a track the session
never declared, or one pointing the other way, or one not yet published, all reach
the native layer, find nothing to do, and return — leaving a loop pushing at 30fps
into nothing. Each of those throws here, naming the fix.

## Examples

Seven numbered scenarios, the same seven every Reactor SDK ships, in
[`examples/`](examples/README.md). That shared numbering is the point: an example
missing from a binding is a code path that binding has never run.

```sh
gradle --project-dir sdks/android :examples:installDebug -PreactorApiKey=rk_…
```

## Development

Everything is driven through mise from the repository root — never a Gradle
wrapper, which this build deliberately does not have:

```sh
mise run setup:android    # install the pinned SDK components (first time, and after a bump)
mise run lint:android
mise run build:android:native   # cross-build libreactor_ffi and stage it with the libwebrtc JAR
mise run build:android
mise run test:android
mise run test:android:asan      # the JNI boundary under AddressSanitizer
```

### Tests that need a device

```sh
mise run setup:android:emulator      # the emulator and a 16 KB page-size system image
mise run test:android:instrumented   # the real libraries load and the ABI matches
mise run test:android:integration-tests   # the real client against a live reactor/echo session
```

The second is the one that reaches the wire. It drives the packaged client through
connection, commands, media in both directions, pause/resume, reconnection, uploads
and a clip download, against a real session — the only place the whole path exists.

Both refuse rather than reporting success for tests that never ran. No device
attached fails before anything is built; a missing
`INTEGRATION_TESTS_REACTOR_API_KEY` fails **on the device** when `CI=true`, and
skips locally, because a contributor should not need production credentials to run
the unit suite.

#### Why they are not a pull-request gate yet

Not by choice. An arm64 emulator needs a hypervisor for the guest, and no
GitHub-hosted runner provides one for arm64: hosted macOS runners are VMs without
Hypervisor.framework, and hosted arm64 Linux runners have no `/dev/kvm` at all.
Both were tried.

The CI job exists and is in `ci-complete`'s `needs`. It runs when the repository
variable **`ANDROID_EMULATOR_RUNNER`** names a runner with a working hypervisor —
a self-hosted arm64 host — and is skipped otherwise. Setting that variable closes
the gate with no change to any workflow. While it is unset, `ci-complete` prints a
warning on every Android run saying so, because a gate that quietly stops being a
gate is worse than one that was never claimed.

The other way to close it is an x86_64 Android ABI, on ordinary hosted runners that
do have KVM — REA-6551, waiting on reactor-webrtc publishing one.

What gates every pull request today: the unit tests, the AddressSanitizer harness
over the JNI boundary, and the native cross-build itself.

### Toolchains, and where each version lives

| What | Pinned in |
| --- | --- |
| JDK, Gradle, CMake, Ninja, Android command-line tools | the repository-root `mise.toml`, locked in `mise.lock` |
| Android platform, build-tools, NDK | [`sdk-packages.txt`](sdk-packages.txt) |
| AGP, Kotlin, Spotless, AndroidX | [`gradle/libs.versions.toml`](gradle/libs.versions.toml) |

The middle row is the one exception to "`mise.toml` is the only place a toolchain
version lives", and it exists because mise cannot express it: the `android-sdk`
plugin installs the command-line tools and nothing else, and the components those
tools fetch are `sdkmanager`'s. `scripts/setup-android-sdk.sh` installs from that
file and then verifies the installed set against it, so the pin is checked rather
than merely written down.

The SDK root is mise's own install directory for the `android-sdk` tool, which
its plugin exports as `ANDROID_HOME` — so Gradle finds the SDK with nothing
configured, and the components land beside the command-line tools that fetched
them, version-scoped by the cmdline-tools pin. That is deliberate: a shared
system SDK is how an unpinned NDK gets used without anyone noticing. There is no
override, and `local.properties` is not read; run the tasks through mise.

### After pulling changes under `crates/`

Rebuild the native library — `mise run build:android:native`. The AAR carries a
compiled `libreactor_ffi.so`, and a stale one links, resolves and then corrupts the
stack at a call that gained a parameter; it does not fail at load. The load-time
ABI check catches the version skew, but only once the library is actually rebuilt.
