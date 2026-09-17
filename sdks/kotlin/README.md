# Reactor Kotlin SDK

Work in progress: the Gradle modules and test runners are in place. This scaffold
is not yet a usable Reactor client or a published package. Implementation follows
[the Kotlin SDK project](https://linear.app/reactor-team/project/kotlin-sdk-64a4e3f8a9ff)
as a stack of focused PRs, starting with REA-6219.

## Modules

- `reactor-core`: JVM-compatible shared Kotlin object model (no Android imports).
- `reactor-jvm`: desktop distribution; exposes the shared module transitively.
- `reactor-android`: Android AAR; exposes the same shared module transitively.

The JNI bridge will include the canonical
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
