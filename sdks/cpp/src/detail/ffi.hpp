// The boundary: every call into libreactor_ffi goes through here.
//
// Two things this file buys.
//
// **The canonical header, never a copy.** The SDK includes
// `crates/reactor-ffi/include/reactor_ffi.h` and derives every signature from it
// with `decltype`, so the linker is the parity check for names and the compiler
// is the parity check for types. The Python binding hand-wrote its declarations
// and became a third place the ABI could drift; there is no equivalent here to
// drift.
//
// **A table, so tests can lie to it.** One indirect call per operation, and in
// exchange a unit test can hand the SDK a fake library — which is the only way
// to cover teardown and the refuse-do-not-fail-quietly table without a live
// session, a network and a GPU.
#pragma once

#include <reactor_ffi.h>

#include <atomic>

namespace reactor::detail {

// Every symbol the SDK is allowed to call, as one list.
//
// `reactor_create` is deliberately absent. It takes its audio device mode from
// an environment variable, and a library whose audience is scripts and servers
// must never let an env var put a live microphone on the wire because a model
// happened to declare a sendonly audio track. The SDK uses
// `reactor_create_with_adm` with mode 0 (synthetic) and cannot do otherwise,
// because the other function is not reachable from here.
// `scripts/check-abi-parity.py` enforces that.
//
// The list is expanded twice — once into the struct's members, once into the
// table that points them at the real library — because a positional aggregate
// initialiser would compile just as happily with `publish_track` and
// `pause_track` swapped. They have identical signatures, and the result would be
// a binding that pauses a track when asked to publish it.
#define REACTOR_FFI_EACH(X)                                                         \
  /* Version, lifecycle */                                                          \
  X(abi_version, reactor_abi_version)                                               \
  X(create_with_adm, reactor_create_with_adm)                                       \
  X(destroy, reactor_destroy)                                                       \
  /* Session */                                                                     \
  X(connect, reactor_connect)                                                       \
  X(disconnect, reactor_disconnect)                                                 \
  X(reconnect, reactor_reconnect)                                                   \
  /* Tracks */                                                                      \
  X(publish_track, reactor_publish_track)                                           \
  X(unpublish_track, reactor_unpublish_track)                                       \
  X(pause_track, reactor_pause_track)                                               \
  X(resume_track, reactor_resume_track)                                             \
  X(set_bitrate, reactor_set_bitrate)                                               \
  X(set_track_bitrate, reactor_set_track_bitrate)                                   \
  /* Commands, messages, uploads */                                                 \
  X(send_command, reactor_send_command)                                             \
  X(request_schema, reactor_request_schema)                                         \
  X(upload_file, reactor_upload_file)                                               \
  X(upload_bytes, reactor_upload_bytes)                                             \
  /* Recording */                                                                   \
  X(request_clip, reactor_request_clip)                                             \
  X(request_recording, reactor_request_recording)                                   \
  X(download_clip, reactor_download_clip)                                           \
  /* Auth */                                                                        \
  X(fetch_jwt, reactor_fetch_jwt)                                                   \
  /* Synchronous reads. Note which of these return heap strings: see strings.hpp */ \
  X(status, reactor_status)                                                         \
  X(session_id, reactor_session_id)                                                 \
  X(tracks, reactor_tracks)                                                         \
  X(paused_tracks, reactor_paused_tracks)                                           \
  X(free_string, reactor_free_string)                                               \
  /* Media */                                                                       \
  X(time_micros, reactor_time_micros)                                               \
  X(push_video_frame, reactor_push_video_frame)                                     \
  X(push_video_frame_with_metadata, reactor_push_video_frame_with_metadata)         \
  X(push_video_frame_with_metadata_at, reactor_push_video_frame_with_metadata_at)   \
  X(push_audio_frame, reactor_push_audio_frame)

/// The functions the SDK calls, as pointers.
///
/// Types come from the header via `decltype`, so a signature that changes in
/// `reactor-ffi` breaks this build rather than the stack at run time.
struct Ffi {
// `name` cannot be parenthesised: it is a member being declared, not an
// expression. `symbol` comes from the list above and is always a bare identifier.
// NOLINTNEXTLINE(bugprone-macro-parentheses)
#define REACTOR_FFI_MEMBER(name, symbol) decltype(&(symbol)) name = nullptr;
  REACTOR_FFI_EACH(REACTOR_FFI_MEMBER)
#undef REACTOR_FFI_MEMBER
};

/// The table pointing at the real library, checked once against its ABI version.
const Ffi& ffi();

/// Throw unless `table`'s library speaks the ABI this binding was compiled
/// against.
///
/// The check that has no substitute. `check-abi-parity.py` compares the ABI's
/// copies by function *name*, so a function that gained a parameter still links
/// and then corrupts the stack at the call — a hang, or an operation silently
/// doing nothing, never a version error. Twice now the library on disk was
/// simply older than the crates.
void require_supported_abi(const Ffi& table);

/// Point `ffi()` at a different table for the duration of a scope.
///
/// For tests, and the reason the table exists at all. Restores the previous one
/// on destruction, including when a test throws part-way through.
class FfiOverride {
 public:
  explicit FfiOverride(const Ffi* table) noexcept;
  ~FfiOverride();

  FfiOverride(const FfiOverride&) = delete;
  FfiOverride& operator=(const FfiOverride&) = delete;
  FfiOverride(FfiOverride&&) = delete;
  FfiOverride& operator=(FfiOverride&&) = delete;

 private:
  const Ffi* previous_;
};

}  // namespace reactor::detail
