# Binary distribution and publication rehearsal

Status: draft rehearsal; publishing is disabled. The Android R8 consumer currently
exposes a missing-generated-JNI-class defect in the published WebRTC p7 JAR.
[reactor-webrtc PR #87](https://github.com/reactor-team/reactor-webrtc/pull/87)
includes transitive Java dependencies, validates runtime closure and bumps the
native build to p8. Rebuild, publish and consume that archive before accepting
Android distribution. Do not suppress R8 missing-class errors.

## Coordinates and runtime loading

All modules use the single `reactorVersion` in `gradle.properties`, currently a
snapshot. These are planned Maven coordinates; they are not on Maven Central yet:

```kotlin
// Desktop: choose exactly one native runtime for the deployment target.
implementation("inc.reactor:reactor-jvm:$version")
runtimeOnly("inc.reactor:reactor-native-macos-arm64:$version")
// Optional Java Sound/Swing integration:
implementation("inc.reactor:reactor-desktop:$version")

// Android: AAR includes arm64-v8a libraries and matching relocated WebRTC classes.
implementation("inc.reactor:reactor-android:$version")
// Optional camera/image, AudioRecord/AudioTrack and lifecycle integration:
implementation("inc.reactor:reactor-android-media:$version")
```

Call `NativeRuntime.initialize()` before constructing clients or using
`timeMicros()`. Android applications should initialize off the main thread.
Initialization is serialized and idempotent; ABI mismatch or a partially failed
load is terminal until process restart. Android preloads WebRTC's Java class
loader, loads the AAR's FFI/JNI libraries, then checks the ABI.

Desktop resolution is `REACTOR_NATIVE_DIR` (explicit directory containing **both**
libraries), then the matching native artifact on the classpath. The latter is
extracted into a unique private temporary directory and retained for the process
lifetime. Shutdown schedules deletion; Windows may retain mapped DLLs until later
OS cleanup. Unsupported platforms, absent artifacts and invalid binaries raise
`UnsatisfiedLinkError` with the original cause. There is no network download,
implicit checkout lookup, Rust invocation or source-build fallback at runtime.
Developers can explicitly point the override at a rebuilt JNI directory.

| Artifact suffix | Target / minimum runtime |
| --- | --- |
| `macos-arm64` | Apple Silicon, macOS 11+ |
| `macos-x64` | Intel macOS 13+ |
| `linux-x64` | x86_64, glibc 2.34+ |
| `linux-arm64` | AArch64, glibc 2.34+ |
| `windows-x64` | Windows x86_64, JDK 17 |
| Android AAR | arm64-v8a, API 26+, 16 KB aligned native libraries |

These targets must all pass the rehearsal matrix before release. Android x86_64,
Windows arm64 and musl are not supported native Kotlin distributions.

## Rehearse locally

Build the matching native FFI/JNI first (see 0002 and 0003), then stage existing
binaries. Staging never builds sources:

```sh
python3 scripts/kotlin-distribution.py macos-arm64 target/kotlin-jni/host /tmp/kotlin-natives
python3 scripts/kotlin-distribution.py android target/kotlin-jni/android /tmp/kotlin-natives \
  --webrtc-jar target/kotlin-native/prebuilt/lib/libwebrtc.jar
cd sdks/kotlin
./gradlew --no-daemon publishAllPublicationsToStagingRepository \
  -PreactorNativeDirectory=/tmp/kotlin-natives -PreactorNativePlatform=macos-arm64 \
  -PreactorStagingRepository=/tmp/kotlin-maven
cd ../..
python3 scripts/kotlin-distribution-test.py /tmp/kotlin-maven macos-arm64 --android
```

Use an absolute staging repository outside the checkout. The rehearsal moves that
repository, copies a standalone Gradle consumer, temporarily renames the producer's
`target` and module build directories, and restores them in `finally`. Run with
exclusive access to this worktree (no simultaneous build). It leaves external
consumer logs/builds in the printed temporary directory for inspection. Desktop
runs with `-Xcheck:jni`, initializes real natives, exercises create/connect-failure/
close, and tests absent and invalid native overrides in separate processes.
Android builds a signed, minified release APK with no consumer-supplied keep rules
and exercises the same lifecycle on an attached arm64 device. Neither test opens
hardware or production sessions.

The Maven publications use Gradle components: POM and `.module` metadata retain
transitive API dependencies. Sources and a documentation archive accompany JVM and
Android modules; source attachments never provide runtime compilation. Native JARs
carry the two binaries, checksums, notices and sources for debugging. The Android
AAR carries the two `.so` files, WebRTC JAR, notices and consumer R8 rules; it requests
no microphone/camera permissions. The diagnostic `native-probe` is not published.

The desktop workflow builds both Linux architectures inside manylinux_2_34,
verifies symbol floors, checks macOS install names and deployment targets, and
rejects Windows dynamic MSVC runtime dependencies. Run its host path with
`mise run test:kotlin:distribution macos-arm64` (matching platform/toolchain).

## Release prerequisites

`release-kotlin.yml` only rehearses local publication. It has read-only GitHub
permissions, no registry endpoint, no signing credentials and no tag/release job.
A push to main must change an existing version and include a matching changelog
heading to trigger the release rehearsal. Pull requests/manual runs cannot release.
The eventual release must additionally require the live integration job on this
exact commit; that gate belongs to the subsequent integration step of the plan.

Still required before public publication:

- Fixed WebRTC Android JAR, successful minified consumer on arm64, including a
  16 KB device. The current emulator evidence does not cover a physical device.
- Successful five-platform desktop matrix and native license-text collection
  beyond the component identifiers provided by upstream SBOMs.
- Verify `inc.reactor` ownership in the chosen Maven registry, provision its
  environment-scoped credentials and signing key, and validate signatures. These
  were not verified through a registry account in this rehearsal; no secrets were
  read or added. Group naming alone does not prove namespace ownership.
- Required live-model integration gates in CI and release, followed by the final
  version/changelog PR. No environment switch can enable publishing in this PR.

Gradle component publishing follows the [Maven Publish documentation](https://docs.gradle.org/8.11.1/userguide/publishing_maven.html)
and Android's [library publishing guidance](https://developer.android.com/build/publish-library/upload-library).
