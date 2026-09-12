# Reactor Kotlin SDK

Work in progress: lifecycle, authentication, control events, receiving and sending media
are available for development builds. Published binary packages are still pending. Implementation follows
[the Kotlin SDK project](https://linear.app/reactor-team/project/kotlin-sdk-64a4e3f8a9ff)
as a stack of focused PRs, starting with REA-6219.

## Modules

- `reactor-core`: JVM-compatible shared Kotlin object model (no Android imports).
- `reactor-jvm`: desktop distribution; exposes the shared module transitively.
- `reactor-android`: Android AAR; exposes the same shared module transitively.

The JNI bridge includes the canonical
[C header](../../../crates/reactor-ffi/include/reactor_ffi.h).
Protocol and WebRTC stay in Rust. Device adapters will be separate optional modules.

## Development

Use the root `mise install` toolchain, JDK 17, an Android SDK with platform 35 and
build-tools 35.0.0, and `ANDROID_HOME` pointing to that SDK. Gradle is bootstrapped
by the committed, checksummed 8.11.1 wrapper. Android SDK licenses must be accepted
by the developer installing the SDK. No build step accepts licenses on your behalf.

```sh
mise run lint:kotlin
mise run build:kotlin
mise run test:kotlin
# With an Android device/emulator attached:
mise run test:kotlin:android
```

These commands fail if their required toolchain is missing. Unit tests are separate
from connected Android tests. The scaffold's emulator job is x86_64 and exercises
only Kotlin/Android packaging; it makes no claim about native x86_64 support.
REA-6220 adds the arm64 native bootstrap and device proof.

When any `crates/` code changes, rebuild before testing native code:
`cargo build -p reactor-ffi --release`. The binding will verify the library ABI at
load time. Desktop library discovery will prefer `REACTOR_FFI_LIB`, then packaged
binaries, then a checkout build. Android uses packaged `jniLibs` instead.

## Planned runtime support

| Platform | Architecture | Minimum |
| --- | --- | --- |
| JVM Linux | x86_64, aarch64 | JDK 17, glibc 2.34 |
| JVM macOS | arm64 / x86_64 | JDK 17, macOS 11 / 13 |
| JVM Windows | x86_64 | JDK 17 |
| Android | arm64-v8a | API 26; native proof tracked in REA-6220 |

These are the release targets, not verified release claims. Android x86_64 and
32-bit ABIs require upstream WebRTC artifacts before they can be supported.

## Distribution

Planned Maven group: `inc.reactor`; artifacts `reactor-core`, `reactor-jvm`, and
`reactor-android`. Registry namespace ownership, signing credentials and release
permissions must be verified in REA-6231. Nothing in this scaffold publishes.
Versions start at `0.0.0-SNAPSHOT`; the v1 bump is the final PR in the stack.

Licensed under the repository's [Apache-2.0 license](../../../LICENSE).
See [the architecture decision](docs/0001-jvm-and-android.md).

## Native bootstrap progress

The reproducible Android cross-build and artifact preflight are documented in
[the K02 probe report](docs/0002-native-probe.md). The official p7 JAR now loads; the C++ vtable ABI fix is included in the published
reactor-webrtc 0.17.2 release. The report includes the reproducible
arm64 JNI lifecycle test. These artifacts are not
packaged in the SDK.

The internal [JNI boundary primitives](docs/0003-jni-boundary.md) have JVM and
Android fake-library tests. `test:kotlin` additionally needs a C++17 compiler on
Linux/macOS. Run `mise run test:kotlin:jni:android` for the arm64 device tests.

[Errors and coroutine completions](docs/0004-errors-and-completions.md) define the
shared error hierarchy and internal operation registry used by the client lifecycle.

## Client lifecycle

The shared module now exposes `Reactor`, token-provider authentication, control events,
and suspending shutdown. See [client lifecycle](docs/0005-client-lifecycle.md) for usage,
threading, explicit library loading and JNI verification commands. The stack consumes
reactor-webrtc 0.17.2 from crates.io. Sending media and distribution remain later slices.

## Receiving media

[Tracks and receiving media](docs/0006-receiving-media.md) describes ordered track lists,
inline BGRA/PCM handlers, retained buffers and metadata, pause/resume, and subscription
removal. Rebuild the development JNI library when updating this stack.

Publishing, typed BGRA/PCM pushes, metadata, engine capture times and bitrate controls are
available on the same Track API. See [Sending media](docs/0007-sending-media.md) for the
publication lifecycle, validation and cancellation behavior.

Correlated commands, schemas, unsolicited messages and typed connection statistics are
available; see [Commands and statistics](docs/0008-commands-and-statistics.md).
