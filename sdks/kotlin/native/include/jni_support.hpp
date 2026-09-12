#pragma once

#include <jni.h>
#include "reactor_ffi.h"
#include <atomic>
#include <cstring>
#include <limits>
#include <memory>
#include <stdexcept>
#include <vector>

namespace reactor_jni {

// JNIEnv belongs to a thread. Never store it in a callback ticket.
class Env {
 public:
  explicit Env(JavaVM* vm) : vm_(vm) {
    auto result = vm_->GetEnv(reinterpret_cast<void**>(&env_), JNI_VERSION_1_6);
    if (result == JNI_EDETACHED) {
#ifdef __ANDROID__
      attached_ = vm_->AttachCurrentThread(&env_, nullptr) == JNI_OK;
#else
      attached_ = vm_->AttachCurrentThread(reinterpret_cast<void**>(&env_), nullptr) == JNI_OK;
#endif
      if (!attached_) env_ = nullptr;
    } else if (result != JNI_OK) {
      env_ = nullptr;
    }
  }
  ~Env() { if (attached_) vm_->DetachCurrentThread(); }
  JNIEnv* get() const { return env_; }
  Env(const Env&) = delete;
  Env& operator=(const Env&) = delete;
 private:
  JavaVM* vm_;
  JNIEnv* env_ = nullptr;
  bool attached_ = false;
};

inline void fail(JNIEnv* env, const char* type, const char* message) {
  if (env->ExceptionCheck()) return;
  auto cls = env->FindClass(type);
  if (cls) {
    env->ThrowNew(cls, message);
    env->DeleteLocalRef(cls);
  }
}

// Copy borrowed FFI storage before returning. Nullable and empty stay distinct.
inline jbyteArray bytes(JNIEnv* env, const void* data, size_t size) {
  if (size > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
    fail(env, "java/lang/IllegalArgumentException", "Native buffer exceeds JVM array limit");
    return nullptr;
  }
  if (!data) {
    if (size) fail(env, "java/lang/IllegalArgumentException", "Null buffer with nonzero length");
    return nullptr;
  }
  auto out = env->NewByteArray(static_cast<jsize>(size));
  if (out && size) env->SetByteArrayRegion(out, 0, static_cast<jsize>(size), static_cast<const jbyte*>(data));
  return out;
}

inline jbyteArray text(JNIEnv* env, const char* value) {
  return value ? bytes(env, value, std::strlen(value)) : nullptr;
}

struct FreeString { void operator()(char* value) const { if (value) reactor_free_string(value); } };
inline jbyteArray ownedText(JNIEnv* env, char* value) {
  std::unique_ptr<char, FreeString> owner(value);
  return text(env, owner.get());
}

// Kotlin uses standard UTF-8 bytes, not JNI's modified UTF-8 (which corrupts
// supplementary characters). Hold a copy; never pin a Java array across FFI.
inline std::vector<char> inputText(JNIEnv* env, jbyteArray input) {
  if (!input) throw std::invalid_argument("Required UTF-8 string is null");
  auto size = env->GetArrayLength(input);
  std::vector<char> copy(static_cast<size_t>(size) + 1, '\0');
  env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte*>(copy.data()));
  if (env->ExceptionCheck()) throw std::runtime_error("Cannot copy UTF-8 string");
  if (std::memchr(copy.data(), '\0', size)) throw std::invalid_argument("C strings cannot contain NUL");
  return copy;
}

// The owner retains this ticket until reactor_destroy returns 0. The weak
// receiver avoids a callback keeping its client alive. Resolve its method on
// the registering JVM thread, using the receiver's own class loader.
class Ticket {
 public:
  Ticket(JNIEnv* env, jobject receiver) {
    if (!receiver) throw std::invalid_argument("Callback receiver is null");
    if (env->GetJavaVM(&vm_) != JNI_OK) throw std::runtime_error("Cannot get JavaVM");
    auto cls = env->GetObjectClass(receiver);
    method_ = cls ? env->GetMethodID(cls, "accept", "(I[B[B)V") : nullptr;
    if (cls) env->DeleteLocalRef(cls);
    if (!method_) throw std::runtime_error("Callback receiver must implement accept");
    receiver_ = env->NewWeakGlobalRef(receiver);
    if (!receiver_) throw std::bad_alloc();
  }
  ~Ticket() {
    Env thread(vm_);
    if (auto* env = thread.get()) env->DeleteWeakGlobalRef(receiver_);
  }
  Ticket(const Ticket&) = delete;
  Ticket& operator=(const Ticket&) = delete;
  void deliver(jint kind, const char* message, const void* data, size_t size) noexcept {
    Env thread(vm_);
    auto* env = thread.get();
    if (!env) return;
    if (env->PushLocalFrame(8) < 0) { env->ExceptionClear(); return; }
    auto receiver = env->NewLocalRef(receiver_);
    if (receiver && !env->ExceptionCheck()) {
      auto message_copy = text(env, message);
      auto data_copy = env->ExceptionCheck() ? nullptr : bytes(env, data, size);
      if (!env->ExceptionCheck()) env->CallVoidMethod(receiver, method_, kind, message_copy, data_copy);
    }
    // No Java exception may escape onto an FFI-owned thread. Higher layers
    // report user-handler exceptions; this flag makes boundary failures visible.
    if (env->ExceptionCheck()) { failed_.store(true); env->ExceptionClear(); }
    env->PopLocalFrame(nullptr);
  }
  bool failed() const { return failed_.load(); }
 private:
  JavaVM* vm_ = nullptr;
  jweak receiver_ = nullptr;
  jmethodID method_ = nullptr;
  std::atomic<bool> failed_{false};
};

inline void releaseAfterDestroy(std::unique_ptr<Ticket> ticket, int result) {
  // -1: native callbacks can still enter. Deliberately retain the ticket.
  if (result != 0) (void)ticket.release();
}

// fetch_jwt and download_clip complete independently of a client handle.
// Transfer a heap ticket to the FFI and release it only in this completion.
inline void completeDetached(int ok, const char* result, const char* error, void* userdata) noexcept {
  std::unique_ptr<Ticket> ticket(static_cast<Ticket*>(userdata));
  ticket->deliver(ok, result, error, error ? std::strlen(error) : 0);
}

}  // namespace reactor_jni
