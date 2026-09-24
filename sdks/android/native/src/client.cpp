// The handle boundary: creation, teardown, the control-event callbacks, and the string getters.
//
// The object model is Kotlin's; this file is only the crossing. What it is careful about is the
// three ways the crossing ends a process instead of failing a call — a global reference released
// while a callback still holds it, a JNIEnv used on the wrong thread, and a string freed by the
// wrong side.

#include "jni_support.hpp"

#include <memory>

using reactor_jni::JavaString;
using reactor_jni::OwnedString;
using reactor_jni::ScopedEnv;
using reactor_jni::to_jstring;

namespace {

/// What the FFI's `userdata` points at, for the life of a handle and sometimes past it.
///
/// Held by a raw pointer on the FFI side, so its lifetime is ours to manage explicitly. The
/// Kotlin listener is a **global** reference: a local one is valid only for the JNI frame that
/// made it, and every callback here arrives on a thread with no frame at all.
struct Context {
  /// The handle this context belongs to. Kept here rather than handed to Kotlin separately, so
  /// there is one pointer to own and one thing that can be got wrong instead of two that must
  /// agree.
  ReactorHandle* handle = nullptr;
  jobject listener = nullptr;   // GlobalRef
  jclass listener_class = nullptr;  // GlobalRef — method IDs are only valid while the class lives
  jmethodID on_status = nullptr;
  jmethodID on_error = nullptr;
  jmethodID on_session_id = nullptr;
  jmethodID on_video_frame = nullptr;
  jmethodID on_audio_frame = nullptr;

  void release(JNIEnv* env) {
    if (listener != nullptr) env->DeleteGlobalRef(listener);
    if (listener_class != nullptr) env->DeleteGlobalRef(listener_class);
    listener = nullptr;
    listener_class = nullptr;
  }
};

/// Deliver one `(String?) -> Unit` event to the Kotlin listener.
///
/// Every exception is cleared rather than propagated. A pending Java exception on an FFI-owned
/// thread has nowhere to go — the native frame returns into Rust, which has no notion of one, and
/// the next JNI call made with it pending is undefined behaviour. A handler that throws is the
/// host's bug and must not become a process-ending one here.
void dispatch(void* userdata, jmethodID method, const char* value) {
  auto* context = static_cast<Context*>(userdata);
  if (context == nullptr || method == nullptr) return;

  ScopedEnv scoped;
  if (!scoped) return;
  JNIEnv* env = scoped.get();

  // Copied into a Java string before this returns, which is the whole contract for a borrowed
  // string: the FFI frees it the moment the callback returns.
  jstring argument = to_jstring(env, value);
  env->CallVoidMethod(context->listener, method, argument);
  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
  }
  if (argument != nullptr) env->DeleteLocalRef(argument);
}

void on_status(const char* status, void* userdata) {
  dispatch(userdata, static_cast<Context*>(userdata)->on_status, status);
}

void on_error(const char* error_json, void* userdata) {
  dispatch(userdata, static_cast<Context*>(userdata)->on_error, error_json);
}

void on_session_id(const char* session_id_or_null, void* userdata) {
  dispatch(userdata, static_cast<Context*>(userdata)->on_session_id, session_id_or_null);
}

/**
 * Deliver a video frame, inline, on the FFI's own delivery thread.
 *
 * Not marshalled anywhere, and that is the design rather than an omission: while the handler runs
 * the FFI keeps only the newest frame and drops the rest, which is a bounded loss. Handing frames
 * to a queue here would trade that for unbounded latency and memory.
 *
 * The pixels are wrapped in a **direct** ByteBuffer over the FFI's own memory — no copy, and
 * valid only until this returns. Kotlin's contract says the same thing to the caller.
 */
