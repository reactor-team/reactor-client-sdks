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
bool send_enabled = false;
bool invalidate_tracks = false;
bool orphan_next_destroy = false;
int destroyed = 0;
int created = 0;
uint32_t adopted_connection = 0;
std::string identity;
std::string supplied_token;
ReactorHandle* live = nullptr;
ReactorCallbacks orphan_callbacks{};
void clear_queries();
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
  if (send_enabled) live->tracks.insert(live->tracks.size() - 1, R"(,{"name":"mic","kind":"audio","direction":"sendonly"})");
  return live;
}
extern "C" int reactor_destroy(ReactorHandle* handle) {
  ++destroyed;
  const bool orphaned = lifecycle_mode == 1 || orphan_next_destroy;
  orphan_next_destroy = false;
  if (orphaned) orphan_callbacks = handle->callbacks;
  else { pending_completion = nullptr; pending_userdata = nullptr; clear_queries(); }
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
  media_enabled = false; send_enabled = false; invalidate_tracks = false; orphan_next_destroy = false;
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

namespace {
int publish_mode = 0;
bool unpublish_failure = false;
reactor_completion_fn publish_completion = nullptr;
void* publish_userdata = nullptr;
std::vector<uint8_t> pushed_video;
std::vector<int16_t> pushed_audio;
jlong send_values[15]{};
void video_sent(const char* name, const uint8_t* data, uint32_t width, uint32_t height,
                const uint8_t* metadata, uint32_t size, int64_t capture, int mode) {
  if (std::strcmp(name, "input")) std::abort();
  ++send_values[0]; send_values[1] = width; send_values[2] = height; send_values[3] = capture; send_values[4] = mode;
  pushed_video.assign(data, data + width * height * 4);
  if (size) pushed_video.insert(pushed_video.end(), metadata, metadata + size);
}
void bitrate_sent(const char* name, int min, int start, int max, reactor_completion_fn fn, void* ud) {
  ++send_values[8]; send_values[9] = min; send_values[10] = start; send_values[11] = max; send_values[12] = name ? 1 : 0;
  done(fn, ud);
}
}
extern "C" void reactor_publish_track(ReactorHandle*, const char*, reactor_completion_fn fn, void* ud) {
  ++send_values[13];
  if (publish_mode == 1) { publish_completion = fn; publish_userdata = ud; return; }
  std::thread worker([=] {
    if (publish_mode == 2) fn(0, nullptr, R"({"code":"INVALID_STATE","message":"Publish refused"})", ud);
    else fn(1, publish_mode == 3 ? "[]" : "{}", nullptr, ud);
  });
  worker.join();
}
extern "C" char* reactor_unpublish_track(ReactorHandle*, const char*) {
  ++send_values[14];
  return unpublish_failure ? ::strdup(R"({"code":"INVALID_STATE","message":"Retry unpublish"})") : nullptr;
}
extern "C" void reactor_set_bitrate(ReactorHandle*, int32_t min, int32_t start, int32_t max, reactor_completion_fn fn, void* ud) {
  bitrate_sent(nullptr, min, start, max, fn, ud);
}
extern "C" void reactor_set_track_bitrate(ReactorHandle*, const char* name, int32_t min, int32_t max, reactor_completion_fn fn, void* ud) {
  bitrate_sent(name, min, -1, max, fn, ud);
}
extern "C" void reactor_push_video_frame(ReactorHandle*, const char* name, const uint8_t* data, uint32_t width, uint32_t height) {
  video_sent(name, data, width, height, nullptr, 0, -1, 0);
}
extern "C" void reactor_push_video_frame_with_metadata(ReactorHandle*, const char* name, const uint8_t* data, uint32_t width, uint32_t height,
    const uint8_t* metadata, uint32_t size) {
  video_sent(name, data, width, height, metadata, size, -1, 1);
}
extern "C" void reactor_push_video_frame_with_metadata_at(ReactorHandle*, const char* name, const uint8_t* data, uint32_t width, uint32_t height,
    const uint8_t* metadata, uint32_t size, int64_t capture) {
  video_sent(name, data, width, height, metadata, size, capture, 2);
}
extern "C" void reactor_push_audio_frame(ReactorHandle*, const char* name, const int16_t* samples, uint32_t count, uint32_t rate, uint32_t channels) {
  if (std::strcmp(name, "mic")) std::abort();
  pushed_audio.assign(samples, samples + count * channels);
  send_values[5] = count; send_values[6] = rate; send_values[7] = channels;
}
extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_internal_MediaSendTest_resetSend(JNIEnv*, jobject) {
  Java_inc_reactor_sdk_internal_LifecycleTest_resetFake(nullptr, nullptr, 0);
  media_enabled = true; send_enabled = true; publish_mode = 0; unpublish_failure = false;
  publish_completion = nullptr; publish_userdata = nullptr;
  std::memset(send_values, 0, sizeof(send_values)); pushed_video.clear(); pushed_audio.clear();
}
extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_internal_MediaSendTest_configure(JNIEnv*, jobject, jint mode, jboolean fail) {
  publish_mode = mode; unpublish_failure = fail;
}
extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_internal_MediaSendTest_finishPublish(JNIEnv*, jobject) {
  auto fn = publish_completion; auto ud = publish_userdata;
  publish_completion = nullptr; publish_userdata = nullptr;
  if (fn) done(fn, ud);
}
extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_internal_MediaSendTest_cycleStatus(JNIEnv*, jobject) {
  auto cb = live->callbacks;
  std::thread worker([=] {
    live->status = "waiting"; cb.on_status("waiting", cb.userdata);
    live->status = "ready"; cb.on_status("ready", cb.userdata);
  });
  worker.join();
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_inc_reactor_sdk_internal_MediaSendTest_values(JNIEnv* env, jobject) {
  auto result = env->NewLongArray(15);
  if (result) env->SetLongArrayRegion(result, 0, 15, send_values);
  return result;
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_inc_reactor_sdk_internal_MediaSendTest_videoBytes(JNIEnv* env, jobject) {
  return reactor_jni::bytes(env, pushed_video.data(), pushed_video.size());
}
extern "C" JNIEXPORT jshortArray JNICALL
Java_inc_reactor_sdk_internal_MediaSendTest_audioSamples(JNIEnv* env, jobject) {
  auto result = env->NewShortArray(static_cast<jsize>(pushed_audio.size()));
  if (result && !pushed_audio.empty()) env->SetShortArrayRegion(result, 0, static_cast<jsize>(pushed_audio.size()), pushed_audio.data());
  return result;
}

#include "stats_fixture.hpp"
namespace {
struct QueryAnswer {
  reactor_completion_fn fn;
  void* userdata;
  std::string payload;
  bool absent;
  bool failure;
};
int query_mode = 0;
bool custom_query = false, custom_absent = false;
std::string custom_payload;
std::vector<QueryAnswer> queries;
void clear_queries() { queries.clear(); }
void answer(QueryAnswer value) {
  std::thread worker([&] {
    if (value.failure) value.fn(0, nullptr, R"({"code":"BAD_REQUEST","message":"Command rejected"})", value.userdata);
    else value.fn(1, value.absent ? nullptr : value.payload.c_str(), nullptr, value.userdata);
    std::fill(value.payload.begin(), value.payload.end(), 'x');
  });
  worker.join();
}
void query_result(reactor_completion_fn fn, void* ud, std::string payload) {
  if (custom_query) payload = custom_payload;
  else if (query_mode == 4) payload = "{";
  else if (query_mode == 5) payload = "[]";
  else if (query_mode == 6) payload = R"({"type":42})";
  QueryAnswer value{fn, ud, payload, query_mode == 3 || (custom_query && custom_absent), query_mode == 2};
  if (query_mode == 1) queries.push_back(std::move(value)); else answer(std::move(value));
}
}
extern "C" void reactor_send_command(ReactorHandle*, const char*, const char* args, const char*, reactor_completion_fn fn, void* ud) {
  query_result(fn, ud, std::string(R"({"type":"reply","data":)") + (args ? args : "{}") + "}");
}
extern "C" void reactor_request_schema(ReactorHandle*, reactor_completion_fn fn, void* ud) {
  query_result(fn, ud, R"({"openapi":"3.1.0","paths":{"/command":{}}})");
}
extern "C" void reactor_get_stats(ReactorHandle* handle, reactor_completion_fn fn, void* ud) {
  if (std::strcmp(handle->status, "ready")) {
    std::thread worker([=] { fn(0, nullptr, R"({"code":"INVALID_STATE","message":"Statistics require ready"})", ud); });
    worker.join(); return;
  }
  query_result(fn, ud, stats_fixture);
}
extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_internal_CommandTest_configure(JNIEnv*, jobject, jint mode) {
  lifecycle_mode = 0; query_mode = mode; custom_query = false; custom_absent = false; custom_payload.clear();
}
extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_internal_CommandTest_replyWith(JNIEnv* env, jobject, jbyteArray payload) {
  custom_query = true; custom_absent = !payload;
  custom_payload = payload ? reactor_jni::inputText(env, payload).data() : "";
}
extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_internal_CommandTest_finishReverse(JNIEnv*, jobject) {
  auto pending = std::move(queries); queries.clear();
  for (auto it = pending.rbegin(); it != pending.rend(); ++it) answer(std::move(*it));
}
extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_internal_CommandTest_orphanNextDestroy(JNIEnv*, jobject) { orphan_next_destroy = true; }
extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_internal_CommandTest_emitMessage(JNIEnv*, jobject, jboolean runtime) {
  auto cb = live->callbacks;
  std::thread worker([=] {
    auto fn = runtime ? cb.on_runtime_message : cb.on_message;
    fn(R"({"type":"notice","data":{"value":7}})", cb.userdata);
  });
  worker.join();
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_inc_reactor_sdk_internal_CommandTest_statsFixture(JNIEnv* env, jobject) { return reactor_jni::text(env, stats_fixture); }
