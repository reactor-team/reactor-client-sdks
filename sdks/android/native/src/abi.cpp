// The load-time ABI check, and nothing else.
//
// This is the whole of the JNI bridge in A02: the object model and its ownership primitives
// arrive in A03. What matters here is that a library older than the header it was compiled
// against is refused *at load*, loudly, naming both numbers — rather than linking, resolving, and
// corrupting the stack at the first call that gained a parameter.

#include "jni_support.hpp"

#include <cstdio>

extern "C" {
#include "reactor_ffi.h"
}

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

// A no-op JNI_OnLoad, and it is not optional.
//
// libreactor_jni links libreactor_ffi, whose own JNI_OnLoad hands the JavaVM to reactor-webrtc.
// Without a JNI_OnLoad of its own here, Android resolves the *dependency's* symbol again when
// this library loads, and WebRTC aborts the process on `!g_jvm`. WebRTC initialisation stays
// owned by libreactor_ffi alone; this one exists only to stop the loader looking further.
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) { return JNI_VERSION_1_6; }
