// The load-time ABI check, and nothing else.
//
// This is the whole of the JNI bridge in A02: the object model and its ownership primitives
// arrive in A03. What matters here is that a library older than the header it was compiled
// against is refused *at load*, loudly, naming both numbers — rather than linking, resolving, and
// corrupting the stack at the first call that gained a parameter.

#include "jni_support.hpp"

#include <cstdio>

// Not wrapped in an `extern "C"` block: the header carries its own `#ifdef __cplusplus` guard,
// and wrapping it again declares the same functions a second time — which is exactly what
// scripts/check-abi-parity.py refuses, because a redeclaration is a signature nobody checked.
#include "reactor_ffi.h"

extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_android_internal_NativeAbi_checkAbi(JNIEnv* env, jobject) {
  const uint32_t actual = reactor_abi_version();
  if (actual != REACTOR_ABI_VERSION) {
    char message[160];
    std::snprintf(
        message, sizeof(message),
        "Reactor ABI mismatch: this AAR was built against ABI %u, but libreactor_ffi.so reports "
        "%u. Rebuild the native library (mise run build:android:native).",
        static_cast<unsigned>(REACTOR_ABI_VERSION), static_cast<unsigned>(actual));
    reactor_jni::fail(env, "java/lang/UnsatisfiedLinkError", message);
  }
}

extern "C" JNIEXPORT jint JNICALL
Java_inc_reactor_sdk_android_internal_NativeAbi_abiVersion(JNIEnv*, jobject) {
  return static_cast<jint>(reactor_abi_version());
}

namespace reactor_jni {
JavaVM* g_vm = nullptr;
}  // namespace reactor_jni

// JNI_OnLoad, and it is not optional even though it does almost nothing.
//
// libreactor_jni links libreactor_ffi, whose own JNI_OnLoad hands the JavaVM to reactor-webrtc.
// Without a JNI_OnLoad of its own here, Android resolves the *dependency's* symbol again when
// this library loads, and WebRTC aborts the process on `!g_jvm`. WebRTC initialisation stays
// owned by libreactor_ffi alone; this one exists to stop the loader looking further — and to
// keep the JavaVM, which is the only way a callback arriving on an FFI-owned thread can get a
// JNIEnv at all.
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  reactor_jni::g_vm = vm;
  return JNI_VERSION_1_6;
}