void on_frame(const char* track_name, const uint8_t* data, uint32_t width, uint32_t height,
              uint64_t frame_id, uint64_t timestamp_us, const uint8_t* user_data,
              uint32_t user_data_len, void* userdata) {
  auto* context = static_cast<Context*>(userdata);
  if (context == nullptr || context->on_video_frame == nullptr || data == nullptr) return;

  ScopedEnv scoped;
  if (!scoped) return;
  JNIEnv* env = scoped.get();

  jstring name = to_jstring(env, track_name);
  jobject pixels = env->NewDirectByteBuffer(
      const_cast<uint8_t*>(data), static_cast<jlong>(width) * height * 4);

  // The tag is copied rather than wrapped: it is small, and a caller keeping it is the ordinary
  // case — unlike the pixel buffer, where a copy per frame would be the expensive default.
  jbyteArray tag = nullptr;
  if (user_data != nullptr && user_data_len > 0) {
    tag = env->NewByteArray(static_cast<jsize>(user_data_len));
    env->SetByteArrayRegion(tag, 0, static_cast<jsize>(user_data_len),
                            reinterpret_cast<const jbyte*>(user_data));
  }

  env->CallVoidMethod(context->listener, context->on_video_frame, name, pixels,
                      static_cast<jint>(width), static_cast<jint>(height),
                      static_cast<jlong>(frame_id), static_cast<jlong>(timestamp_us), tag);
  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
  }
  if (tag != nullptr) env->DeleteLocalRef(tag);
  if (pixels != nullptr) env->DeleteLocalRef(pixels);
  if (name != nullptr) env->DeleteLocalRef(name);
}

/// As on_frame, for interleaved int16 PCM. Roughly 10 ms per call.
void on_audio(const char* track_name, const int16_t* samples, uint32_t num_samples,
              uint32_t sample_rate, uint32_t channels, void* userdata) {
  auto* context = static_cast<Context*>(userdata);
  if (context == nullptr || context->on_audio_frame == nullptr || samples == nullptr) return;

  ScopedEnv scoped;
  if (!scoped) return;
  JNIEnv* env = scoped.get();

  jstring name = to_jstring(env, track_name);
  jobject pcm = env->NewDirectByteBuffer(const_cast<int16_t*>(samples),
                                         static_cast<jlong>(num_samples) * 2);

  env->CallVoidMethod(context->listener, context->on_audio_frame, name, pcm,
                      static_cast<jint>(num_samples), static_cast<jint>(sample_rate),
                      static_cast<jint>(channels));
  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
  }
  if (pcm != nullptr) env->DeleteLocalRef(pcm);
  if (name != nullptr) env->DeleteLocalRef(name);
}

/// Where completions are settled: Completions.settleFromNative, resolved once at load.
///
/// Cached rather than looked up per completion, and cached from a thread that has an application
/// class loader — a FindClass from an FFI-owned thread finds the system loader, which cannot see
/// this class.
jclass g_completions_class = nullptr;
jmethodID g_settle = nullptr;

/// Settle one async operation.
///
/// `userdata` carries the ticket and nothing else — no pointer, no reference. A ticket whose
/// entry has already gone (the caller was cancelled, or the client was torn down) is a no-op on
/// the Kotlin side, so a late completion is harmless by construction rather than by timing.
void completion_trampoline(int ok, const char* result_json, const char* error_json,
                           void* userdata) {
  if (g_completions_class == nullptr || g_settle == nullptr) return;

  ScopedEnv scoped;
  if (!scoped) return;
  JNIEnv* env = scoped.get();

  // Both strings are borrowed: the FFI frees them when this returns, so they are copied into
  // Java strings here and not a moment later.
  jstring result = to_jstring(env, result_json);
  jstring error = to_jstring(env, error_json);
  env->CallStaticVoidMethod(g_completions_class, g_settle,
                            static_cast<jlong>(reinterpret_cast<intptr_t>(userdata)),
                            ok != 0 ? JNI_TRUE : JNI_FALSE, result, error);
  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
  }
  if (result != nullptr) env->DeleteLocalRef(result);
  if (error != nullptr) env->DeleteLocalRef(error);
}

