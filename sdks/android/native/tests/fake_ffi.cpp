// A fake libreactor_ffi, for the host-side sanitizer harness.
//
// The real library cannot be made to do the things this boundary has to survive: reactor_destroy
// returning -1 is "a callback is still running and could not be waited for", and there is no way
// to ask a healthy library for it. Nor can a real library be made to fire a control event at a
// chosen moment on a thread of our choosing. Both are exactly where the bridge leaks references
// or writes through freed memory, so both need a library that lies on request.
//
// The strings here are deliberately heap-allocated with strdup so that the ownership rules are
// enforced by the allocator: calling reactor_free_string on the static one is a free() of
// something that was never malloc'd, which AddressSanitizer reports. Forgetting to free an owned
// one is only a leak, and the harness runs with leak detection off — the JVM it hosts leaks by
// design — so the owned strings are also counted, and the harness asserts the count returns to
// zero rather than trusting the sanitizer to notice.

#include <atomic>
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

/// One step's output: a single video track with one 2x2 RGB frame, tagged and timed. Allocated
/// with new so that freeing it twice, or reading it after the free, is an ASan report.
struct ReactorStepResult {
    uint32_t shape[3] = {2, 2, 3};
    uint8_t data[12] = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
    uint8_t tag[3] = {9, 8, 7};
};

