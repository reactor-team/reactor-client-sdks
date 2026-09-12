#include "jni_generated.h"
#include "jni_support.hpp"
#include "jni_media.hpp"
#include <mutex>
#include <unordered_map>

namespace {
using reactor_jni::Ticket;
struct Client;
struct Operation {
  Client* owner;
  Ticket ticket;
  Operation(Client* owner, JNIEnv* env, jobject receiver) : owner(owner), ticket(env, receiver) {}
};
struct Client {
  ReactorHandle* handle = nullptr;
  Ticket events;
  std::unique_ptr<reactor_jni::MediaTicket> media;
  std::mutex mutex;
  std::unordered_map<Operation*, std::unique_ptr<Operation>> pending;
  Client(JNIEnv* env, jobject receiver, jobject media_receiver) : events(env, receiver) {
    if (media_receiver) media = std::make_unique<reactor_jni::MediaTicket>(env, media_receiver);
  }
};
Client* client(jlong value) {
  if (!value) throw std::invalid_argument("Client is closed");
  return reinterpret_cast<Client*>(static_cast<intptr_t>(value));
}
std::vector<char> optional(JNIEnv* env, jbyteArray value) {
  return value ? reactor_jni::inputText(env, value) : std::vector<char>{};
}
const char* pointer(const std::vector<char>& value) { return value.empty() ? nullptr : value.data(); }
void complete(int ok, const char* result, const char* error, void* userdata) noexcept {
  auto* operation = static_cast<Operation*>(userdata);
  std::unique_ptr<Operation> owned;
  {
    std::lock_guard<std::mutex> lock(operation->owner->mutex);
    auto& pending = operation->owner->pending;
    auto found = pending.find(operation);
    if (found == pending.end()) return;
    owned = std::move(found->second);
    pending.erase(found);
  }
  owned->ticket.deliver(ok, result, error, error ? std::strlen(error) : 0);
}
template<int kind> void event(const char* value, void* userdata) noexcept {
  static_cast<Client*>(userdata)->events.deliver(kind, value, nullptr, 0);
}
void track(const char* name, const char* mid, void* userdata) noexcept {
  static_cast<Client*>(userdata)->events.deliver(6, name, mid, mid ? std::strlen(mid) : 0);
}
void video(const char* name, const uint8_t* pixels, uint32_t width, uint32_t height,
           uint64_t id, uint64_t timestamp, const uint8_t* metadata, uint32_t size, void* userdata) noexcept {
  static_cast<Client*>(userdata)->media->video(name, pixels, width, height, id, timestamp, metadata, size);
}
void audio(const char* name, const int16_t* samples, uint32_t count, uint32_t rate, uint32_t channels, void* userdata) noexcept {
  static_cast<Client*>(userdata)->media->audio(name, samples, count, rate, channels);
}
void failure(JNIEnv* env, const std::exception& error) {
  reactor_jni::fail(env, "java/lang/IllegalStateException", error.what());
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_inc_reactor_sdk_internal_NativeClient_create(JNIEnv* env, jobject, jbyteArray url, jbyteArray model,
    jbyteArray token, jboolean local, jbyteArray version, jobject receiver, jobject media_receiver) {
  try {
    if (reactor_abi_version() != REACTOR_ABI_VERSION) {
      reactor_jni::fail(env, "java/lang/UnsatisfiedLinkError", "Reactor FFI ABI mismatch");
      return 0;
    }
    auto u = reactor_jni::inputText(env, url), m = reactor_jni::inputText(env, model);
    auto t = optional(env, token), v = reactor_jni::inputText(env, version);
    auto state = std::make_unique<Client>(env, receiver, media_receiver);
    ReactorCallbacks callbacks{};
    callbacks.on_status = event<0>;
    callbacks.on_error = event<1>;
    callbacks.on_message = event<2>;
    callbacks.on_runtime_message = event<3>;
    callbacks.on_capabilities = event<4>;
    callbacks.on_session_id = event<5>;
    callbacks.on_track = track;
    if (state->media) { callbacks.on_frame = video; callbacks.on_audio = audio; }
    callbacks.userdata = state.get();
    state->handle = reactor_create_with_adm(u.data(), m.data(), pointer(t), local,
        &callbacks, 0, v.data(), "kotlin");
    if (!state->handle) throw std::runtime_error("Native client creation failed");
    return static_cast<jlong>(reinterpret_cast<intptr_t>(state.release()));
  } catch (const std::exception& error) { failure(env, error); return 0; }
}

extern "C" JNIEXPORT jint JNICALL
Java_inc_reactor_sdk_internal_NativeClient_destroy(JNIEnv* env, jobject, jlong value) {
  try {
    std::unique_ptr<Client> state(client(value));
    // No callback mutex held: destroy waits for callbacks that acquire it.
    int result = reactor_destroy(state->handle);
    if (result != 0) (void)state.release(); // consumed handle, still-live callback state
    return result;
  } catch (const std::exception& error) { failure(env, error); return -1; }
}

extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_internal_NativeClient_start(JNIEnv* env, jobject, jlong value, jint kind,
    jbyteArray session, jlong connection, jobject receiver) {
  try {
    auto* state = client(value);
    if (kind < 0 || kind > 5) throw std::invalid_argument("Unknown lifecycle operation");
    if (connection < -1 || connection > UINT32_MAX) throw std::invalid_argument("Connection ID out of range");
    auto sid = optional(env, session);
    uint32_t cid = static_cast<uint32_t>(connection);
    auto owned = std::make_unique<Operation>(state, env, receiver);
    auto* operation = owned.get();
    {
      std::lock_guard<std::mutex> lock(state->mutex);
      state->pending.emplace(operation, std::move(owned));
    }
    if (kind == 0) reactor_connect(state->handle, pointer(sid), connection < 0 ? nullptr : &cid, complete, operation);
    else if (kind == 1) reactor_reconnect(state->handle, complete, operation);
    else if (kind == 2) reactor_disconnect(state->handle, complete, operation);
    else if (kind == 3) reactor_pause_track(state->handle, pointer(sid), complete, operation);
    else if (kind == 4) reactor_resume_track(state->handle, pointer(sid), complete, operation);
    else reactor_publish_track(state->handle, pointer(sid), complete, operation);
  } catch (const std::exception& error) { failure(env, error); }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_inc_reactor_sdk_internal_NativeClient_status(JNIEnv* env, jobject, jlong value) {
  try { return reactor_jni::text(env, reactor_status(client(value)->handle)); }
  catch (const std::exception& error) { failure(env, error); return nullptr; }
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_inc_reactor_sdk_internal_NativeClient_session(JNIEnv* env, jobject, jlong value) {
  try { return reactor_jni::ownedText(env, reactor_session_id(client(value)->handle)); }
  catch (const std::exception& error) { failure(env, error); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_internal_NativeClient_authenticate(JNIEnv* env, jobject, jbyteArray url,
    jbyteArray key, jbyteArray options, jboolean local, jobject receiver) {
  try {
    auto u = reactor_jni::inputText(env, url), k = reactor_jni::inputText(env, key);
    auto o = reactor_jni::inputText(env, options);
    auto ticket = std::make_unique<Ticket>(env, receiver);
    // Ownership transfers before the call, including if a completion is immediate.
    auto* raw = ticket.release();
    reactor_fetch_jwt(u.data(), k.data(), o.data(), local, reactor_jni::completeDetached, raw);
  } catch (const std::exception& error) { failure(env, error); }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_inc_reactor_sdk_internal_NativeClient_tracks(JNIEnv* env, jobject, jlong value) {
  try { return reactor_jni::ownedText(env, reactor_tracks(client(value)->handle)); }
  catch (const std::exception& error) { failure(env, error); return nullptr; }
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_inc_reactor_sdk_internal_NativeClient_paused(JNIEnv* env, jobject, jlong value) {
  try { return reactor_jni::ownedText(env, reactor_paused_tracks(client(value)->handle)); }
  catch (const std::exception& error) { failure(env, error); return nullptr; }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_inc_reactor_sdk_internal_NativeClient_unpublish(JNIEnv* env, jobject, jlong value, jbyteArray name) {
  try {
    auto n = reactor_jni::inputText(env, name);
    return reactor_jni::ownedText(env, reactor_unpublish_track(client(value)->handle, n.data()));
  } catch (const std::exception& error) { failure(env, error); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_internal_NativeClient_bitrate(JNIEnv* env, jobject, jlong value, jbyteArray name,
    jint min, jint start, jint max, jobject receiver) {
  try {
    if (min < -1 || start < -1 || max < -1) throw std::invalid_argument("Bitrate bounds must be >= -1");
    auto* state = client(value);
    auto n = optional(env, name);
    auto owned = std::make_unique<Operation>(state, env, receiver);
    auto* operation = owned.get();
    {
      std::lock_guard<std::mutex> lock(state->mutex);
      state->pending.emplace(operation, std::move(owned));
    }
    if (name) reactor_set_track_bitrate(state->handle, n.data(), min, max, complete, operation);
    else reactor_set_bitrate(state->handle, min, start, max, complete, operation);
  } catch (const std::exception& error) { failure(env, error); }
}

namespace {
std::vector<uint8_t> inputBytes(JNIEnv* env, jbyteArray input) {
  if (!input) return {};
  auto size = env->GetArrayLength(input);
  std::vector<uint8_t> copy(static_cast<size_t>(size));
  if (size) env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte*>(copy.data()));
  if (env->ExceptionCheck()) throw std::runtime_error("Cannot copy media bytes");
  return copy;
}
}
extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_internal_NativeClient_pushVideo(JNIEnv* env, jobject, jlong value, jbyteArray name,
    jbyteArray pixels, jint width, jint height, jbyteArray metadata, jlong capture) {
  try {
    // Check sizes before copying or calling a permissive FFI that cannot inspect array length.
    const int64_t count = static_cast<int64_t>(width) * height;
    if (!pixels || width <= 0 || height <= 0 || count > INT32_MAX / 4 ||
        env->GetArrayLength(pixels) != count * 4 || capture < -1)
      throw std::invalid_argument("Invalid BGRA dimensions, byte length or capture time");
    auto n = reactor_jni::inputText(env, name);
    auto p = inputBytes(env, pixels), m = inputBytes(env, metadata);
    auto* handle = client(value)->handle;
    if (capture >= 0) reactor_push_video_frame_with_metadata_at(handle, n.data(), p.data(), width, height,
        m.empty() ? nullptr : m.data(), static_cast<uint32_t>(m.size()), capture);
    else if (metadata) reactor_push_video_frame_with_metadata(handle, n.data(), p.data(), width, height,
        m.empty() ? nullptr : m.data(), static_cast<uint32_t>(m.size()));
    else reactor_push_video_frame(handle, n.data(), p.data(), width, height);
  } catch (const std::exception& error) { failure(env, error); }
}
extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_internal_NativeClient_pushAudio(JNIEnv* env, jobject, jlong value, jbyteArray name,
    jshortArray samples, jint rate, jint channels) {
  try {
    const bool valid_rate = rate == 8000 || rate == 16000 || rate == 24000 || rate == 32000 || rate == 44100 || rate == 48000;
    if (!samples || !valid_rate || channels < 1 || channels > 2)
      throw std::invalid_argument("Unsupported PCM sample rate or channel count");
    auto count = env->GetArrayLength(samples);
    if (count % channels) throw std::invalid_argument("PCM requires complete interleaved samples");
    auto n = reactor_jni::inputText(env, name);
    std::vector<int16_t> pcm(static_cast<size_t>(count));
    if (count) env->GetShortArrayRegion(samples, 0, count, pcm.data());
    if (env->ExceptionCheck()) throw std::runtime_error("Cannot copy PCM samples");
    // Empty pushes contain no media. Never manufacture a pointer for the FFI.
    if (count) reactor_push_audio_frame(client(value)->handle, n.data(), pcm.data(), count / channels, rate, channels);
  } catch (const std::exception& error) { failure(env, error); }
}