/// An owned string from the FFI, handed to Java and freed here.
jstring owned_to_jstring(JNIEnv* env, char* raw) {
  OwnedString owned(raw);
  return to_jstring(env, owned.get());
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL Java_inc_reactor_sdk_android_internal_NativeClient_nativeCreate(
    JNIEnv* env, jobject, jstring api_url, jstring model_name, jstring jwt, jboolean local,
    jobject listener, jstring sdk_version, jstring sdk_type) {
  auto context = std::make_unique<Context>();

  context->listener = env->NewGlobalRef(listener);
  jclass local_class = env->GetObjectClass(listener);
  context->listener_class = static_cast<jclass>(env->NewGlobalRef(local_class));
  env->DeleteLocalRef(local_class);

  // Resolved once, here, on a thread that has a class loader. Looking a class up by name from an
  // FFI-owned thread finds the *system* loader, which cannot see application classes — the
  // classic "works on the main thread, NoClassDefFoundError on a callback" failure.
  context->on_status =
      env->GetMethodID(context->listener_class, "onStatus", "(Ljava/lang/String;)V");
  context->on_error =
      env->GetMethodID(context->listener_class, "onError", "(Ljava/lang/String;)V");
  context->on_session_id =
      env->GetMethodID(context->listener_class, "onSessionId", "(Ljava/lang/String;)V");
  context->on_video_frame = env->GetMethodID(
      context->listener_class, "onVideoFrame",
      "(Ljava/lang/String;Ljava/nio/ByteBuffer;IIJJ[B)V");
  context->on_audio_frame = env->GetMethodID(context->listener_class, "onAudioFrame",
                                             "(Ljava/lang/String;Ljava/nio/ByteBuffer;III)V");

  if (context->on_status == nullptr || context->on_error == nullptr ||
      context->on_session_id == nullptr || context->on_video_frame == nullptr ||
      context->on_audio_frame == nullptr) {
    context->release(env);
    reactor_jni::fail(env, "java/lang/NoSuchMethodError",
                      "NativeEvents is missing a callback method — the Kotlin interface and "
                      "client.cpp have drifted apart");
    return 0;
  }

  ReactorCallbacks callbacks = {};
  callbacks.on_status = on_status;
  callbacks.on_error = on_error;
  callbacks.on_session_id = on_session_id;
  callbacks.on_frame = on_frame;
  callbacks.on_audio = on_audio;
  callbacks.userdata = context.get();

  JavaString url(env, api_url);
  JavaString model(env, model_name);
  JavaString token(env, jwt);
  JavaString version(env, sdk_version);
  JavaString type(env, sdk_type);

  // adm_mode 0 — synthetic, pinned. The platform module opens a real microphone, and a library
  // whose audience is applications must never do that because a model happened to declare a
  // sendonly audio track. reactor_create's env-var default is exactly what this avoids.
  ReactorHandle* handle = reactor_create_with_adm(url.get(), model.get(), token.get(),
                                                  local ? 1 : 0, &callbacks, /*adm_mode=*/0,
                                                  version.get(), type.get());
  if (handle == nullptr) {
    context->release(env);
    return 0;
  }

  context->handle = handle;
  // The FFI holds a raw pointer to this for the life of the handle. Ownership moves to Kotlin,
  // which hands it back to nativeDestroy — and which keeps it alive forever if teardown says a
  // callback is still running.
  return reinterpret_cast<jlong>(context.release());
}

/**
 * Destroy the handle and report whether the context may be released.
 *
 * Returns what reactor_destroy returned: 0 means quiesced, -1 means a callback is still in
 * flight. Releasing the global references on -1 is a use-after-free — a callback already running
 * holds them — so this does not release them, and Kotlin does not either. The deliberate leak is
 * the correct answer; see NativeClient.
 */
extern "C" JNIEXPORT jint JNICALL Java_inc_reactor_sdk_android_internal_NativeClient_nativeDestroy(
    JNIEnv* env, jobject, jlong context_ptr) {
  auto* context = reinterpret_cast<Context*>(context_ptr);
  if (context == nullptr) return 0;
  const int result = reactor_destroy(context->handle);
  if (result == 0 && context != nullptr) {
    context->release(env);
    delete context;
  }
  return result;
}

/// The one static string in the ABI. Never freed: it is a literal, and reactor_free_string on it
/// corrupts the heap.
extern "C" JNIEXPORT jstring JNICALL Java_inc_reactor_sdk_android_internal_NativeClient_nativeStatus(
    JNIEnv* env, jobject, jlong context_ptr) {
  auto* context = reinterpret_cast<Context*>(context_ptr);
  if (context == nullptr) return nullptr;
  return to_jstring(env, reactor_status(context->handle));
}

extern "C" JNIEXPORT jstring JNICALL Java_inc_reactor_sdk_android_internal_NativeClient_nativeSessionId(
    JNIEnv* env, jobject, jlong context_ptr) {
  auto* context = reinterpret_cast<Context*>(context_ptr);
  if (context == nullptr) return nullptr;
  return owned_to_jstring(env, reactor_session_id(context->handle));
}

extern "C" JNIEXPORT jstring JNICALL Java_inc_reactor_sdk_android_internal_NativeClient_nativeTracks(
    JNIEnv* env, jobject, jlong context_ptr) {
  auto* context = reinterpret_cast<Context*>(context_ptr);
  if (context == nullptr) return nullptr;
  return owned_to_jstring(env, reactor_tracks(context->handle));
}

extern "C" JNIEXPORT jstring JNICALL Java_inc_reactor_sdk_android_internal_NativeClient_nativePausedTracks(
    JNIEnv* env, jobject, jlong context_ptr) {
  auto* context = reinterpret_cast<Context*>(context_ptr);
  if (context == nullptr) return nullptr;
  return owned_to_jstring(env, reactor_paused_tracks(context->handle));
}


/**
 * Cache the completion entry point. Called once, from Kotlin, on a thread with a class loader
 * that can see application classes.
 */
extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_android_internal_NativeClient_nativeInitCompletions(JNIEnv* env, jobject,
                                                                        jclass completions) {
  g_completions_class = static_cast<jclass>(env->NewGlobalRef(completions));
  g_settle = env->GetStaticMethodID(g_completions_class, "settleFromNative",
                                    "(JZLjava/lang/String;Ljava/lang/String;)V");
  if (g_settle == nullptr) {
    reactor_jni::fail(env, "java/lang/NoSuchMethodError",
                      "Completions.settleFromNative is missing — the Kotlin registry and "
                      "client.cpp have drifted apart");
  }
}

extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_android_internal_NativeClient_nativeConnect(JNIEnv* env, jobject,
                                                                 jlong context_ptr,
                                                                 jstring session_id,
                                                                 jlong ticket) {
  auto* context = reinterpret_cast<Context*>(context_ptr);
  if (context == nullptr) return;
  JavaString session(env, session_id);
  reactor_connect(context->handle, session.get(), /*connection_id=*/nullptr,
                  completion_trampoline, reinterpret_cast<void*>(static_cast<intptr_t>(ticket)));
}

extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_android_internal_NativeClient_nativeDisconnect(JNIEnv*, jobject,
                                                                    jlong context_ptr,
                                                                    jlong ticket) {
  auto* context = reinterpret_cast<Context*>(context_ptr);
  if (context == nullptr) return;
  reactor_disconnect(context->handle, completion_trampoline,
                     reinterpret_cast<void*>(static_cast<intptr_t>(ticket)));
}

extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_android_internal_NativeClient_nativeReconnect(JNIEnv*, jobject,
                                                                   jlong context_ptr,
                                                                   jlong ticket) {
  auto* context = reinterpret_cast<Context*>(context_ptr);
  if (context == nullptr) return;
  reactor_reconnect(context->handle, completion_trampoline,
                    reinterpret_cast<void*>(static_cast<intptr_t>(ticket)));
}

// ── Publishing and media ─────────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_android_internal_NativeClient_nativePublishTrack(JNIEnv* env, jobject,
                                                                      jlong context_ptr,
                                                                      jstring name, jlong ticket) {
  auto* context = reinterpret_cast<Context*>(context_ptr);
  if (context == nullptr) return;
  JavaString track(env, name);
  reactor_publish_track(context->handle, track.get(), completion_trampoline,
                        reinterpret_cast<void*>(static_cast<intptr_t>(ticket)));
}

