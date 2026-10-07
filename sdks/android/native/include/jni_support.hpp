// The primitives the whole bridge is built out of: thread attachment, string ownership, and the
// global reference that outlives teardown.
//
// Everything here exists because of a specific way the boundary kills a process rather than
// failing a call. The comments say which.

#pragma once

#include <jni.h>

#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

// Not wrapped in an `extern "C"` block: the header carries its own `#ifdef __cplusplus` guard,
// and wrapping it again declares the same functions a second time — which is exactly what
// scripts/check-abi-parity.py refuses, because a redeclaration is a signature nobody checked.
#include "reactor_ffi.h"

namespace reactor_jni {

/// The process JavaVM, captured in JNI_OnLoad.
///
/// Callbacks arrive on threads the FFI owns, which have no JNIEnv of their own. A JNIEnv is
/// per-thread and must never be cached across threads; the JavaVM is per-process and is the only
/// thing that can hand out an env on a thread we did not create.
extern JavaVM* g_vm;

/// Throw a Java exception. The caller must return immediately afterwards without making further
/// JNI calls: a JNI throw sets a pending exception rather than unwinding, and calling on with one
/// pending is undefined.
inline void fail(JNIEnv* env, const char* class_name, const char* message) {
  jclass clazz = env->FindClass(class_name);
  if (clazz != nullptr) {
    env->ThrowNew(clazz, message);
    env->DeleteLocalRef(clazz);
  }
}

/// A JNIEnv for the current thread, attaching it if the FFI owns it.
///
/// Detaches on destruction **only when this scope did the attaching**. Detaching a thread the JVM
/// owns — the one that called in from Kotlin — tears down state that thread still needs; the JVM
/// does not survive it.
class ScopedEnv {
 public:
  ScopedEnv() {
    if (g_vm == nullptr) return;
    const jint status = g_vm->GetEnv(reinterpret_cast<void**>(&env_), JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
      // Named, so a native thread shows up as something recognisable in a thread dump rather
      // than as an anonymous "Thread-12" in the middle of a WebRTC stack.
      JavaVMAttachArgs args = {JNI_VERSION_1_6, const_cast<char*>("reactor-ffi"), nullptr};
      // The two jni.h in play genuinely disagree about this parameter: the NDK declares
      // AttachCurrentThread as taking JNIEnv**, the desktop JDK as taking void**. No single cast
      // satisfies both — a reinterpret_cast to void** compiles on the host and fails on Android,
      // and the reverse fails on the host. The bridge builds against the NDK's header on a
      // device and the JDK's in the sanitizer harness, so the difference is stated rather than
      // papered over. A host-only build would never have shown it, which is exactly what
      // happened: this file compiled for Android nowhere in CI until the AAR build below.
#ifdef __ANDROID__
      const jint attached = g_vm->AttachCurrentThread(&env_, &args);
#else
      const jint attached = g_vm->AttachCurrentThread(reinterpret_cast<void**>(&env_), &args);
#endif
      if (attached == JNI_OK) {
        attached_ = true;
      } else {
        env_ = nullptr;
      }
    } else if (status != JNI_OK) {
      env_ = nullptr;
    }
  }

  ~ScopedEnv() {
    if (attached_ && g_vm != nullptr) g_vm->DetachCurrentThread();
  }

  ScopedEnv(const ScopedEnv&) = delete;
  ScopedEnv& operator=(const ScopedEnv&) = delete;

  JNIEnv* get() const { return env_; }
  explicit operator bool() const { return env_ != nullptr; }

 private:
  JNIEnv* env_ = nullptr;
  bool attached_ = false;
};

/// A string the FFI handed us ownership of, freed exactly once.
///
/// The header states per function which of the three kinds a string is, and all three are easy to
/// get wrong in different directions: freeing the static one (`reactor_status`) corrupts the
/// heap, freeing a borrowed one is a double free, and not freeing an owned one leaks on every
/// property read. This type is only ever wrapped around the owned kind.
class OwnedString {
 public:
  explicit OwnedString(char* raw) : raw_(raw) {}
  ~OwnedString() {
    if (raw_ != nullptr) reactor_free_string(raw_);
  }

  OwnedString(const OwnedString&) = delete;
  OwnedString& operator=(const OwnedString&) = delete;

  const char* get() const { return raw_; }
  explicit operator bool() const { return raw_ != nullptr; }

