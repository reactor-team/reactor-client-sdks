#include "jni_generated.h"
#include "jni_support.hpp"
#include <cstdio>

extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_internal_NativeAbi_checkAbi(JNIEnv* env, jobject) {
  auto actual = reactor_abi_version();
  if (actual != REACTOR_ABI_VERSION) {
    char message[128];
    std::snprintf(message, sizeof(message), "Reactor ABI mismatch: header=%u library=%u; rebuild the native library",
                  REACTOR_ABI_VERSION, actual);
    reactor_jni::fail(env, "java/lang/UnsatisfiedLinkError", message);
  }
}

extern "C" JNIEXPORT jlong JNICALL
Java_inc_reactor_sdk_internal_NativeAbi_timeMicros(JNIEnv*, jobject) {
  static_assert(sizeof(jlong) == sizeof(int64_t));
  return reactor_time_micros();
}

// WebRTC initialization belongs to libreactor_ffi, including on Android.
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) { return JNI_VERSION_1_6; }