extern "C" {

static ReactorCallbacks g_callbacks;
static int g_destroy_result = 0;
static int g_last_auto_resume = -1;
static ReactorHandle g_handle = {0xBEEF};
static std::atomic<long> g_owned_strings{0};

static char* owned(const char* text) {
    g_owned_strings.fetch_add(1);
    return strdup(text);
}

uint32_t reactor_abi_version(void) { return REACTOR_ABI_VERSION; }

ReactorHandle* reactor_create_with_adm(const char*, const char*, const char*, int,
                                       int auto_resume_tracks, const ReactorCallbacks* callbacks,
                                       int adm_mode, const char*, const char*) {
    g_last_auto_resume = auto_resume_tracks;
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

char* reactor_tracks(ReactorHandle*) { return owned("[{\"name\":\"main_video\"}]"); }

char* reactor_paused_tracks(ReactorHandle*) { return owned("[]"); }

void reactor_free_string(char* s) {
    if (s != nullptr) g_owned_strings.fetch_sub(1);
    free(s);
}

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

/// The delivery-stage marker. Stateless in the real library too; nothing here reads it.
void reactor_frame_delivered(void) {}

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

static size_t g_uploaded_bytes = 0;
static std::string g_last_upload_name;
static std::string g_last_upload_mime;

void reactor_upload_file(ReactorHandle*, const char*, reactor_completion_fn completion,
                         void* userdata) {
    remember(completion, userdata);
}

void reactor_request_clip(ReactorHandle*, double, reactor_completion_fn completion,
                          void* userdata) {
    remember(completion, userdata);
}

void reactor_request_recording(ReactorHandle*, reactor_completion_fn completion, void* userdata) {
    remember(completion, userdata);
}

static reactor_progress_fn g_progress = nullptr;
static void* g_progress_userdata = nullptr;
static double g_last_ready_timeout = 0.0;

/// A download keeps the callbacks it was given and does **not** treat the handle as its owner —
/// the header says a download outlives the handle it was given one of. The harness fires its
/// progress and completion *after* destroying the client, which is where a binding holding a
/// pointer rather than a ticket reads freed memory.
void reactor_download_clip(ReactorHandle*, const char*, const char*, const char*, double,
                           double ready_timeout_seconds, int, reactor_progress_fn progress,
                           reactor_completion_fn completion, void* userdata) {
    g_last_ready_timeout = ready_timeout_seconds;
    g_progress = progress;
    g_progress_userdata = userdata;
    remember(completion, userdata);
}

static std::string g_last_fetch_options;
static int g_last_fetch_local = -1;

/// The key exchange takes no handle, so there is no handle-related rule to check — what matters
/// is the nullable argument and the flag. options_json is recorded the way send_command records
/// its nullables, so the harness can tell NULL apart from the string "null" or "". api_url and
/// api_key are *not* dereferenced: the header promises a null there completes with an error, and
/// the fake must not be stricter by crashing first than the ABI is.
void reactor_fetch_jwt(const char*, const char*, const char* options_json, int local,
                       reactor_completion_fn completion, void* userdata) {
    g_last_fetch_options = options_json == nullptr ? "<null>" : options_json;
    g_last_fetch_local = local;
    remember(completion, userdata);
}

/// Reads the whole borrowed buffer, so a binding that handed over a wrong length — or a
/// non-direct buffer whose address is not the bytes — is a read past the end under the sanitizer
/// rather than a truncated upload nobody notices.
// name and mime_type are dereferenced here on purpose, exactly as reactor_upload_bytes does with
// CStr::from_ptr. The fake used to ignore both, which is why a binding that passed a null MIME
// type — the public API's own default — survived every ASan run and would have dereferenced null
// on a real device. A fake that is laxer than the ABI it stands in for cannot catch the one class
// of bug this harness exists for.
void reactor_upload_bytes(ReactorHandle*, const uint8_t* data, size_t len, const char* name,
                          const char* mime_type, reactor_completion_fn completion,
                          void* userdata) {
    g_last_upload_name = name;       // std::string from const char*: a null here is the crash.
    g_last_upload_mime = mime_type;  // Same.
    g_uploaded_bytes = 0;
    for (size_t i = 0; i < len; ++i) g_uploaded_bytes += data[i];
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

int fake_last_auto_resume(void) { return g_last_auto_resume; }
void fake_set_unpublish_fails(int fails) { g_unpublish_fails = fails; }

uint32_t fake_pushed_video_frames(void) { return g_pushed_video; }

size_t fake_uploaded_checksum(void) { return g_uploaded_bytes; }

const char* fake_last_upload_mime(void) { return g_last_upload_mime.c_str(); }
double fake_last_ready_timeout(void) { return g_last_ready_timeout; }

const char* fake_last_fetch_options(void) { return g_last_fetch_options.c_str(); }

int fake_last_fetch_local(void) { return g_last_fetch_local; }

/// Fire the download's progress callback from a thread the JVM has never seen.
void fake_fire_download_progress(uint32_t done, uint32_t total) {
    reactor_progress_fn progress = g_progress;
    void* userdata = g_progress_userdata;
    if (progress == nullptr) return;
    std::thread([progress, userdata, done, total]() { progress(done, total, userdata); }).join();
}

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

/// Owned strings handed out and not yet returned through reactor_free_string.
long fake_owned_strings_outstanding(void) { return g_owned_strings.load(); }

// ── Stepped generation ───────────────────────────────────────────────────────

static std::string g_last_create_options;
static std::atomic<long> g_step_results{0};
static std::atomic<int> g_clear_step_inputs{0};
static reactor_step_completion_fn g_step_completion = nullptr;
static void* g_step_userdata = nullptr;
static std::string g_last_step_state;
static std::string g_last_step_commands;

/// Refuses options that do not pin the synthetic ADM, as reactor_create_with_adm refuses any mode
/// but 0 — through error_out, which is the path the bridge has to free.
ReactorHandle* reactor_create_with_options(const char*, const char*, const char*,
                                           const ReactorCallbacks* callbacks,
                                           const char* options_json, char** error_out) {
    g_last_create_options = options_json == nullptr ? "<null>" : options_json;
    if (options_json == nullptr || strstr(options_json, "\"adm\":\"synthetic\"") == nullptr) {
        if (error_out != nullptr) {
            *error_out = owned("{\"code\":\"INVALID_STATE\",\"message\":\"adm must be synthetic\"}");
        }
        return nullptr;
    }
    if (callbacks != nullptr) g_callbacks = *callbacks;
    return &g_handle;
}

void reactor_step(ReactorHandle*, const char* state_json, const char* commands_json,
                  reactor_step_completion_fn completion, void* userdata) {
    g_last_step_state = state_json == nullptr ? "<null>" : state_json;
    g_last_step_commands = commands_json == nullptr ? "<null>" : commands_json;
    g_step_completion = completion;
    g_step_userdata = userdata;
}

void reactor_clear_step_inputs(ReactorHandle*) { g_clear_step_inputs.fetch_add(1); }

uint64_t reactor_step_result_step(const ReactorStepResult* r) { return r == nullptr ? 0 : 7; }

double reactor_step_result_elapsed(const ReactorStepResult* r) { return r == nullptr ? 0.0 : 0.25; }

const char* reactor_step_result_error(const ReactorStepResult*) { return nullptr; }

uint32_t reactor_step_result_file_count(const ReactorStepResult*) { return 0; }

const char* reactor_step_result_file_name(const ReactorStepResult*, uint32_t) { return nullptr; }

const char* reactor_step_result_file_content_type(const ReactorStepResult*, uint32_t) {
    return nullptr;
}

uint64_t reactor_step_result_file_size(const ReactorStepResult*, uint32_t) { return 0; }

uint32_t reactor_step_result_track_count(const ReactorStepResult* r) { return r == nullptr ? 0 : 1; }

const char* reactor_step_result_track_name(const ReactorStepResult* r, uint32_t track) {
    return r != nullptr && track == 0 ? "main_video" : nullptr;
}

const char* reactor_step_result_track_kind(const ReactorStepResult* r, uint32_t track) {
    return r != nullptr && track == 0 ? "video" : nullptr;
}

double reactor_step_result_track_rate(const ReactorStepResult* r, uint32_t track) {
    return r != nullptr && track == 0 ? 30.0 : 0.0;
}

uint32_t reactor_step_result_frame_count(const ReactorStepResult* r, uint32_t track) {
    return r != nullptr && track == 0 ? 1 : 0;
}

static bool in_range(const ReactorStepResult* r, uint32_t track, uint32_t frame) {
    return r != nullptr && track == 0 && frame == 0;
}

const char* reactor_step_result_frame_encoding(const ReactorStepResult* r, uint32_t track,
                                               uint32_t frame) {
    return in_range(r, track, frame) ? "raw" : nullptr;
}

const char* reactor_step_result_frame_dtype(const ReactorStepResult* r, uint32_t track,
                                            uint32_t frame) {
    return in_range(r, track, frame) ? "uint8" : nullptr;
}

const uint32_t* reactor_step_result_frame_shape(const ReactorStepResult* r, uint32_t track,
                                                uint32_t frame, uint32_t* ndim) {
    if (!in_range(r, track, frame)) {
        if (ndim != nullptr) *ndim = 0;
        return nullptr;
    }
    if (ndim != nullptr) *ndim = 3;
    return r->shape;
}

const uint8_t* reactor_step_result_frame_data(const ReactorStepResult* r, uint32_t track,
                                              uint32_t frame, size_t* len) {
    if (!in_range(r, track, frame)) {
        if (len != nullptr) *len = 0;
        return nullptr;
    }
    if (len != nullptr) *len = sizeof(r->data);
    return r->data;
}

const uint8_t* reactor_step_result_frame_metadata(const ReactorStepResult* r, uint32_t track,
                                                  uint32_t frame, size_t* len) {
    if (!in_range(r, track, frame)) {
        if (len != nullptr) *len = 0;
        return nullptr;
    }
    if (len != nullptr) *len = sizeof(r->tag);
    return r->tag;
}

int reactor_step_result_frame_capture_time_us(const ReactorStepResult* r, uint32_t track,
                                              uint32_t frame, int64_t* capture_time_us) {
    if (!in_range(r, track, frame)) return 0;
    if (capture_time_us != nullptr) *capture_time_us = 424242;
    return 1;
}

void reactor_step_result_free(ReactorStepResult* r) {
    if (r == nullptr) return;
    g_step_results.fetch_sub(1);
    delete r;
}

/// A result the harness reads directly, to put the accessors under the sanitizer.
ReactorStepResult* fake_new_step_result(void) {
    g_step_results.fetch_add(1);
    return new ReactorStepResult();
}

/// Step results handed out and not yet returned through reactor_step_result_free.
long fake_step_results_outstanding(void) { return g_step_results.load(); }

int fake_clear_step_inputs_calls(void) { return g_clear_step_inputs.load(); }

const char* fake_last_create_options(void) { return g_last_create_options.c_str(); }

const char* fake_last_step_state(void) { return g_last_step_state.c_str(); }

const char* fake_last_step_commands(void) { return g_last_step_commands.c_str(); }

/// Complete the last step from a thread the JVM has never seen: with a fresh result when ok, or
/// with no result and error_json when not.
void fake_complete_last_step_on_foreign_thread(int ok, const char* error_json) {
    reactor_step_completion_fn completion = g_step_completion;
    void* userdata = g_step_userdata;
    if (completion == nullptr) return;
    const bool has_error = error_json != nullptr;
    std::string error(has_error ? error_json : "");
    std::thread([completion, userdata, ok, error, has_error]() {
        ReactorStepResult* result = ok == 1 ? fake_new_step_result() : nullptr;
        completion(ok, result, has_error ? error.c_str() : nullptr, userdata);
    }).join();
    g_step_completion = nullptr;
    g_step_userdata = nullptr;
}

/// Fire a status event from a thread the "FFI" owns — one the JVM has never seen, which is the
/// only way to exercise ScopedEnv's attach and detach.
void fake_fire_status_on_foreign_thread(const char* status) {
    std::string copy(status == nullptr ? "" : status);
    std::thread([copy]() {
        if (g_callbacks.on_status != nullptr) g_callbacks.on_status(copy.c_str(), g_callbacks.userdata);
    }).join();
}

/// Fire a model message (or, with `runtime`, a runtime message) from an FFI-owned thread.
void fake_fire_message_on_foreign_thread(const char* json, bool runtime) {
    std::string copy(json == nullptr ? "" : json);
    std::thread([copy, runtime]() {
        auto callback = runtime ? g_callbacks.on_runtime_message : g_callbacks.on_message;
        if (callback != nullptr) callback(copy.c_str(), g_callbacks.userdata);
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
