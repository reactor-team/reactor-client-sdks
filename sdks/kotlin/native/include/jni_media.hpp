#pragma once
#include "jni_support.hpp"

namespace reactor_jni {
class MediaTicket {
 public:
  MediaTicket(JNIEnv* env, jobject receiver) {
    if (env->GetJavaVM(&vm_) != JNI_OK) throw std::runtime_error("Cannot get JavaVM");
    auto cls = env->GetObjectClass(receiver);
    video_ = cls ? env->GetMethodID(cls, "video", "([B[BIIJJ[B)V") : nullptr;
    if (!env->ExceptionCheck()) audio_ = env->GetMethodID(cls, "audio", "([B[SII)V");
    if (!env->ExceptionCheck()) diagnostic_ = env->GetMethodID(cls, "diagnostic", "([B)V");
    if (cls) env->DeleteLocalRef(cls);
    if (!video_ || !audio_ || !diagnostic_) throw std::runtime_error("Invalid media callback receiver");
    receiver_ = env->NewWeakGlobalRef(receiver);
    if (!receiver_) throw std::bad_alloc();
  }
  ~MediaTicket() { Env thread(vm_); if (auto* env = thread.get()) env->DeleteWeakGlobalRef(receiver_); }
  MediaTicket(const MediaTicket&) = delete;
  MediaTicket& operator=(const MediaTicket&) = delete;

  void video(const char* name, const uint8_t* pixels, uint32_t width, uint32_t height,
             uint64_t id, uint64_t timestamp, const uint8_t* metadata, uint32_t metadata_size) noexcept {
    call([&](JNIEnv* env, jobject receiver) {
      const uint64_t count = static_cast<uint64_t>(width) * height;
      if (!name || !pixels || !width || !height || count > INT32_MAX / 4 || metadata_size > INT32_MAX || (!metadata && metadata_size)) {
        diagnostic(env, receiver, "Invalid native BGRA dimensions, metadata or pointer"); return;
      }
      auto n = text(env, name);
      auto p = env->ExceptionCheck() ? nullptr : bytes(env, pixels, static_cast<size_t>(count * 4));
      auto m = env->ExceptionCheck() ? nullptr : bytes(env, metadata, metadata_size);
      jlong frame_id, stamp;
      static_assert(sizeof(jlong) == sizeof(uint64_t));
      std::memcpy(&frame_id, &id, sizeof(id));
      std::memcpy(&stamp, &timestamp, sizeof(timestamp));
      if (!env->ExceptionCheck()) env->CallVoidMethod(receiver, video_, n, p, static_cast<jint>(width), static_cast<jint>(height), frame_id, stamp, m);
    });
  }
  void audio(const char* name, const int16_t* samples, uint32_t count, uint32_t rate, uint32_t channels) noexcept {
    call([&](JNIEnv* env, jobject receiver) {
      if (!name || (!samples && count) || count > INT32_MAX || !rate || rate > INT32_MAX || !channels || channels > INT32_MAX || count % channels) {
        diagnostic(env, receiver, "Invalid native PCM count, format or pointer"); return;
      }
      auto n = text(env, name);
      auto pcm = env->ExceptionCheck() ? nullptr : env->NewShortArray(static_cast<jsize>(count));
      if (pcm && count) env->SetShortArrayRegion(pcm, 0, static_cast<jsize>(count), samples);
      if (!env->ExceptionCheck()) env->CallVoidMethod(receiver, audio_, n, pcm, static_cast<jint>(rate), static_cast<jint>(channels));
    });
  }
 private:
  void diagnostic(JNIEnv* env, jobject receiver, const char* message) {
    auto value = text(env, message);
    if (!env->ExceptionCheck()) env->CallVoidMethod(receiver, diagnostic_, value);
  }
  template<class F> void call(F&& function) noexcept {
    Env thread(vm_);
    auto* env = thread.get();
    if (!env) return;
    if (env->PushLocalFrame(8) < 0) { env->ExceptionClear(); return; }
    auto receiver = env->NewLocalRef(receiver_);
    if (receiver && !env->ExceptionCheck()) function(env, receiver);
    // User handlers are contained in Kotlin; allocation/decoding failures cannot escape JNI.
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->PopLocalFrame(nullptr);
  }
  JavaVM* vm_ = nullptr;
  jweak receiver_ = nullptr;
  jmethodID video_ = nullptr, audio_ = nullptr, diagnostic_ = nullptr;
};
} // namespace reactor_jni
