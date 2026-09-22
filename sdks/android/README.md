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

## Development

Everything is driven through mise from the repository root — never a Gradle
wrapper, which this build deliberately does not have:

```sh
mise run setup:android    # install the pinned SDK components (first time, and after a bump)
mise run lint:android
mise run build:android
mise run test:android
```

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

The SDK is installed into `~/.local/share/reactor/android-sdk` by default, not
into whatever `ANDROID_HOME` a machine already has — a shared SDK is how an
unpinned NDK gets used without anyone noticing. Override with
`REACTOR_ANDROID_SDK_ROOT` if you need to.

### After pulling changes under `crates/`

Rebuild the native library. The AAR carries a compiled `libreactor_ffi.so`, and a
stale one links, resolves and then corrupts the stack at a call that gained a
parameter — it does not fail at load. (A02 adds the build and the load-time ABI
guard that catches it.)
