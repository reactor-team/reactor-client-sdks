// Small helpers shared by the JNI bridge.
//
// A02 needs only enough of this to run the ABI check; the ownership primitives that the rest of
// the binding is built out of — string ownership, callback globals, thread attachment — arrive
// with A03.

#pragma once

#include <jni.h>

namespace reactor_jni {

/// Throw a Java exception of `class_name` with `message`.
///
/// Returns to the caller: a JNI throw is not a C++ throw, it is a pending exception that the JVM
/// raises once the native frame returns. Every caller must return immediately afterwards without
/// calling further JNI functions, which is why this returns void rather than pretending to be
/// something the compiler can enforce.
inline void fail(JNIEnv* env, const char* class_name, const char* message) {
  jclass clazz = env->FindClass(class_name);
  if (clazz != nullptr) {
    env->ThrowNew(clazz, message);
    env->DeleteLocalRef(clazz);
  }
}

}  // namespace reactor_jni
