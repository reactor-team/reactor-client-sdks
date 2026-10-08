# Reactor Android SDK

A Reactor client for Android, bound to `libreactor_ffi` through a C++ JNI bridge.

> **Status: scaffold.** The Gradle build, the toolchain pins and the CI job are in
> place; the binding itself is not. See the Linear project *Android SDK*
> (P-REA-89) for the stack that fills it in.

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

## Camera, microphone and rendering

The core artifact opens no hardware and declares no permissions. Real devices
live in `reactor-sdk-android-media`, a separate dependency precisely so that
importing the SDK is not a request for a microphone — and one that takes no
dependency of its own, built on the platform's own classes.

| Class | What it is |
| --- | --- |
| `AudioRecordCapture` | A `CaptureDevice` on `AudioRecord`, at the voice-communication source so the platform's echo canceller is engaged |
| `AudioTrackRender` | A `RenderDevice` on `AudioTrack`, blocking writes as backpressure |
| `CameraCapture` | Camera2 to BGRA frames, through `YuvToBgra` |
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
```

### Instrumented tests

```sh
mise run setup:android:emulator   # the emulator and a 16 KB page-size system image
mise run test:android:instrumented
```

These run against the real libraries on a real Android image, and they are **not** a
pull-request gate — not by choice. An arm64 emulator needs a hypervisor for the
guest, and no GitHub-hosted runner provides one for arm64: hosted macOS runners are
VMs without Hypervisor.framework, and hosted arm64 Linux runners have no `/dev/kvm`
at all. Both were tried. An x86_64 Android ABI would restore the gate, because
hosted x86_64 Linux runners do have KVM; that waits on reactor-webrtc publishing
one (REA-6551).

Until then they run locally — which works, on an arm64 machine — and on demand
through the `Android SDK instrumented tests` workflow against a runner with a
working hypervisor.

What does gate every pull request: the unit tests, the AddressSanitizer harness over
the JNI boundary, and the native cross-build itself.

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

Rebuild the native library. The AAR carries a compiled `libreactor_ffi.so`, and a
stale one links, resolves and then corrupts the stack at a call that gained a
parameter — it does not fail at load. (A02 adds the build and the load-time ABI
guard that catches it.)
