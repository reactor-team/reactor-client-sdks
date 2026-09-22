// The primitives the whole bridge is built out of: thread attachment, string ownership, and the
// global reference that outlives teardown.
//
// Everything here exists because of a specific way the boundary kills a process rather than
// failing a call. The comments say which.

#pragma once

#include <jni.h>

#include <cstring>
#include <string>

// Not wrapped in an `extern "C"` block: the header carries its own `#ifdef __cplusplus` guard,
// and wrapping it again declares the same functions a second time — which is exactly what
// scripts/check-abi-parity.py refuses, because a redeclaration is a signature nobody checked.
#include "reactor_ffi.h"

namespace reactor_jni {

/// The process JavaVM, captured in JNI_OnLoad.
///
/// Callbacks arrive on threads the FFI owns, which have no JNIEnv of their own. A JNIEnv is
/// per-thread and must never be cached across threads; the JavaVM is per-process and is the only
/// thing that can hand out an env on a thread we did not create.
extern JavaVM* g_vm;

/// Throw a Java exception. The caller must return immediately afterwards without making further
/// JNI calls: a JNI throw sets a pending exception rather than unwinding, and calling on with one
/// pending is undefined.
inline void fail(JNIEnv* env, const char* class_name, const char* message) {
  jclass clazz = env->FindClass(class_name);
  if (clazz != nullptr) {
    env->ThrowNew(clazz, message);
    env->DeleteLocalRef(clazz);
  }
}

/// A JNIEnv for the current thread, attaching it if the FFI owns it.
///
/// Detaches on destruction **only when this scope did the attaching**. Detaching a thread the JVM
/// owns — the one that called in from Kotlin — tears down state that thread still needs; the JVM
/// does not survive it.
class ScopedEnv {
 public:
  ScopedEnv() {
    if (g_vm == nullptr) return;
    const jint status = g_vm->GetEnv(reinterpret_cast<void**>(&env_), JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
      // Named, so a native thread shows up as something recognisable in a thread dump rather
      // than as an anonymous "Thread-12" in the middle of a WebRTC stack.
      JavaVMAttachArgs args = {JNI_VERSION_1_6, const_cast<char*>("reactor-ffi"), nullptr};
      // Cast because the two jni.h in play disagree: the NDK declares AttachCurrentThread as
      // taking JNIEnv**, the desktop JDK as taking void**. The bridge compiles against the first
      // on a device and the second in the host sanitizer harness, and only the cast satisfies
      // both. The device build alone would never have shown this.
      if (g_vm->AttachCurrentThread(reinterpret_cast<void**>(&env_), &args) == JNI_OK) {
        attached_ = true;
      } else {
        env_ = nullptr;
      }
    } else if (status != JNI_OK) {
      env_ = nullptr;
    }
  }

  ~ScopedEnv() {
    if (attached_ && g_vm != nullptr) g_vm->DetachCurrentThread();
  }

  ScopedEnv(const ScopedEnv&) = delete;
  ScopedEnv& operator=(const ScopedEnv&) = delete;

  JNIEnv* get() const { return env_; }
  explicit operator bool() const { return env_ != nullptr; }

 private:
  JNIEnv* env_ = nullptr;
  bool attached_ = false;
};

/// A string the FFI handed us ownership of, freed exactly once.
///
/// The header states per function which of the three kinds a string is, and all three are easy to
/// get wrong in different directions: freeing the static one (`reactor_status`) corrupts the
/// heap, freeing a borrowed one is a double free, and not freeing an owned one leaks on every
/// property read. This type is only ever wrapped around the owned kind.
class OwnedString {
 public:
  explicit OwnedString(char* raw) : raw_(raw) {}
  ~OwnedString() {
    if (raw_ != nullptr) reactor_free_string(raw_);
  }

  OwnedString(const OwnedString&) = delete;
  OwnedString& operator=(const OwnedString&) = delete;

  const char* get() const { return raw_; }
  explicit operator bool() const { return raw_ != nullptr; }

 private:
  char* raw_;
};

/// A Java string from a C string, or null for nullptr.
///
/// Null is meaningful in this ABI rather than an error: `reactor_session_id` returns it when
/// there is no session, and `on_session_id` receives it when the session is cleared. Mapping it
/// to "" would lose that.
inline jstring to_jstring(JNIEnv* env, const char* value) {
  return value == nullptr ? nullptr : env->NewStringUTF(value);
}

/// A borrowed C string copied out of a JNI string, valid for the scope.
class JavaString {
 public:
  JavaString(JNIEnv* env, jstring value) : env_(env), value_(value) {
    if (value_ != nullptr) chars_ = env_->GetStringUTFChars(value_, nullptr);
  }

  ~JavaString() {
    if (chars_ != nullptr) env_->ReleaseStringUTFChars(value_, chars_);
  }

  JavaString(const JavaString&) = delete;
  JavaString& operator=(const JavaString&) = delete;

  /// nullptr for a null jstring — the ABI's nullable arguments take it directly.
  const char* get() const { return chars_; }

 private:
  JNIEnv* env_;
  jstring value_;
  const char* chars_ = nullptr;
};

}  // namespace reactor_jni