 private:
  char* raw_;
};

/// JNI's string encoding is **not** the one on the other side of this boundary, and the
/// difference is silent.
///
/// `GetStringUTFChars` and `NewStringUTF` speak *modified* UTF-8: a JVM encoding in which a
/// character outside the Basic Multilingual Plane is emitted as its two UTF-16 surrogate halves,
/// each encoded separately as three bytes. Standard UTF-8 — which is what Rust's `str` is, and
/// what every `CStr::from_ptr(...).to_string_lossy()` on the other side decodes — encodes that
/// same character as one four-byte sequence, and treats an encoded surrogate as invalid.
///
/// So the naive pair round-trips everything a test is likely to contain and corrupts emoji,
/// historic scripts, and the CJK extension blocks: a prompt or a filename carrying one arrives at
/// the model with replacement characters in it, with nothing failing anywhere. These two
/// functions convert explicitly through UTF-16, which is the encoding both sides agree on.
///
/// Unpaired surrogates and malformed sequences become U+FFFD rather than propagating: they have
/// no standard-UTF-8 encoding at all, and a Java `String` is free to contain one.
inline std::string utf16_to_utf8(const jchar* chars, jsize length) {
  std::string out;
  out.reserve(static_cast<size_t>(length) + static_cast<size_t>(length) / 2 + 4);
  for (jsize i = 0; i < length; ++i) {
    uint32_t code = chars[i];
    if (code >= 0xD800 && code <= 0xDBFF && i + 1 < length) {
      const uint32_t low = chars[i + 1];
      if (low >= 0xDC00 && low <= 0xDFFF) {
        code = 0x10000 + ((code - 0xD800) << 10) + (low - 0xDC00);
        ++i;
      }
    }
    if (code >= 0xD800 && code <= 0xDFFF) code = 0xFFFD;  // unpaired half

    if (code < 0x80) {
      out.push_back(static_cast<char>(code));
    } else if (code < 0x800) {
      out.push_back(static_cast<char>(0xC0 | (code >> 6)));
      out.push_back(static_cast<char>(0x80 | (code & 0x3F)));
    } else if (code < 0x10000) {
      out.push_back(static_cast<char>(0xE0 | (code >> 12)));
      out.push_back(static_cast<char>(0x80 | ((code >> 6) & 0x3F)));
      out.push_back(static_cast<char>(0x80 | (code & 0x3F)));
    } else {
      out.push_back(static_cast<char>(0xF0 | (code >> 18)));
      out.push_back(static_cast<char>(0x80 | ((code >> 12) & 0x3F)));
      out.push_back(static_cast<char>(0x80 | ((code >> 6) & 0x3F)));
      out.push_back(static_cast<char>(0x80 | (code & 0x3F)));
    }
  }
  return out;
}

/// The reverse: standard UTF-8 to UTF-16, for `NewString` rather than `NewStringUTF`.
inline std::vector<jchar> utf8_to_utf16(const char* bytes) {
  std::vector<jchar> out;
  const auto* cursor = reinterpret_cast<const unsigned char*>(bytes);
  while (*cursor != 0) {
    const unsigned char lead = *cursor++;
    uint32_t code;
    int continuations;
    if (lead < 0x80) {
      code = lead;
      continuations = 0;
    } else if ((lead & 0xE0) == 0xC0) {
      code = lead & 0x1Fu;
      continuations = 1;
    } else if ((lead & 0xF0) == 0xE0) {
      code = lead & 0x0Fu;
      continuations = 2;
    } else if ((lead & 0xF8) == 0xF0) {
      code = lead & 0x07u;
      continuations = 3;
    } else {
      code = 0xFFFD;  // a stray continuation byte, or 0xF8..0xFF
      continuations = 0;
    }
    for (int i = 0; i < continuations; ++i) {
      const unsigned char next = *cursor;
      if ((next & 0xC0) != 0x80) {
        // Truncated. Stop here rather than consuming a byte that starts the next character.
        code = 0xFFFD;
        break;
      }
      code = (code << 6) | (next & 0x3Fu);
      ++cursor;
    }
    if (code > 0x10FFFF || (code >= 0xD800 && code <= 0xDFFF)) code = 0xFFFD;

    if (code < 0x10000) {
      out.push_back(static_cast<jchar>(code));
    } else {
      code -= 0x10000;
      out.push_back(static_cast<jchar>(0xD800 + (code >> 10)));
      out.push_back(static_cast<jchar>(0xDC00 + (code & 0x3FF)));
    }
  }
  return out;
}

/// A Java string from a **standard** UTF-8 C string, or null for nullptr.
///
/// Null is meaningful in this ABI rather than an error: `reactor_session_id` returns it when
/// there is no session, and `on_session_id` receives it when the session is cleared. Mapping it
/// to "" would lose that.
///
/// `NewString` rather than `NewStringUTF`, because what arrives here is standard UTF-8 and
/// `NewStringUTF` reads modified UTF-8 — see [utf16_to_utf8] above.
inline jstring to_jstring(JNIEnv* env, const char* value) {
  if (value == nullptr) return nullptr;
  const std::vector<jchar> utf16 = utf8_to_utf16(value);
  return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

/// A standard-UTF-8 copy of a JNI string, valid for the scope.
///
/// Copied rather than borrowed through `GetStringUTFChars`, which would hand back modified UTF-8
/// — see [utf16_to_utf8].
class JavaString {
 public:
  JavaString(JNIEnv* env, jstring value) {
    if (value == nullptr) return;
    const jsize length = env->GetStringLength(value);
    std::vector<jchar> utf16(static_cast<size_t>(length));
    if (length > 0) env->GetStringRegion(value, 0, length, utf16.data());
    chars_ = utf16_to_utf8(utf16.data(), length);
    present_ = true;
  }

  JavaString(const JavaString&) = delete;
  JavaString& operator=(const JavaString&) = delete;

  /// nullptr for a null jstring — the ABI's nullable arguments take it directly.
  const char* get() const { return present_ ? chars_.c_str() : nullptr; }

 private:
  std::string chars_;
  bool present_ = false;
};

}  // namespace reactor_jni
