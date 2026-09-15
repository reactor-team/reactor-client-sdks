# K16 — Release and registry verification

The final release step verifies one version across the Kotlin modules, changelog,
Maven metadata and Android AAR. `scripts/kotlin-release-verify.py` rejects a
release candidate without the matching changelog heading, core/desktop/Android
POMs, or the arm64 JNI libraries and notices in the AAR. Run it against the
staged Maven repository before any registry upload.

The release workflow keeps publication behind the existing live integration
gate and does not expose credentials in pull requests. A clean consumer must
resolve desktop artifacts and a minified Android app must resolve the AAR,
`libreactor_ffi.so`, `libreactor_jni.so`, and the relocated WebRTC Java archive.
