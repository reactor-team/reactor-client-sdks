#include "jni_generated.h"
#include "jni_support.hpp"
#include <cstdlib>
#include <thread>

namespace {
uint32_t abi = REACTOR_ABI_VERSION;
int freed = 0;
}
// A fake FFI with real allocated strings; never give fabricated handles to Rust.
extern "C" uint32_t reactor_abi_version() { return abi; }
extern "C" int64_t reactor_time_micros() { return INT64_C(0x123456789abcdef); }
extern "C" void reactor_free_string(char* value) { ++freed; std::free(value); }

extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_internal_NativeBoundaryTest_setAbi(JNIEnv*, jobject, jint value) { abi = value; }

extern "C" JNIEXPORT jint JNICALL
Java_inc_reactor_sdk_internal_NativeBoundaryTest_freeCount(JNIEnv*, jobject) { return freed; }

extern "C" JNIEXPORT jbyteArray JNICALL
Java_inc_reactor_sdk_internal_NativeBoundaryTest_roundTrip(JNIEnv* env, jobject, jbyteArray value, jboolean owned) {
  try {
    auto copy = reactor_jni::inputText(env, value);
    if (owned) {
      auto* result = static_cast<char*>(std::malloc(copy.size()));
      if (!result) throw std::bad_alloc();
      std::memcpy(result, copy.data(), copy.size());
      return reactor_jni::ownedText(env, result);
    }
    return reactor_jni::text(env, copy.data());
  } catch (const std::exception& error) {
    reactor_jni::fail(env, "java/lang/IllegalArgumentException", error.what());
    return nullptr;
  }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_inc_reactor_sdk_internal_NativeBoundaryTest_buffer(JNIEnv* env, jobject, jlong size) {
  const char value = 'x';
  // Test bounds before JNI can read memory; never manufacture a huge buffer.
  if (size == 0) return reactor_jni::bytes(env, nullptr, 0);
  return reactor_jni::bytes(env, &value, static_cast<size_t>(size));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_inc_reactor_sdk_internal_NativeBoundaryTest_callbacks(JNIEnv* env, jobject, jobject receiver) {
  try {
    auto ticket = std::make_unique<reactor_jni::Ticket>(env, receiver);
    std::thread worker([&] {
      char message[] = "\xf0\x9f\x8c\x8d";
      unsigned char data[] = {0, 127, 128, 255};
      for (int i = 0; i < 1000; ++i) ticket->deliver(i, message, data, sizeof(data));
      // Data is borrowed: retained Java arrays must survive this overwrite.
      std::memset(message, 'x', 4);
      std::memset(data, 0, sizeof(data));
    });
    worker.join();
    bool failed = ticket->failed();
    reactor_jni::releaseAfterDestroy(std::move(ticket), 0);
    return failed;
  } catch (const std::exception& error) {
    reactor_jni::fail(env, "java/lang/IllegalStateException", error.what());
    return false;
  }
}

extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_internal_NativeBoundaryTest_lateCallback(JNIEnv* env, jobject, jobject receiver) {
  try {
    auto ticket = std::make_unique<reactor_jni::Ticket>(env, receiver);
    auto* retained = ticket.get();
    reactor_jni::releaseAfterDestroy(std::move(ticket), -1);
    std::thread worker([&] { retained->deliver(1, "after destroy", nullptr, 0); });
    worker.join();
    // The fake can prove quiescence by joining. Production must retain this
    // forever after -1 because reactor_destroy has consumed the handle.
    delete retained;
  } catch (const std::exception& error) {
    reactor_jni::fail(env, "java/lang/IllegalStateException", error.what());
  }
}

namespace {
reactor_completion_fn auth_completion = nullptr;
void* auth_userdata = nullptr;
}
extern "C" void reactor_fetch_jwt(const char*, const char*, const char*, int,
                                    reactor_completion_fn completion, void* userdata) {
  auth_completion = completion;
  auth_userdata = userdata;
}
extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_internal_NativeBoundaryTest_startAuth(JNIEnv* env, jobject, jobject receiver) {
  try {
    auto ticket = std::make_unique<reactor_jni::Ticket>(env, receiver);
    reactor_fetch_jwt("https://example.invalid", "fake-key", nullptr, 0,
                     reactor_jni::completeDetached, ticket.get());
    (void)ticket.release();
  } catch (const std::exception& error) {
    reactor_jni::fail(env, "java/lang/IllegalStateException", error.what());
  }
}
extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_internal_NativeBoundaryTest_finishAuth(JNIEnv*, jobject) {
  auto completion = auth_completion;
  auto userdata = auth_userdata;
  auth_completion = nullptr;
  auth_userdata = nullptr;
  if (!completion) return;
  std::thread worker([=] { completion(1, "{\"jwt\":\"fake\"}", nullptr, userdata); });
  worker.join();
}

// Exercises the production client.cpp entrypoints against real callback pointers.
#include <condition_variable>
#include <mutex>
#include <string>
struct ReactorHandle {
  ReactorCallbacks callbacks;
  std::string session;
  const char* status = "disconnected";
  std::string tracks = "[]";
  std::string paused = "[]";
};
namespace {
int lifecycle_mode = 0;
bool media_enabled = false;
bool invalidate_tracks = false;
bool orphan_next_destroy = false;
int destroyed = 0;
int created = 0;
uint32_t adopted_connection = 0;
std::string identity;
std::string supplied_token;
ReactorHandle* live = nullptr;
ReactorCallbacks orphan_callbacks{};
reactor_completion_fn pending_completion = nullptr;
void* pending_userdata = nullptr;
std::mutex start_mutex;
std::condition_variable start_cv;
bool started = false, released = false;
void done(reactor_completion_fn fn, void* ud) {
  std::thread worker([=] { fn(1, "{}", nullptr, ud); });
  worker.join();
}
}
extern "C" ReactorHandle* reactor_create_with_adm(const char*, const char*, const char* jwt, int,
    const ReactorCallbacks* callbacks, int adm, const char* version, const char* type) {
  if (adm != 0 || !version || !type || std::strcmp(type, "kotlin")) return nullptr;
  identity = std::string(type) + "/" + version;
  supplied_token = jwt ? jwt : "";
  ++created;
  live = new ReactorHandle{*callbacks, "", "disconnected", "[]", "[]"};
  if (media_enabled) live->tracks = R"([{"name":"z-video","kind":"video","direction":"recvonly"},{"name":"a-audio","kind":"audio","direction":"recvonly"},{"name":"input","kind":"video","direction":"sendonly"}])";
  return live;
}
extern "C" int reactor_destroy(ReactorHandle* handle) {
  ++destroyed;
  const bool orphaned = lifecycle_mode == 1 || orphan_next_destroy;
  orphan_next_destroy = false;
  if (orphaned) orphan_callbacks = handle->callbacks;
  else { pending_completion = nullptr; pending_userdata = nullptr; }
  delete handle;
  live = nullptr;
  return orphaned ? -1 : 0;
}
extern "C" void reactor_connect(ReactorHandle* handle, const char* session, const uint32_t* connection,
    reactor_completion_fn completion, void* userdata) {
  handle->session = session ? session : "created-session";
  adopted_connection = connection ? *connection : 0;
  handle->status = "ready";
  if (lifecycle_mode == 3) {
    std::unique_lock<std::mutex> lock(start_mutex);
    started = true;
    start_cv.notify_all();
    start_cv.wait(lock, [] { return released; });
  }
  auto callbacks = handle->callbacks;
  std::thread worker([=] {
    callbacks.on_status("ready", callbacks.userdata);
    callbacks.on_session_id(handle->session.c_str(), callbacks.userdata);
    auto caps = std::string("{\"tracks\":") + handle->tracks + "}";
    callbacks.on_capabilities(caps.c_str(), callbacks.userdata);
    if (media_enabled) callbacks.on_track("z-video", "video-mid", callbacks.userdata);
    callbacks.on_runtime_message("{\"type\":\"test\"}", callbacks.userdata);
  });
  worker.join();
  if (lifecycle_mode == 1 || lifecycle_mode == 2) {
    pending_completion = completion;
    pending_userdata = userdata;
  } else done(completion, userdata);
}
extern "C" void reactor_reconnect(ReactorHandle* handle, reactor_completion_fn completion, void* userdata) {
  if (handle->session.empty()) {
    std::thread worker([=] { completion(0, nullptr, "{\"code\":\"INVALID_STATE\",\"message\":\"No session\"}", userdata); });
    worker.join();
  } else done(completion, userdata);
}
extern "C" void reactor_disconnect(ReactorHandle* handle, reactor_completion_fn completion, void* userdata) {
  handle->session.clear();
  handle->status = "disconnected";
  auto cb = handle->callbacks;
  std::thread worker([=] { cb.on_session_id(nullptr, cb.userdata); cb.on_status("disconnected", cb.userdata); });
  worker.join();
  done(completion, userdata);
}
extern "C" const char* reactor_status(ReactorHandle* handle) { return handle->status; }
extern "C" char* reactor_session_id(ReactorHandle* handle) {
  return handle->session.empty() ? nullptr : ::strdup(handle->session.c_str());
}
extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_internal_LifecycleTest_resetFake(JNIEnv*, jobject, jint mode) {
  media_enabled = false; invalidate_tracks = false; orphan_next_destroy = false;
  lifecycle_mode = mode; destroyed = 0; created = 0; adopted_connection = 0;
  started = false; released = false;
}
extern "C" JNIEXPORT jint JNICALL
Java_inc_reactor_sdk_internal_LifecycleTest_destroyCount(JNIEnv*, jobject) { return destroyed; }
extern "C" JNIEXPORT jint JNICALL
Java_inc_reactor_sdk_internal_LifecycleTest_createCount(JNIEnv*, jobject) { return created; }
extern "C" JNIEXPORT jlong JNICALL
Java_inc_reactor_sdk_internal_LifecycleTest_connectionId(JNIEnv*, jobject) { return adopted_connection; }
extern "C" JNIEXPORT jbyteArray JNICALL
Java_inc_reactor_sdk_internal_LifecycleTest_clientIdentity(JNIEnv* env, jobject) { return reactor_jni::text(env, identity.c_str()); }
extern "C" JNIEXPORT jbyteArray JNICALL
Java_inc_reactor_sdk_internal_LifecycleTest_clientToken(JNIEnv* env, jobject) { return reactor_jni::text(env, supplied_token.c_str()); }
extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_internal_LifecycleTest_finishLate(JNIEnv*, jobject) {
  auto fn = pending_completion; auto ud = pending_userdata;
  pending_completion = nullptr; pending_userdata = nullptr;
  std::thread worker([=] {
    if (orphan_callbacks.on_status) orphan_callbacks.on_status("ready", orphan_callbacks.userdata);
    if (fn) fn(1, "{}", nullptr, ud);
  });
  worker.join();
  orphan_callbacks = {};
}
extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_internal_LifecycleTest_waitForStart(JNIEnv*, jobject) {
  std::unique_lock<std::mutex> lock(start_mutex);
  start_cv.wait(lock, [] { return started; });
}
extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_internal_LifecycleTest_releaseStart(JNIEnv*, jobject) {
  std::lock_guard<std::mutex> lock(start_mutex);
  released = true; start_cv.notify_all();
}
extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_internal_LifecycleTest_emitStatus(JNIEnv*, jobject) {
  auto cb = live->callbacks;
  std::thread worker([=] { cb.on_status("ready", cb.userdata); });
  worker.join();
}

extern "C" char* reactor_tracks(ReactorHandle* handle) {
  auto previous = handle->tracks;
  if (invalidate_tracks) {
    invalidate_tracks = false;
    handle->tracks = R"([{"name":"fresh","kind":"audio","direction":"recvonly"}])";
    auto caps = std::string("{\"tracks\":") + handle->tracks + "}";
    auto cb = handle->callbacks;
    std::thread worker([&] { cb.on_capabilities(caps.c_str(), cb.userdata); });
    worker.join();
  }
  return ::strdup(previous.c_str());
}
extern "C" char* reactor_paused_tracks(ReactorHandle* handle) { return ::strdup(handle->paused.c_str()); }
extern "C" void reactor_pause_track(ReactorHandle* handle, const char* name, reactor_completion_fn fn, void* ud) {
  handle->paused = std::string("[\"") + name + "\"]"; done(fn, ud);
}
extern "C" void reactor_resume_track(ReactorHandle* handle, const char*, reactor_completion_fn fn, void* ud) {
  handle->paused = "[]"; done(fn, ud);
}
extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_internal_MediaReceiveTest_resetMedia(JNIEnv*, jobject) {
  Java_inc_reactor_sdk_internal_LifecycleTest_resetFake(nullptr, nullptr, 0);
  media_enabled = true;
}
extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_internal_MediaReceiveTest_invalidateOnRead(JNIEnv*, jobject) { invalidate_tracks = true; }
extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_internal_MediaReceiveTest_emit(JNIEnv*, jobject, jint kind) {
  auto cb = live->callbacks;
  std::thread worker([=] {
    uint8_t pixels[] = {1, 2, 3, 255};
    uint8_t metadata[] = {0, 128, 255};
    int16_t samples[] = {-32768, 10, 300, 32767};
    if (kind == 0 || kind == 2) cb.on_frame(kind == 2 ? "missing" : "z-video", pixels, 1, 1, UINT64_MAX, UINT64_C(0x8000000000000000), metadata, 3, cb.userdata);
    else if (kind == 1) cb.on_audio("a-audio", samples, 4, 44100, 2, cb.userdata);
    else if (kind == 3) cb.on_frame("z-video", pixels, UINT32_MAX, UINT32_MAX, 0, 0, nullptr, 0, cb.userdata);
    else cb.on_audio("a-audio", samples, 3, 44100, 2, cb.userdata);
    std::memset(pixels, 0, sizeof(pixels));
    std::memset(metadata, 0, sizeof(metadata));
    std::memset(samples, 0, sizeof(samples));
  });
  worker.join();
}

extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_internal_MediaReceiveTest_orphanNextDestroy(JNIEnv*, jobject) { orphan_next_destroy = true; }
extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_internal_MediaReceiveTest_emitOld(JNIEnv*, jobject) {
  auto cb = orphan_callbacks;
  std::thread worker([=] {
    uint8_t pixel[] = {1, 2, 3, 4};
    cb.on_capabilities(R"({"tracks":[{"name":"stale","kind":"video","direction":"recvonly"}]})", cb.userdata);
    cb.on_frame("z-video", pixel, 1, 1, 1, 1, nullptr, 0, cb.userdata);
  });
  worker.join();
  orphan_callbacks = {};
}
