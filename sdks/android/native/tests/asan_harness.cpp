// The host-side sanitizer harness for the JNI bridge.
//
// Runs the real client.cpp against a fake libreactor_ffi, inside a JVM this binary creates, with
// AddressSanitizer watching. That combination is what makes the lifetime rules testable at all:
//
//   * the instrumented suite on a device exercises these paths against the *real* library, which
//     is evidence but not proof — a callback writing through a freed pointer is invisible in a
//     passing run, which is the entire reason a sanitizer is the tool for this;
//   * and the real library cannot be asked for reactor_destroy == -1, the one case where leaking
//     the global references is the correct answer.
//
// The JNI entry points are called directly rather than through System.loadLibrary: this binary
// links client.cpp, so the functions are right here. JNI_OnLoad is therefore called by hand — it
// is what captures the JavaVM that every foreign-thread callback needs.

#include <jni.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

extern "C" {
jint JNI_OnLoad(JavaVM* vm, void* reserved);

jlong Java_inc_reactor_sdk_android_internal_NativeClient_nativeCreate(
    JNIEnv*, jobject, jstring, jstring, jstring, jboolean, jobject, jstring, jstring);
jint Java_inc_reactor_sdk_android_internal_NativeClient_nativeDestroy(JNIEnv*, jobject, jlong);
jstring Java_inc_reactor_sdk_android_internal_NativeClient_nativeStatus(JNIEnv*, jobject, jlong);
jstring Java_inc_reactor_sdk_android_internal_NativeClient_nativeSessionId(JNIEnv*, jobject, jlong);
jstring Java_inc_reactor_sdk_android_internal_NativeClient_nativeTracks(JNIEnv*, jobject, jlong);
jstring Java_inc_reactor_sdk_android_internal_NativeClient_nativePausedTracks(JNIEnv*, jobject,
                                                                             jlong);

void fake_set_destroy_result(int result);
void fake_fire_status_on_foreign_thread(const char* status);
void fake_fire_session_id_on_foreign_thread(const char* session_id);
}

