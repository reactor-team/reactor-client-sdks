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