/**
 * Unpublish, which is synchronous and returns an error object rather than completing.
 *
 * That error string is the fourth owned-string case in this ABI: heap-allocated on failure, NULL
 * on success, and the caller frees it. OwnedString is what keeps that from being the leak nobody
 * notices, since the success path allocates nothing.
 */
extern "C" JNIEXPORT jstring JNICALL
Java_inc_reactor_sdk_android_internal_NativeClient_nativeUnpublishTrack(JNIEnv* env, jobject,
                                                                        jlong context_ptr,
                                                                        jstring name) {
  auto* context = reinterpret_cast<Context*>(context_ptr);
  if (context == nullptr) return nullptr;
  JavaString track(env, name);
  return owned_to_jstring(env, reactor_unpublish_track(context->handle, track.get()));
}

extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_android_internal_NativeClient_nativePauseTrack(JNIEnv* env, jobject,
                                                                    jlong context_ptr, jstring name,
                                                                    jlong ticket) {
  auto* context = reinterpret_cast<Context*>(context_ptr);
  if (context == nullptr) return;
  JavaString track(env, name);
  reactor_pause_track(context->handle, track.get(), completion_trampoline,
                      reinterpret_cast<void*>(static_cast<intptr_t>(ticket)));
}

extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_android_internal_NativeClient_nativeResumeTrack(JNIEnv* env, jobject,
                                                                     jlong context_ptr,
                                                                     jstring name, jlong ticket) {
  auto* context = reinterpret_cast<Context*>(context_ptr);
  if (context == nullptr) return;
  JavaString track(env, name);
  reactor_resume_track(context->handle, track.get(), completion_trampoline,
                       reinterpret_cast<void*>(static_cast<intptr_t>(ticket)));
}

