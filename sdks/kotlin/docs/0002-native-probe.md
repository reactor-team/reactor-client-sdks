# K02 native bootstrap probe

Status: incomplete, awaiting the upstream Android C++ ABI fix
([REA-6255](https://linear.app/reactor-team/issue/REA-6255)) and negotiated-media proof.
The p7 release resolves the earlier Java namespace mismatch. This PR now supplies
a reproducible JNI lifecycle harness; it is not a complete media-support claim.

## Reproduce

Use the repository's pinned Rust toolchain, an Android SDK in `ANDROID_HOME`,
NDK **29.0.14206865**, and the `aarch64-linux-android` Rust target. Install these
explicitly before building; the script never accepts licenses or installs tools.
The current cross-build host is macOS arm64; Linux x86_64 is supported by the
script's NDK layout selection but has not yet been exercised for this probe.
The macOS NDK's `darwin-x86_64` directory also contains its Apple Silicon tools.
`tar` must support zstd archives, and Python 3 is required.

```sh
sdkmanager 'ndk;29.0.14206865'
rustup target add aarch64-linux-android
mise run build:kotlin:native:host
mise run build:kotlin:native:android
```

The Android command pins API 26, downloads the WebRTC p7 archive with an embedded
SHA256, builds with `--locked`, prints dynamic dependencies, and checks the
resulting ELF and JAR. The preflight passes with the matching p7 JAR.
The build command does not publish or copy the result into any AAR. The artifact checker
can also be run separately:

```sh
python3 scripts/check-kotlin-native.py /path/to/libreactor_ffi.so /path/to/libwebrtc.jar
mise run test:kotlin:native-checks
```

The fixture tests run in CI and cover the actual mismatch, valid matching classes,
wrong architecture, a later misaligned LOAD segment, and truncated ELF headers.
They prove the rejection checks; they do not substitute for loading a real library.

## Original p6 observations (2026-09-11)

- Repository base: main `956eaea`; reactor-webrtc/reactor-webrtc-sys 0.16.0.
- WebRTC archive: `webrtc-7907-a5ddff60-p6/reactor-webrtc-android-arm64-release.tar.zst`.
- Archive SHA256: `a98488292c61e89c279f1d2ff765a260eea2c4c6764e79ce48d731f6fcfccdc2`.
- Host release FFI and Android arm64/API26 cross-build succeeded with NDK 29.
- All three LOAD segments of the Android FFI have `p_align = 0x4000` and
  compatible offsets. DT_NEEDED contains only `liblog.so`, `libdl.so`, `libm.so`,
  and `libc.so`; bundled libc++ is static. No JNI bridge is packaged yet, so this
  is evidence for this FFI file only, not a complete 16 KB package claim.
- The native image embeds `inc.reactor.org.webrtc.WebRtcClassLoader`. The same
  archive's `lib/libwebrtc.jar` contains `org/webrtc/WebRtcClassLoader.class` and
  lacks `inc/reactor/org/webrtc/WebRtcClassLoader.class`. The preflight fails
  before JNI bootstrap attempts to resolve this missing class.
- `reactor-ffi` owns `JNI_OnLoad` and calls `android_init`. Do not add a second
  owner in the Kotlin bridge. Current `android_init_context` ignores its context
  argument, and the 0.16.0 factory path does not install a Java MediaCodec factory.

## p7 lifecycle proof

`Cargo.toml` and `Cargo.lock` select the published reactor-webrtc 0.17.2
release from crates.io, including the Android relative-vtable fix from upstream PR #86. The Android build
pins `webrtc-7907-a5ddff60-p7`, SHA256
`f9fee15aa6ebaee94ef081caead78317e7957a12c0fb54f43685e3920cb77973`.
Both desktop and Android release FFI builds pass. The official p7 JAR contains
the relocated WebRTC and JNI Zero bootstrap classes.

The separate `native-probe` Gradle module builds a diagnostic APK, never a
published SDK artifact. With an arm64 device connected, run:

```sh
mise run test:kotlin:native:android
```

It builds and stages the actual FFI and JAR, compiles the JNI helper against the
canonical C header, checks both ELF files for 16 KB alignment, and runs ten
synthetic-ADM create/destroy cycles. It requires neither a key nor microphone
permission. The FFI owns WebRTC initialization; the helper's no-op `JNI_OnLoad`
prevents Android from invoking the dependency's initializer twice.

**Known upstream failure in 0.17.1:** factory creation crashes in
`WebRtcVoiceEngine + 692`. The archive uses relative C++ vtables; the glue uses
absolute entries. The exact same probe passes when the glue is rebuilt with:

```sh
CXXFLAGS_aarch64_linux_android=-fexperimental-relative-c++-abi-vtables \
  mise run test:kotlin:native:android
```

This is a diagnostic override, not an SDK packaging fix. The default native lifecycle task now uses the corrected commit without any
compiler override. The correction belongs in reactor-webrtc-sys.
Keep this PR in draft until that dependency is available and negotiated media
has been demonstrated. An emulator lifecycle pass does not establish hardware
MediaCodec support, live-session compatibility, or a complete release-platform
matrix. No Android native artifact is published by this PR.
