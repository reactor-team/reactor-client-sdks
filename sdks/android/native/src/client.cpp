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

  if (context->on_status == nullptr || context->on_error == nullptr ||
      context->on_session_id == nullptr) {
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
