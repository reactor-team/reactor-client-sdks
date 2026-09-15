# JNI boundary primitives

This slice adds internal ABI and memory/thread primitives, not a usable client.
Client handles, coroutines, typed errors and each operation are added in later
stack slices. The C header remains the source of truth for FFI signatures.

`NativeAbi.checkAbi()` compares the linked library with `REACTOR_ABI_VERSION`
before use; mismatch raises `UnsatisfiedLinkError` naming both versions.
JNI prototypes are generated from compiled Kotlin descriptors using `javap`.
The C++ compiler checks definitions against those generated prototypes.

Text crosses JNI as standard UTF-8 byte arrays. This avoids JNI's modified UTF-8
encoding of supplementary characters. Native input copies reject embedded NUL;
nullable and empty values remain distinct. Owned FFI strings use RAII and
`reactor_free_string`; borrowed/static text only gets copied. Buffers exceeding
JVM array limits are rejected before reading memory.

Callback tickets hold a weak global receiver, cache its method on the registering
thread, obtain a thread-local JNIEnv and detach only threads they attached.
Each delivery bounds local references and copies native data before returning.
Java exceptions are contained at the callback boundary and recorded on the ticket.
The later event dispatcher must report handler errors to the application.
Control dispatch and media routing are deliberately outside this primitive layer.

A ticket can be freed only after `reactor_destroy` returns zero. Nonzero results
retain it permanently; the handle has already been consumed, so retrying destroy
is not safe. Detached operations such as JWT fetch and clip download need their
own completion-owned tickets when those operations are introduced.

## Validation

`mise run test:kotlin` compiles the real C++ JNI entry points against a fake C
library, then runs JVM tests under `-Xcheck:jni`. This requires a C++17 compiler
and JDK 17 on Linux/macOS. No credentials or libwebrtc download are required.
The fake allocates real heap strings and never hands invented handles to Rust.

`mise run test:kotlin:jni:android` cross-compiles the same boundary tests and
runs them on an arm64 Android device. The test library uses static libc++ and
all LOAD segments are checked for 16 KB alignment. The separate K02 lifecycle
probe covers actual reactor-ffi creation; these fake tests isolate JNI behavior.

Coverage: ABI mismatch, 64-bit values, UTF-8 including emoji, nullable/empty
strings, NUL rejection, owned versus borrowed freeing, overflow refusal,
foreign-thread callbacks, copied payloads, handler exceptions, and callback
retention after non-quiescent destruction. Negotiated media remains K02 work;
these tests do not claim codec or live-session validation.

Local evidence: five JNI tests pass on JDK 17 with `-Xcheck:jni` and Android 15
arm64 with `debug.checkjni=1`. An AddressSanitizer build was produced, but this
Mac's loader rejected the sanitizer runtime (`Sanitizer load violates platform
policy`), including outside the sandbox. Sanitizer execution remains unverified.
