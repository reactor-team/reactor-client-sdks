// The step result accessors, one to one with reactor_step_result_*.
//
// Called from Kotlin's StepResults.read, on the native completion thread, while step_trampoline
// still holds the result: it frees it as soon as Completions.settleStepFromNative returns. So
// every pointer an accessor answers is copied into a Java object here and never handed out —
// a frame's bytes included, which become a byte[] once, rather than a direct buffer over memory
// that is about to go.

#include "jni_support.hpp"

#include <climits>

using reactor_jni::to_jstring;

namespace {

const ReactorStepResult* result_of(jlong result) {
  return reinterpret_cast<const ReactorStepResult*>(static_cast<intptr_t>(result));
}

jbyteArray to_jbytes(JNIEnv* env, const uint8_t* data, size_t len) {
  if (len > static_cast<size_t>(INT32_MAX)) {
    reactor_jni::fail(env, "java/lang/IllegalStateException",
                      "a step frame is larger than a Java array can hold");
    return nullptr;
  }
  jbyteArray array = env->NewByteArray(static_cast<jsize>(len));
  if (array == nullptr) return nullptr;  // OutOfMemoryError pending
  if (len > 0) {
    env->SetByteArrayRegion(array, 0, static_cast<jsize>(len),
                            reinterpret_cast<const jbyte*>(data));
  }
  return array;
}

}  // namespace

#define STEP_RESULTS(name) Java_inc_reactor_sdk_android_internal_NativeStepResults_##name

extern "C" JNIEXPORT jlong JNICALL STEP_RESULTS(nativeStep)(JNIEnv*, jobject, jlong result) {
  return static_cast<jlong>(reactor_step_result_step(result_of(result)));
}

extern "C" JNIEXPORT jdouble JNICALL STEP_RESULTS(nativeElapsed)(JNIEnv*, jobject, jlong result) {
  return reactor_step_result_elapsed(result_of(result));
}

extern "C" JNIEXPORT jstring JNICALL STEP_RESULTS(nativeError)(JNIEnv* env, jobject,
                                                               jlong result) {
  return to_jstring(env, reactor_step_result_error(result_of(result)));
}

extern "C" JNIEXPORT jint JNICALL STEP_RESULTS(nativeFileCount)(JNIEnv*, jobject, jlong result) {
  return static_cast<jint>(reactor_step_result_file_count(result_of(result)));
}

extern "C" JNIEXPORT jstring JNICALL STEP_RESULTS(nativeFileName)(JNIEnv* env, jobject,
                                                                  jlong result, jint file) {
  return to_jstring(env,
                    reactor_step_result_file_name(result_of(result), static_cast<uint32_t>(file)));
}

extern "C" JNIEXPORT jstring JNICALL STEP_RESULTS(nativeFileContentType)(JNIEnv* env, jobject,
                                                                         jlong result, jint file) {
  return to_jstring(env, reactor_step_result_file_content_type(result_of(result),
                                                               static_cast<uint32_t>(file)));
}

extern "C" JNIEXPORT jlong JNICALL STEP_RESULTS(nativeFileSize)(JNIEnv*, jobject, jlong result,
                                                                jint file) {
  return static_cast<jlong>(
      reactor_step_result_file_size(result_of(result), static_cast<uint32_t>(file)));
}

extern "C" JNIEXPORT jint JNICALL STEP_RESULTS(nativeTrackCount)(JNIEnv*, jobject, jlong result) {
  return static_cast<jint>(reactor_step_result_track_count(result_of(result)));
}

extern "C" JNIEXPORT jstring JNICALL STEP_RESULTS(nativeTrackName)(JNIEnv* env, jobject,
                                                                   jlong result, jint track) {
  return to_jstring(
      env, reactor_step_result_track_name(result_of(result), static_cast<uint32_t>(track)));
}

extern "C" JNIEXPORT jstring JNICALL STEP_RESULTS(nativeTrackKind)(JNIEnv* env, jobject,
                                                                   jlong result, jint track) {
  return to_jstring(
      env, reactor_step_result_track_kind(result_of(result), static_cast<uint32_t>(track)));
}

