# 0001: One Kotlin object model for desktop JVM and Android

Status: accepted for implementation (REA-6219). Base: main 956eaea.

Use plain Kotlin/JVM shared code with small desktop and Android distribution
modules, rather than a separate Kotlin/Native object model. Both consumers run
JVM-compatible bytecode. The public API will expose Reactor, Track, suspending
operations and typed errors. JNI includes reactor_ffi.h; it must never redeclare
the C ABI. Protocol and media transport remain in reactor-core/reactor-ffi.

The build baseline is JDK 17, Gradle 8.11.1, Kotlin 2.2.21, AGP 8.9.2, Android
compile SDK 35/build tools 35.0.0 and minSdk 26. All compiler targets use Java 17.
AGP's runtime JDK and the Android minimum API are independent requirements.
Kotlin 2.2.21 supports this Gradle/AGP combination. The Java distribution is pinned
in the root mise configuration; plugins and test libraries are pinned in the
Gradle version catalog. Gradle's wrapper distribution is checksum-verified.

Native feasibility: main's reactor-webrtc 0.16 supports Android arm64 and already
has JavaVM bootstrap in reactor-ffi. REA-6220 must prove the actual Android load,
application context, matching WebRTC Java archive, MediaCodec and 16 KB alignment.
It will select and pin the NDK/CMake combination supported by those artifacts.
Do not promise x86_64 native Android support on the strength of a Kotlin-only
emulator test. A green scaffold is not native parity.

Ownership design for subsequent PRs: weak public-client references, copied callback
payloads, per-thread JNI attachment, serialized control events, inline media,
exactly-once coroutine settlement, and detached-operation tickets that survive
client destruction. Synthetic ADM is always selected; hardware adapters are optional.

CI scopes Kotlin to its sources/build scripts, the workflow, shared toolchains and
crates. Every added Kotlin job is part of CI Complete. Each later PR adds the
regression tests for its own paths. The final release requires production-model
scenario evidence, live integration gates and clean external binary consumers.

References:
- https://developer.android.com/build/releases/agp-8-9-0-release-notes
- https://kotlinlang.org/docs/gradle-configure-project.html
- https://linear.app/reactor-team/project/kotlin-sdk-64a4e3f8a9ff
