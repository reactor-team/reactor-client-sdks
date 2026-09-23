// A fake libreactor_ffi, for the host-side sanitizer harness.
//
// The real library cannot be made to do the things this boundary has to survive: reactor_destroy
// returning -1 is "a callback is still running and could not be waited for", and there is no way
// to ask a healthy library for it. Nor can a real library be made to fire a control event at a
// chosen moment on a thread of our choosing. Both are exactly where the bridge leaks references
// or writes through freed memory, so both need a library that lies on request.
//
// The strings here are deliberately heap-allocated with strdup so that the ownership rules are
// enforced by the allocator: forgetting reactor_free_string leaks, and calling it on the static
// one is a free() of something that was never malloc'd. Under AddressSanitizer both are errors
// rather than opinions.

#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <string>
#include <thread>

// The real header, so the callback struct's layout and every signature come from the one place
// that defines them. An earlier draft of this file re-declared ReactorCallbacks by hand; that is
// a second copy of the ABI in the repository whose only symptom, when it drifts, is `userdata`
// arriving at the wrong offset and a callback dereferencing whatever was next to it.
#include "reactor_ffi.h"

/// The header leaves this opaque; the fake gives it a body so there is something to point at.
struct ReactorHandle {
    int marker;
};

extern "C" {

static ReactorCallbacks g_callbacks;
static int g_destroy_result = 0;
static ReactorHandle g_handle = {0xBEEF};

uint32_t reactor_abi_version(void) { return REACTOR_ABI_VERSION; }

ReactorHandle* reactor_create_with_adm(const char*, const char*, const char*, int,
                                       const ReactorCallbacks* callbacks, int adm_mode,
                                       const char*, const char*) {
    // The binding must always ask for synthetic. A test that let this through would be the one
    // place a live microphone could reach the wire unnoticed.
    if (adm_mode != 0) return nullptr;
    if (callbacks != nullptr) g_callbacks = *callbacks;
    return &g_handle;
}

int reactor_destroy(ReactorHandle*) { return g_destroy_result; }

/// Static storage, as the real reactor_status is. Passing this to reactor_free_string is a free()
/// of a pointer that was never allocated, which ASan reports rather than tolerating.
const char* reactor_status(ReactorHandle*) { return "disconnected"; }

char* reactor_session_id(ReactorHandle*) { return nullptr; }

char* reactor_tracks(ReactorHandle*) { return strdup("[{\"name\":\"main_video\"}]"); }

char* reactor_paused_tracks(ReactorHandle*) { return strdup("[]"); }

void reactor_free_string(char* s) { free(s); }

// ── Async operations ─────────────────────────────────────────────────────────
//
// The completion is stored rather than called, so the harness decides when it fires and on which
// thread. That is the whole point: a real library completes when it completes, and the races this
// boundary has to survive — a completion arriving after the caller was cancelled, or after the
// client was closed — are not reproducible by waiting.

static reactor_completion_fn g_completion = nullptr;
static void* g_completion_userdata = nullptr;

static void remember(reactor_completion_fn completion, void* userdata) {
    g_completion = completion;
    g_completion_userdata = userdata;
}

void reactor_connect(ReactorHandle*, const char*, const uint32_t*,
                     reactor_completion_fn completion, void* userdata) {
    remember(completion, userdata);
}

void reactor_disconnect(ReactorHandle*, reactor_completion_fn completion, void* userdata) {
    remember(completion, userdata);
}

void reactor_reconnect(ReactorHandle*, reactor_completion_fn completion, void* userdata) {
    remember(completion, userdata);
}

void reactor_publish_track(ReactorHandle*, const char*, reactor_completion_fn completion,
                           void* userdata) {
    remember(completion, userdata);
}

static std::string g_last_args;
static std::string g_last_uploads;

/// Records what the binding actually put on the wire, so the harness can check that nullable
/// arguments arrive as NULL rather than as the string "null" or "".
void reactor_send_command(ReactorHandle*, const char* name, const char* args_json,
                          const char* uploads_json, reactor_completion_fn completion,
                          void* userdata) {
    (void)name;
    g_last_args = args_json == nullptr ? "<null>" : args_json;
    g_last_uploads = uploads_json == nullptr ? "<null>" : uploads_json;
    remember(completion, userdata);
}

void reactor_request_schema(ReactorHandle*, reactor_completion_fn completion, void* userdata) {
    remember(completion, userdata);
}

void reactor_get_stats(ReactorHandle*, reactor_completion_fn completion, void* userdata) {
    remember(completion, userdata);
}

void reactor_pause_track(ReactorHandle*, const char*, reactor_completion_fn completion,
                         void* userdata) {
    remember(completion, userdata);
}

void reactor_resume_track(ReactorHandle*, const char*, reactor_completion_fn completion,
                          void* userdata) {
    remember(completion, userdata);
}

static int g_unpublish_fails = 0;

/// The fourth owned-string case: heap on failure, NULL on success, and the caller frees it.
/// Returning a real strdup is what lets the sanitizer see a leak or a double free.
char* reactor_unpublish_track(ReactorHandle*, const char*) {
    if (!g_unpublish_fails) return nullptr;
    return strdup("{\"code\":\"CONFLICT\",\"message\":\"still sending\"}");
}

// ── Media, counted rather than sent ──────────────────────────────────────────

static uint32_t g_pushed_video = 0;
static uint32_t g_pushed_audio = 0;
static uint8_t g_last_pixel = 0;

void reactor_push_video_frame(ReactorHandle*, const char*, const uint8_t* data, uint32_t width,
                              uint32_t height) {
    g_pushed_video++;
    // Read the far end of the buffer. If Kotlin's length check were wrong, or the buffer were
    // not really direct, this is where the sanitizer would say so.
    if (data != nullptr && width > 0 && height > 0) {
        g_last_pixel = data[static_cast<size_t>(width) * height * 4 - 1];
    }
}

void reactor_push_video_frame_with_metadata(ReactorHandle*, const char*, const uint8_t* data,
                                            uint32_t width, uint32_t height, const uint8_t*,
                                            uint32_t) {
    reactor_push_video_frame(nullptr, nullptr, data, width, height);
}

void reactor_push_audio_frame(ReactorHandle*, const char*, const int16_t* data,
                              uint32_t samples_per_channel, uint32_t, uint32_t num_channels) {
    g_pushed_audio++;
    if (data != nullptr && samples_per_channel > 0 && num_channels > 0) {
        g_last_pixel = static_cast<uint8_t>(
            data[static_cast<size_t>(samples_per_channel) * num_channels - 1] & 0xFF);
    }
}

// ── Controls, for the harness only ───────────────────────────────────────────
// Named fake_* rather than reactor_*: a reactor_* symbol here would be a new name in the ABI as
// far as check-abi-parity.py is concerned.

void fake_set_destroy_result(int result) { g_destroy_result = result; }

void fake_set_unpublish_fails(int fails) { g_unpublish_fails = fails; }

uint32_t fake_pushed_video_frames(void) { return g_pushed_video; }

const char* fake_last_command_args(void) { return g_last_args.c_str(); }

const char* fake_last_command_uploads(void) { return g_last_uploads.c_str(); }

/// Fire a video frame from a thread the JVM has never seen, with a tag attached.
///
/// The pixel buffer is a real allocation the "FFI" owns and frees on return, so a binding that
/// kept the buffer instead of copying reads freed memory — which is what the sanitizer is here to
/// notice.
void fake_fire_video_frame_on_foreign_thread(const char* track, uint32_t width, uint32_t height,
                                             uint64_t frame_id) {
    std::string name(track == nullptr ? "" : track);
    std::thread([name, width, height, frame_id]() {
        if (g_callbacks.on_frame == nullptr) return;
        const size_t bytes = static_cast<size_t>(width) * height * 4;
        auto* pixels = static_cast<uint8_t*>(malloc(bytes));
        memset(pixels, 0x7F, bytes);
        const uint8_t tag[3] = {1, 2, 3};
        g_callbacks.on_frame(name.c_str(), pixels, width, height, frame_id,
                             /*timestamp_us=*/123456, tag, sizeof(tag), g_callbacks.userdata);
        free(pixels);
    }).join();
}

/// Fire the completion the last async call registered, from a thread the JVM has never seen.
void fake_complete_last_on_foreign_thread(int ok, const char* result_json, const char* error_json) {
    reactor_completion_fn completion = g_completion;
    void* userdata = g_completion_userdata;
    if (completion == nullptr) return;
    const bool has_result = result_json != nullptr;
    const bool has_error = error_json != nullptr;
    std::string result(has_result ? result_json : "");
    std::string error(has_error ? error_json : "");
    std::thread([completion, userdata, ok, result, error, has_result, has_error]() {
        completion(ok, has_result ? result.c_str() : nullptr,
                   has_error ? error.c_str() : nullptr, userdata);
    }).join();
    g_completion = nullptr;
    g_completion_userdata = nullptr;
}

/// Fire a status event from a thread the "FFI" owns — one the JVM has never seen, which is the
/// only way to exercise ScopedEnv's attach and detach.
void fake_fire_status_on_foreign_thread(const char* status) {
    std::string copy(status == nullptr ? "" : status);
    std::thread([copy]() {
        if (g_callbacks.on_status != nullptr) g_callbacks.on_status(copy.c_str(), g_callbacks.userdata);
    }).join();
}

void fake_fire_session_id_on_foreign_thread(const char* session_id) {
    const bool null_session = session_id == nullptr;
    std::string copy(null_session ? "" : session_id);
    std::thread([copy, null_session]() {
        if (g_callbacks.on_session_id != nullptr) {
            g_callbacks.on_session_id(null_session ? nullptr : copy.c_str(), g_callbacks.userdata);
        }
    }).join();
}

}  // extern "C"