/**
 * Push BGRA pixels, optionally tagged.
 *
 * The buffer is read straight out of a direct ByteBuffer — no copy on this side either, so a
 * caller pushing at 30 fps pays for no allocation here. Kotlin has already checked that its
 * length is exactly width * height * 4; this reads that many bytes and nothing checks it again,
 * which is why that check is not optional up there.
 */
extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_android_internal_NativeClient_nativePushVideoFrame(
    JNIEnv* env, jobject, jlong context_ptr, jstring name, jobject pixels, jint width, jint height,
    jbyteArray user_data) {
  auto* context = reinterpret_cast<Context*>(context_ptr);
  if (context == nullptr || pixels == nullptr) return;

  auto* data = static_cast<const uint8_t*>(env->GetDirectBufferAddress(pixels));
  if (data == nullptr) {
    reactor_jni::fail(env, "java/lang/IllegalArgumentException",
                      "pushFrame needs a direct ByteBuffer — allocateDirect(), not allocate()");
    return;
  }

  JavaString track(env, name);
  if (user_data == nullptr) {
    reactor_push_video_frame(context->handle, track.get(), data, static_cast<uint32_t>(width),
                             static_cast<uint32_t>(height));
    return;
  }

  const jsize tag_len = env->GetArrayLength(user_data);
  jbyte* tag = env->GetByteArrayElements(user_data, nullptr);
  reactor_push_video_frame_with_metadata(
      context->handle, track.get(), data, static_cast<uint32_t>(width),
      static_cast<uint32_t>(height), reinterpret_cast<const uint8_t*>(tag),
      static_cast<uint32_t>(tag_len));
  env->ReleaseByteArrayElements(user_data, tag, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_android_internal_NativeClient_nativePushAudioFrame(
    JNIEnv* env, jobject, jlong context_ptr, jstring name, jobject pcm, jint samplesPerChannel,
    jint sampleRate, jint channels) {
  auto* context = reinterpret_cast<Context*>(context_ptr);
  if (context == nullptr || pcm == nullptr) return;

  auto* data = static_cast<const int16_t*>(env->GetDirectBufferAddress(pcm));
  if (data == nullptr) {
    reactor_jni::fail(env, "java/lang/IllegalArgumentException",
                      "pushFrame needs a direct ByteBuffer — allocateDirect(), not allocate()");
    return;
  }

  JavaString track(env, name);
  reactor_push_audio_frame(context->handle, track.get(), data,
                           static_cast<uint32_t>(samplesPerChannel),
                           static_cast<uint32_t>(sampleRate), static_cast<uint32_t>(channels));
}

// ── Commands, schema and statistics ──────────────────────────────────────────

/**
 * Send a command and wait for its **correlated** reply.
 *
 * The correlation is the FFI's, not ours: the completion fires for this command and no other.
 * A binding that instead sent and then waited for a message event would be racing a reply that
 * may already have arrived — the classic hang at this boundary.
 */
extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_android_internal_NativeClient_nativeSendCommand(
    JNIEnv* env, jobject, jlong context_ptr, jstring name, jstring args_json, jstring uploads_json,
    jlong ticket) {
  auto* context = reinterpret_cast<Context*>(context_ptr);
  if (context == nullptr) return;
  JavaString command(env, name);
  JavaString args(env, args_json);
  JavaString uploads(env, uploads_json);
  reactor_send_command(context->handle, command.get(), args.get(), uploads.get(),
                       completion_trampoline,
                       reinterpret_cast<void*>(static_cast<intptr_t>(ticket)));
}

extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_android_internal_NativeClient_nativeRequestSchema(JNIEnv*, jobject,
                                                                       jlong context_ptr,
                                                                       jlong ticket) {
  auto* context = reinterpret_cast<Context*>(context_ptr);
  if (context == nullptr) return;
  reactor_request_schema(context->handle, completion_trampoline,
                         reinterpret_cast<void*>(static_cast<intptr_t>(ticket)));
}

extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_android_internal_NativeClient_nativeGetStats(JNIEnv*, jobject,
                                                                  jlong context_ptr, jlong ticket) {
  auto* context = reinterpret_cast<Context*>(context_ptr);
  if (context == nullptr) return;
  reactor_get_stats(context->handle, completion_trampoline,
                    reinterpret_cast<void*>(static_cast<intptr_t>(ticket)));
}

// ── Uploads ──────────────────────────────────────────────────────────────────

extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_android_internal_NativeClient_nativeUploadFile(JNIEnv* env, jobject,
                                                                    jlong context_ptr, jstring path,
                                                                    jlong ticket) {
  auto* context = reinterpret_cast<Context*>(context_ptr);
  if (context == nullptr) return;
  JavaString file(env, path);
  reactor_upload_file(context->handle, file.get(), completion_trampoline,
                      reinterpret_cast<void*>(static_cast<intptr_t>(ticket)));
}

/**
 * Upload bytes the caller already holds.
 *
 * `data` is borrowed for the call only, which is why this takes a direct ByteBuffer and reads it
 * in place: copying would double the peak memory for exactly the case — a large in-memory
 * payload — where that matters most.
 */
extern "C" JNIEXPORT void JNICALL
Java_inc_reactor_sdk_android_internal_NativeClient_nativeUploadBytes(
    JNIEnv* env, jobject, jlong context_ptr, jobject data, jint length, jstring name,
    jstring mime_type, jlong ticket) {
  auto* context = reinterpret_cast<Context*>(context_ptr);
  if (context == nullptr || data == nullptr) return;

  auto* bytes = static_cast<const uint8_t*>(env->GetDirectBufferAddress(data));
  if (bytes == nullptr) {
    reactor_jni::fail(env, "java/lang/IllegalArgumentException",
                      "uploadBytes needs a direct ByteBuffer — allocateDirect(), not allocate()");
    return;
  }

  JavaString file_name(env, name);
  JavaString mime(env, mime_type);
  reactor_upload_bytes(context->handle, bytes, static_cast<size_t>(length), file_name.get(),
                       mime.get(), completion_trampoline,
                       reinterpret_cast<void*>(static_cast<intptr_t>(ticket)));
}
