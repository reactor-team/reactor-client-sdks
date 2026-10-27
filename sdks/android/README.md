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
| ABIs | `arm64-v8a`, `x86_64` |
| Kotlin | 2.1 or newer (the AGP 9 default works) |

`arm64-v8a` is every phone; `x86_64` is every emulator on an x86_64 host, which
is also where CI runs the instrumented and live suites.

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

For development you can pass `apiKey` instead of `jwt`: `connect()` then
exchanges it for a token scoped to what that connect reaches — the model when
creating a session, the session when adopting one — and re-mints when the scope
changes. Production apps mint short-lived tokens on their own backend; a key in
an APK is a key anyone can extract, which is all the exchange around it cannot
change. A `jwt` passed alongside always wins, and `local = true` never
authenticates at all.

Need the exchange without a client — a tool that mints tokens for devices, for
example? `Reactor.fetchJwt(apiUrl, apiKey, options = """{"models":["owner/name"]}""")`
is the same standalone call the Java, Swift and Python SDKs expose. Scope it:
a token minted without options carries everything the key's roles allow.

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

## Stepped generation

A model that supports it can generate one step at a time instead of streaming.
Ask for it in the options, then call `step()`: the runtime applies the state
(each key as the model's `set_<key>` command), then the commands, then every
input frame pushed since the previous step, generates, and the step returns what
the model produced on each output track. The runtime checks the whole request
before it applies any of it, so a refused step changed nothing
(`StepRefusedException.entry` names the part at fault); a step whose call failed
keeps the calls before it (`StepCommandFailedException.entry` and `commandCode`
name the call and the code its handler raised). Every part is optional: `step()`
with no arguments runs on the model's current state, and commands sent with
`sendCommand` outside a step still change the state the next step uses.

```kotlin
val reactor = Reactor(ReactorOptions(jwt = token, generation = Generation.STEPPED))
reactor.connect("owner/model")         // GenerationModeMismatchException if the runtime cannot

val camera = reactor.track("camera")
camera.publish()
camera.pushFrame(bgra, width, height)  // buffered for the next step, not sent

val result =
    reactor.step(
        state = """{"prompt": "a red fox"}""",
        commands = listOf(StepCommand("advance", """{"frames": 4}""")),
    )
for (frame in result.track("main_video").frames) {
    frame.shape                        // [height, width, 3], uint8 RGB
    frame.data()                       // read-only little-endian ByteBuffer
    frame.metadata
    frame.captureTimeUs
}
result.track("main_video").rate        // frames per second (samples per second on audio); 0 if unspecified
result.elapsed                         // seconds the step's generate() took (or the model reported)
result.error?.let { why -> Log.w(TAG, "the model failed the step: $why") }
for (file in result.files) {           // named only; fetch the bytes over HTTP
    Log.i(TAG, "${file.name} ${file.contentType} ${file.size}")
}
```

`state` and a command's `data` are JSON objects as strings, the form
`sendCommand` takes its arguments in.

`Track.pushFrame` keeps its signature: in a stepped session it buffers the frame
for the next step instead of sending it, converted to `(height, width, 3)` RGB.
Audio comes back as `(channels, samples)` `int16`, so
`frame.data().asShortBuffer()` reads the samples. A frame handler on a stepped
session's track is refused, because nothing streams there — the output is in the
result.

A step refused before it goes out (outside a connected stepped session, or with
malformed arguments) keeps the buffered frames for the next step. Past that they
belong to the step whatever its outcome: one that could not be sent,
`MessageTooLargeException` included, drops them too, and nothing is resent.
`clearStepInputs()` drops what is buffered now. The buffer holds at most one
data-channel message's worth of frame bytes, so pushing faster than steps run
drops the oldest frames, across tracks. Once the session's tracks are known, a
push to a track the session does not take as input is dropped with a warning.
`ReactorOptions.stepTimeoutSeconds` bounds how long a step may take; the default
is 300.

The result is copied onto the heap once, when the step completes, and the native
one is freed straight away: a `StepResult` holds no native memory and can be
kept.

`result.files` names the extra files the model kept with the step — empty when
it kept none or the result was not saved. The result does not carry their
bytes: fetch them over HTTP from the session's step results,
`GET /sessions/{id}/steps/{index}`, which lists each file with a download URL
(202 while they are not stored yet).

To pass a file into a step, upload it first (`uploadFile` / `uploadBytes`) and
put the reference in a command's arguments or in the value of an upload-typed
state field, exactly as for a normal command.

## Examples

Numbered scenarios, the same numbers every Reactor SDK ships, in
[`examples/`](examples/README.md). That shared numbering is the point: an example
missing from a binding is a code path that binding has never run.

```sh
gradle --project-dir sdks/android :examples:installDebug -PreactorApiKey=rk_…
```

## Camera, microphone and rendering

The core artifact opens no hardware and declares no permissions. Real devices
live in `reactor-sdk-android-media`, a separate dependency precisely so that
importing the SDK is not a request for a microphone — and one that takes no
dependency of its own, built on the platform's own classes.

| Class | What it is |
| --- | --- |
| `AudioRecordCapture` | A `CaptureDevice` on `AudioRecord`, at the voice-communication source so the platform's echo canceller is engaged |
| `AudioTrackRender` | A `RenderDevice` on `AudioTrack`, blocking writes as backpressure |
| `CameraCapture` | Camera2 to upright BGRA frames, following the device's orientation |
| `VideoRenderer` | A `SurfaceView` that draws frames without tearing |
| `Microphone` / `Speaker` | The lifecycle above a device — start, stop, and the races between them |

End to end, a microphone into a published track:

```kotlin
val mic = Microphone(
    AudioRecordCapture(context),
    targetSampleRate = 48_000,
    deviceSampleRate = AudioRecordCapture.DEFAULT_SAMPLE_RATE,
    channels = 1,
)
val buffer = PcmConversion.directBufferFor(frames = 480)

mic.start { samples ->
    track.pushFrame(
        PcmConversion.toDirectBuffer(samples, buffer),
        samplesPerChannel = samples.size,
        sampleRate = 48_000,
        channels = 1,
    )
}
```

And a camera into one:

```kotlin
val camera = CameraCapture(context, facing = CameraSelection.Facing.FRONT)
camera.start { bgra, width, height -> track.pushFrame(bgra, width, height) }
```

Camera frames arrive upright and unmirrored — what the camera sees, the way the
model should get it. The camera follows which way the phone is held on its own,
so turning the phone turns the stream with nothing to wire up (pass
`followDeviceOrientation = false` to set `camera.deviceRotation` yourself).
Mirror only the local preview, with `VideoRenderer.mirror = true`.

Both callbacks run on the capture thread, and pushing straight from them is the
intent — the buffers are reused, and queueing instead trades a bounded frame
drop for unbounded latency.

**The permissions are yours.** `RECORD_AUDIO` and `CAMERA` are requested by the
app, because only the app knows when to ask. The adapters check before opening,
so a missing grant is a message naming the permission rather than a
`SecurityException` from inside the platform.

**The parts worth knowing are unit-tested without a device.** `AudioRecord`,
`CameraCharacteristics` and friends cannot be constructed on a JVM, so buffer
sizing, resolution choice, rotation and the YUV conversion all live in plain
functions — `AudioFormats`, `CameraSelection`, `YuvToBgra` — where a test can
reach them. Front-camera rotation in particular is otherwise wrong on somebody's
handset for months, because the symptom needs a specific device held a specific
way.

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

#### Where they run in CI

On every Android pull request, on an **x86_64 emulator** with a 16 KB page-size
image on GitHub's hosted x86_64 runners — the one kind of hosted runner with a
hypervisor an emulator can use. The instrumented tests run for every pull
request; the live suite for every one from this repository, since a fork's run
gets no API key. A release does not publish until the live suite has passed
against the exact natives it packages.

What CI does not run is the `arm64-v8a` libraries on an emulator: no hosted
runner can host an arm64 one. They are built from the same sources, and every
pull request cross-compiles them and checks their alignment.

### The endurance suite

```sh
mise run test:android:endurance -- PublishChurnTest
```

Six scenarios in four shapes: a fresh client per cycle, one client churning
operations, two single-operation churns, and two that publish once and stream
for the whole run. Four shapes rather than one broad mix because a leak in one
operation is diluted into "RSS grew a bit, inconclusive" by everything else in
the loop — a real run of `pause-resume-churn` in another binding found a 50%
RSS climb that `publish-churn`, same process, did not show at all.

It gates nothing. It runs for minutes to hours, from `workflow_dispatch`
(*Endurance tests* → `android`) and after a release.

Two things are Android's alone. Reports are written to app-private storage on
the device and pulled off afterwards — `scripts/android-endurance.sh` does
that, whichever way the run ends, because the report from a failed run is the
one worth reading. And there is no attaching a debugger as the suite's parent,
which is how every other binding gets a backtrace off a crashing Rust or
libwebrtc thread: the suite is an app process on a device. The equivalent is
the tombstone `debuggerd` writes, collected into the same
`native-crash-diagnostics.json` the other suites produce.

The trend rules and the report format are unit-tested on the host JVM
(`:endurance-tests:test`), so they are checked on every CI run without a
device, a key or a live session. That is the point of keeping `Trends` and
`Report` free of any SDK import.

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
