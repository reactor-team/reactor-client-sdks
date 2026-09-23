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

#include <cstdint>
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

void Java_inc_reactor_sdk_android_internal_NativeClient_nativeInitCompletions(JNIEnv*, jobject,
                                                                             jclass);
void Java_inc_reactor_sdk_android_internal_NativeClient_nativeConnect(JNIEnv*, jobject, jlong,
                                                                     jstring, jlong);
void fake_set_destroy_result(int result);
void fake_complete_last_on_foreign_thread(int ok, const char* result_json, const char* error_json);
void fake_fire_video_frame_on_foreign_thread(const char* track, uint32_t width, uint32_t height,
                                             uint64_t frame_id);
void fake_set_unpublish_fails(int fails);
uint32_t fake_pushed_video_frames(void);
size_t fake_uploaded_checksum(void);
const char* fake_last_command_args(void);
void Java_inc_reactor_sdk_android_internal_NativeClient_nativeUploadBytes(
    JNIEnv*, jobject, jlong, jobject, jint, jstring, jstring, jlong);
const char* fake_last_command_uploads(void);
void Java_inc_reactor_sdk_android_internal_NativeClient_nativeSendCommand(
    JNIEnv*, jobject, jlong, jstring, jstring, jstring, jlong);
jstring Java_inc_reactor_sdk_android_internal_NativeClient_nativeUnpublishTrack(JNIEnv*, jobject,
                                                                               jlong, jstring);
void Java_inc_reactor_sdk_android_internal_NativeClient_nativePushVideoFrame(
    JNIEnv*, jobject, jlong, jstring, jobject, jint, jint, jbyteArray);
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