namespace {

int g_failures = 0;

void check(bool condition, const char* what) {
  if (condition) {
    std::printf("  ok    %s\n", what);
  } else {
    std::printf("  FAIL  %s\n", what);
    g_failures += 1;
  }
}

JNIEnv* g_env = nullptr;
jclass g_listener_class = nullptr;

jobject new_listener() {
  jmethodID ctor = g_env->GetMethodID(g_listener_class, "<init>", "()V");
  return g_env->NewObject(g_listener_class, ctor);
}

jint static_int(const char* field) {
  jfieldID id = g_env->GetStaticFieldID(g_listener_class, field, "I");
  return g_env->GetStaticIntField(g_listener_class, id);
}

void set_static_bool(const char* field, bool value) {
  jfieldID id = g_env->GetStaticFieldID(g_listener_class, field, "Z");
  g_env->SetStaticBooleanField(g_listener_class, id, value ? JNI_TRUE : JNI_FALSE);
}

jlong create() {
  jstring url = g_env->NewStringUTF("https://api.invalid");
  jstring model = g_env->NewStringUTF("reactor/echo");
  jstring version = g_env->NewStringUTF("0.0.0-asan");
  jstring type = g_env->NewStringUTF("android");
  jlong context = Java_inc_reactor_sdk_android_internal_NativeClient_nativeCreate(
      g_env, nullptr, url, model, nullptr, JNI_FALSE, new_listener(), version, type);
  g_env->DeleteLocalRef(url);
  g_env->DeleteLocalRef(model);
  g_env->DeleteLocalRef(version);
  g_env->DeleteLocalRef(type);
  return context;
}

/// The owned strings, read enough times that a missing free is a leak ASan reports at exit and a
/// double free is an immediate error.
void owned_strings_are_freed_exactly_once() {
  jlong context = create();
  for (int i = 0; i < 500; ++i) {
    jstring tracks = Java_inc_reactor_sdk_android_internal_NativeClient_nativeTracks(g_env, nullptr, context);
    jstring paused = Java_inc_reactor_sdk_android_internal_NativeClient_nativePausedTracks(g_env, nullptr, context);
    if (tracks != nullptr) g_env->DeleteLocalRef(tracks);
    if (paused != nullptr) g_env->DeleteLocalRef(paused);
  }
  fake_set_destroy_result(0);
  check(Java_inc_reactor_sdk_android_internal_NativeClient_nativeDestroy(g_env, nullptr, context) == 0,
        "500 owned-string reads, then a quiesced destroy");
}

/// The static string. If the bridge ever passed it to reactor_free_string, this is a free() of a
/// pointer that was never allocated — ASan's "attempting free on address which was not malloc()".
void the_static_string_is_never_freed() {
  jlong context = create();
  for (int i = 0; i < 500; ++i) {
    jstring status = Java_inc_reactor_sdk_android_internal_NativeClient_nativeStatus(g_env, nullptr, context);
    if (status != nullptr) g_env->DeleteLocalRef(status);
  }
  fake_set_destroy_result(0);
  Java_inc_reactor_sdk_android_internal_NativeClient_nativeDestroy(g_env, nullptr, context);
  check(true, "500 static-string reads without a free");
}

/// Callbacks on a thread the JVM has never seen. Without ScopedEnv attaching, this is a null env
/// dereference; detaching a thread it did not attach would corrupt the caller's.
void events_cross_from_a_foreign_thread() {
  jlong context = create();
  const jint before = static_int("statusCount");
  fake_fire_status_on_foreign_thread("connecting");
  fake_fire_status_on_foreign_thread("ready");
  const jint after = static_int("statusCount");
  check(after == before + 2, "two events delivered from FFI-owned threads");

  // A null session id is an answer, not an absence, and must arrive as a Java null.
  fake_fire_session_id_on_foreign_thread(nullptr);
  jfieldID id = g_env->GetStaticFieldID(g_listener_class, "lastSessionIdWasNull", "Z");
  check(g_env->GetStaticBooleanField(g_listener_class, id) == JNI_TRUE,
        "a null session id arrives as Java null rather than as \"\"");

  fake_set_destroy_result(0);
  Java_inc_reactor_sdk_android_internal_NativeClient_nativeDestroy(g_env, nullptr, context);
}

/// A handler that throws. The bridge must clear the pending exception before returning into the
/// fake's C++ frame; returning with one pending makes the next JNI call undefined.
void a_throwing_handler_is_contained() {
  jlong context = create();
  set_static_bool("throwFromHandlers", true);
  fake_fire_status_on_foreign_thread("ready");
  set_static_bool("throwFromHandlers", false);
  check(g_env->ExceptionCheck() == JNI_FALSE,
        "no exception is pending on the calling thread after a handler threw");
  fake_set_destroy_result(0);
  Java_inc_reactor_sdk_android_internal_NativeClient_nativeDestroy(g_env, nullptr, context);
}

/// destroy == -1. The references must NOT be released: a callback still running holds them, and
/// freeing them here is the use-after-free this whole harness exists to catch. Firing an event
/// afterwards is what would trip it.
void destroy_minus_one_keeps_the_references_alive() {
  jlong context = create();
  fake_set_destroy_result(-1);
  const jint result =
      Java_inc_reactor_sdk_android_internal_NativeClient_nativeDestroy(g_env, nullptr, context);
  check(result == -1, "destroy reports -1 to the caller");

  // The callback still holds the global references. If nativeDestroy had released them, this
  // dispatch reads freed memory — which is the point of running under a sanitizer.
  const jint before = static_int("statusCount");
  fake_fire_status_on_foreign_thread("late");
  check(static_int("statusCount") == before + 1,
        "a callback arriving after destroy == -1 still reaches its listener");
  fake_set_destroy_result(0);
}

}  // namespace

int main() {
  const char* classpath = std::getenv("REACTOR_ASAN_CLASSPATH");
  if (classpath == nullptr) {
    std::fprintf(stderr, "REACTOR_ASAN_CLASSPATH is unset\n");
    return 2;
  }
  std::string option = std::string("-Djava.class.path=") + classpath;

  JavaVMOption options[1];
  options[0].optionString = const_cast<char*>(option.c_str());
  JavaVMInitArgs args{};
  args.version = JNI_VERSION_1_8;
  args.nOptions = 1;
  args.options = options;

  JavaVM* vm = nullptr;
  if (JNI_CreateJavaVM(&vm, reinterpret_cast<void**>(&g_env), &args) != JNI_OK) {
    std::fprintf(stderr, "could not create a JVM\n");
    return 2;
  }
  JNI_OnLoad(vm, nullptr);

  jclass local = g_env->FindClass("AsanListener");
  if (local == nullptr) {
    std::fprintf(stderr, "AsanListener is not on the classpath (%s)\n", classpath);
    return 2;
  }
  g_listener_class = static_cast<jclass>(g_env->NewGlobalRef(local));
  g_env->DeleteLocalRef(local);

  std::printf("JNI boundary, under AddressSanitizer:\n");
  owned_strings_are_freed_exactly_once();
  the_static_string_is_never_freed();
  events_cross_from_a_foreign_thread();
  a_throwing_handler_is_contained();
  destroy_minus_one_keeps_the_references_alive();

  std::printf("%s\n", g_failures == 0 ? "all checks passed" : "CHECKS FAILED");
  return g_failures == 0 ? 0 : 1;
}