extern "C" JNIEXPORT jdouble JNICALL STEP_RESULTS(nativeTrackRate)(JNIEnv*, jobject, jlong result,
                                                                   jint track) {
  return reactor_step_result_track_rate(result_of(result), static_cast<uint32_t>(track));
}

extern "C" JNIEXPORT jint JNICALL STEP_RESULTS(nativeFrameCount)(JNIEnv*, jobject, jlong result,
                                                                 jint track) {
  return static_cast<jint>(
      reactor_step_result_frame_count(result_of(result), static_cast<uint32_t>(track)));
}

extern "C" JNIEXPORT jstring JNICALL STEP_RESULTS(nativeFrameEncoding)(JNIEnv* env, jobject,
                                                                       jlong result, jint track,
                                                                       jint frame) {
  return to_jstring(env, reactor_step_result_frame_encoding(result_of(result),
                                                            static_cast<uint32_t>(track),
                                                            static_cast<uint32_t>(frame)));
}

extern "C" JNIEXPORT jstring JNICALL STEP_RESULTS(nativeFrameDtype)(JNIEnv* env, jobject,
                                                                    jlong result, jint track,
                                                                    jint frame) {
  return to_jstring(env, reactor_step_result_frame_dtype(result_of(result),
                                                         static_cast<uint32_t>(track),
                                                         static_cast<uint32_t>(frame)));
}

/// The shape as an int[]. Null when out of range; a dimension past Int.MAX_VALUE is refused.
extern "C" JNIEXPORT jintArray JNICALL STEP_RESULTS(nativeFrameShape)(JNIEnv* env, jobject,
                                                                      jlong result, jint track,
                                                                      jint frame) {
  uint32_t ndim = 0;
  const uint32_t* shape = reactor_step_result_frame_shape(
      result_of(result), static_cast<uint32_t>(track), static_cast<uint32_t>(frame), &ndim);
  if (shape == nullptr) return nullptr;
  jintArray array = env->NewIntArray(static_cast<jsize>(ndim));
  if (array == nullptr) return nullptr;
  for (uint32_t i = 0; i < ndim; ++i) {
    if (shape[i] > static_cast<uint32_t>(INT32_MAX)) {
      env->DeleteLocalRef(array);
      reactor_jni::fail(env, "java/lang/IllegalStateException",
                        "a step frame dimension is larger than a Java int can hold");
      return nullptr;
    }
    const jint dimension = static_cast<jint>(shape[i]);
    env->SetIntArrayRegion(array, static_cast<jsize>(i), 1, &dimension);
  }
  return array;
}

/// The frame's bytes, copied once into a byte[]: the result is freed right after this is read.
extern "C" JNIEXPORT jbyteArray JNICALL STEP_RESULTS(nativeFrameData)(JNIEnv* env, jobject,
                                                                      jlong result, jint track,
                                                                      jint frame) {
  size_t len = 0;
  const uint8_t* data = reactor_step_result_frame_data(
      result_of(result), static_cast<uint32_t>(track), static_cast<uint32_t>(frame), &len);
  if (data == nullptr) return nullptr;
  return to_jbytes(env, data, len);
}

/// The sender's tag, or null when it attached none. An attached but empty tag is an empty array.
extern "C" JNIEXPORT jbyteArray JNICALL STEP_RESULTS(nativeFrameMetadata)(JNIEnv* env, jobject,
                                                                          jlong result, jint track,
                                                                          jint frame) {
  size_t len = 0;
  const uint8_t* data = reactor_step_result_frame_metadata(
      result_of(result), static_cast<uint32_t>(track), static_cast<uint32_t>(frame), &len);
  if (data == nullptr) return nullptr;
  return to_jbytes(env, data, len);
}

/// The capture time, or Long.MIN_VALUE when the frame carries none: a jlong cannot be null.
extern "C" JNIEXPORT jlong JNICALL STEP_RESULTS(nativeFrameCaptureTimeUs)(JNIEnv*, jobject,
                                                                          jlong result, jint track,
                                                                          jint frame) {
  int64_t capture_time_us = 0;
  if (reactor_step_result_frame_capture_time_us(result_of(result), static_cast<uint32_t>(track),
                                                static_cast<uint32_t>(frame),
                                                &capture_time_us) != 1) {
    return LLONG_MIN;
  }
  return static_cast<jlong>(capture_time_us);
}