/// The bridge must ask for the synthetic audio module and nothing else.
///
/// `reactor_create_with_adm`'s other mode opens a real microphone, and `reactor_create` takes its
/// mode from an environment variable — which is how a library whose audience is applications ends
/// up with live microphone audio on the wire because a model declared a sendonly audio track. The
/// fake refuses any mode but 0, so a bridge that ever passed 1 gets a null handle here.
void the_synthetic_audio_module_is_pinned() {
  jlong context = create();
  check(context != 0,
        "create asked for the synthetic ADM (the fake refuses every other mode)");
  fake_set_destroy_result(0);
  Java_inc_reactor_sdk_android_internal_NativeClient_nativeDestroy(g_env, nullptr, context);
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

/// A video frame, delivered inline on an FFI-owned thread over a direct buffer.
///
/// The fake frees the pixels the moment the callback returns, so a binding that handed Kotlin
/// anything but a view valid for exactly that call would be reading freed memory here.
void video_frames_arrive_over_a_direct_buffer() {
  jlong context = create();
  const jint before = static_int("videoFrameCount");
  fake_fire_video_frame_on_foreign_thread("main_video", 64, 48, 99);
  check(static_int("videoFrameCount") == before + 1, "a video frame reached its listener");
  check(static_int("lastWidth") == 64 && static_int("lastHeight") == 48,
        "the frame's dimensions survive the crossing");
  check(static_int("lastPixelCapacity") == 64 * 48 * 4,
        "the buffer spans exactly width * height * 4 bytes");

  jfieldID direct_id = g_env->GetStaticFieldID(g_listener_class, "lastBufferWasDirect", "Z");
  check(g_env->GetStaticBooleanField(g_listener_class, direct_id) == JNI_TRUE,
        "pixels arrive as a direct buffer rather than a copy");
  check(static_int("lastTagLength") == 3, "the per-frame tag is copied, not borrowed");

  jfieldID frame_id = g_env->GetStaticFieldID(g_listener_class, "lastFrameId", "J");
  check(g_env->GetStaticLongField(g_listener_class, frame_id) == 99,
        "the trailer's frame id survives the crossing");

  fake_set_destroy_result(0);
  Java_inc_reactor_sdk_android_internal_NativeClient_nativeDestroy(g_env, nullptr, context);
}

/// Pushing reads the whole buffer, and unpublish's error string is owned.
///
/// The fake reads the *last* byte of every pushed frame, so a direct buffer shorter than
/// width * height * 4 — or one that was never direct — is a read past the end that ASan reports
/// rather than a silent misread. And reactor_unpublish_track is the fourth owned-string case in
/// this ABI: heap on failure, NULL on success. Exercising both paths is what would catch either
/// a leak on the failure path or a free of the NULL success path.
void pushing_reads_the_whole_buffer_and_unpublish_owns_its_error() {
  jlong context = create();

  jstring track = g_env->NewStringUTF("webcam");
  const int capacity = 8 * 8 * 4;
  void* raw = std::malloc(capacity);
  std::memset(raw, 0x5A, capacity);
  jobject pixels = g_env->NewDirectByteBuffer(raw, capacity);

  const uint32_t before = fake_pushed_video_frames();
  for (int i = 0; i < 200; ++i) {
    Java_inc_reactor_sdk_android_internal_NativeClient_nativePushVideoFrame(
        g_env, nullptr, context, track, pixels, 8, 8, nullptr);
  }
  check(fake_pushed_video_frames() == before + 200,
        "200 frames pushed, every byte of each one read back");
  g_env->DeleteLocalRef(pixels);
  std::free(raw);

  // Success: no string to free. Doing so anyway would be a free of NULL's worth of nothing, or
  // worse, of a pointer the FFI still owns.
  fake_set_unpublish_fails(0);
  jstring ok = Java_inc_reactor_sdk_android_internal_NativeClient_nativeUnpublishTrack(
      g_env, nullptr, context, track);
  check(ok == nullptr, "a successful unpublish returns no error and allocates nothing");

  // Failure: a heap string the bridge owns and must free exactly once.
  fake_set_unpublish_fails(1);
  for (int i = 0; i < 100; ++i) {
    jstring err = Java_inc_reactor_sdk_android_internal_NativeClient_nativeUnpublishTrack(
        g_env, nullptr, context, track);
    if (err != nullptr) g_env->DeleteLocalRef(err);
  }
  check(true, "100 failing unpublishes, each error string freed exactly once");
  fake_set_unpublish_fails(0);

  g_env->DeleteLocalRef(track);
  fake_set_destroy_result(0);
  Java_inc_reactor_sdk_android_internal_NativeClient_nativeDestroy(g_env, nullptr, context);
}

/// An uploaded buffer is borrowed for the call, and read whole.
///
/// The fake sums every byte, so a length that does not match the buffer reads past the end and
/// ASan says so — where a real platform would simply upload the wrong bytes and no one would
/// know until the model complained.
void uploaded_bytes_are_read_whole_while_borrowed() {
  jlong context = create();
  const int size = 32 * 1024;
  void* raw = std::malloc(size);
  std::memset(raw, 0x01, size);
  jobject buffer = g_env->NewDirectByteBuffer(raw, size);
  jstring name = g_env->NewStringUTF("payload.bin");

  Java_inc_reactor_sdk_android_internal_NativeClient_nativeUploadBytes(
      g_env, nullptr, context, buffer, size, name, nullptr, /*ticket=*/11);
  check(fake_uploaded_checksum() == static_cast<size_t>(size),
        "every byte of the uploaded buffer was read, and none beyond it");

  g_env->DeleteLocalRef(name);
  g_env->DeleteLocalRef(buffer);
  std::free(raw);
  fake_set_destroy_result(0);
  Java_inc_reactor_sdk_android_internal_NativeClient_nativeDestroy(g_env, nullptr, context);
}

/// A nullable argument must reach the ABI as NULL, not as "null" or "".
///
/// The header says args_json and uploads_json are nullable and that NULL means "{}"/none. A
/// binding that stringified a Kotlin null would send the four characters n-u-l-l, which the
/// platform would try to parse as arguments — a silent misbehaviour rather than a crash, and so
/// exactly the kind this harness exists to make loud.
void nullable_command_arguments_arrive_as_null() {
  jlong context = create();
  jstring name = g_env->NewStringUTF("get_status");

  Java_inc_reactor_sdk_android_internal_NativeClient_nativeSendCommand(
      g_env, nullptr, context, name, nullptr, nullptr, /*ticket=*/7);
  check(std::strcmp(fake_last_command_args(), "<null>") == 0,
        "a null args object reaches the ABI as NULL, not as \"null\"");
  check(std::strcmp(fake_last_command_uploads(), "<null>") == 0,
        "a null uploads object reaches the ABI as NULL");

  jstring args = g_env->NewStringUTF("{\"fps\":30}");
  Java_inc_reactor_sdk_android_internal_NativeClient_nativeSendCommand(
      g_env, nullptr, context, name, args, nullptr, /*ticket=*/8);
  check(std::strcmp(fake_last_command_args(), "{\"fps\":30}") == 0,
        "a real args object crosses unchanged");
  g_env->DeleteLocalRef(args);
  g_env->DeleteLocalRef(name);

  fake_set_destroy_result(0);
  Java_inc_reactor_sdk_android_internal_NativeClient_nativeDestroy(g_env, nullptr, context);
}

/// The completion path, end to end on the native side: a completion fires on a thread the JVM has
/// never seen, and both of its strings are copied before the FFI frees them.
void completions_cross_from_a_foreign_thread() {
  jclass local = g_env->FindClass("AsanCompletions");
  if (local == nullptr) {
    check(false, "AsanCompletions is on the classpath");
    return;
  }
  jclass completions = static_cast<jclass>(g_env->NewGlobalRef(local));
  g_env->DeleteLocalRef(local);
  Java_inc_reactor_sdk_android_internal_NativeClient_nativeInitCompletions(g_env, nullptr,
                                                                          completions);

  jlong context = create();
  Java_inc_reactor_sdk_android_internal_NativeClient_nativeConnect(g_env, nullptr, context,
                                                                  nullptr, /*ticket=*/4242);
  fake_complete_last_on_foreign_thread(1, "{\"ok\":true}", nullptr);

  jfieldID ticket_id = g_env->GetStaticFieldID(completions, "lastTicket", "J");
  jfieldID result_id = g_env->GetStaticFieldID(completions, "lastResult", "Ljava/lang/String;");
  jfieldID error_id = g_env->GetStaticFieldID(completions, "lastError", "Ljava/lang/String;");

  check(g_env->GetStaticLongField(completions, ticket_id) == 4242,
        "the ticket survives the round trip through userdata");

  auto result = static_cast<jstring>(g_env->GetStaticObjectField(completions, result_id));
  const char* chars = result == nullptr ? nullptr : g_env->GetStringUTFChars(result, nullptr);
  check(chars != nullptr && std::strcmp(chars, "{\"ok\":true}") == 0,
        "result_json is copied before the FFI frees it");
  if (chars != nullptr) g_env->ReleaseStringUTFChars(result, chars);

  check(g_env->GetStaticObjectField(completions, error_id) == nullptr,
        "a null error_json arrives as Java null rather than as \"\"");

  fake_set_destroy_result(0);
  Java_inc_reactor_sdk_android_internal_NativeClient_nativeDestroy(g_env, nullptr, context);
  g_env->DeleteGlobalRef(completions);
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
  the_synthetic_audio_module_is_pinned();
  owned_strings_are_freed_exactly_once();
  the_static_string_is_never_freed();
  events_cross_from_a_foreign_thread();
  a_throwing_handler_is_contained();
  video_frames_arrive_over_a_direct_buffer();
  pushing_reads_the_whole_buffer_and_unpublish_owns_its_error();
  uploaded_bytes_are_read_whole_while_borrowed();
  nullable_command_arguments_arrive_as_null();
  completions_cross_from_a_foreign_thread();
  destroy_minus_one_keeps_the_references_alive();

  std::printf("%s\n", g_failures == 0 ? "all checks passed" : "CHECKS FAILED");
  return g_failures == 0 ? 0 : 1;
}
