/* reactor_ffi.h — C API for the Reactor SDK core
 *
 * Build the shared library:
 *   cargo build -p reactor-ffi --release
 *
 * macOS : target/release/libreactor_ffi.dylib
 * Linux : target/release/libreactor_ffi.so
 * Windows: target/release/reactor_ffi.dll
 *
 * Thread safety
 * ─────────────
 * No callback runs on the caller's thread, and callbacks may run concurrently.
 * Strings and buffers handed to a callback are valid only for its duration —
 * copy anything you need to keep.
 *
 * Callbacks are delivered on threads dedicated to them, so a callback that
 * blocks — taking the CPython GIL, attaching to the JVM — is tolerated and
 * delays only its own stream.  It never stalls WebRTC decoding or the internal
 * tokio runtime.  There are three such threads, because their backpressure
 * differs:
 *
 *   - Control events (on_status, on_error, on_message, on_runtime_message,
 *     on_track, on_capabilities, on_session_id) and the completion callbacks
 *     (reactor_completion_fn, invoked exactly once per call) share one thread,
 *     with an unbounded queue: they are low-rate, and losing one would leave you
 *     with a wrong view of the session.
 *   - on_frame has its own thread and a one-deep queue.  If you are slower than
 *     the incoming frame rate you get the newest frame and the ones in between
 *     are dropped, because a stale frame costs latency without buying anything.
 *   - on_audio has its own thread and a short queue that keeps its backlog and
 *     refuses new arrivals when full, since there the queue is the jitter buffer
 *     and a hole in it is audible.
 *
 * Blocking is therefore safe, but still not free: whatever you block, you drop.
 *
 * The caller is responsible for ensuring that `userdata` pointers are safe
 * to dereference from any thread that may call the callbacks.
 *
 * Teardown
 * ────────
 * reactor_destroy() blocks until every callback in flight has returned, and no
 * callback starts after it.  It is therefore the boundary to release your
 * callback context on — a ctypes trampoline, a cgo.Handle, a JNI GlobalRef —
 * and doing so any earlier is a use-after-free.
 *
 * Check the return value.  0 means quiescence was reached and releasing is safe;
 * -1 means a callback is still running and the pointers must be kept alive
 * instead, which happens if the host is wedged (a callback waiting on a GIL an
 * already-finalising interpreter will never release) or if destroy was called
 * from inside one of the handle's own callbacks.  Tearing down while your
 * runtime is still running keeps you on the 0 path.
 */

#ifndef REACTOR_FFI_H
#define REACTOR_FFI_H

#ifdef __cplusplus
extern "C" {
#endif

#include <stddef.h>
#include <stdint.h>

/* ── Opaque client handle ─────────────────────────────────────────────────── */

typedef struct ReactorHandle ReactorHandle;

/* ── Event callback types ─────────────────────────────────────────────────── */

/* Status string: "disconnected" | "connecting" | "waiting" | "ready" */
typedef void (*reactor_on_status_fn)(const char *status, void *userdata);

/* JSON object: { code, message, recoverable, timestamp_ms, component } */
typedef void (*reactor_on_error_fn)(const char *error_json, void *userdata);

/* JSON object: model application message */
typedef void (*reactor_on_message_fn)(const char *msg_json, void *userdata);

/* JSON object: runtime (platform) message */
typedef void (*reactor_on_runtime_message_fn)(const char *msg_json, void *userdata);

/* Incoming media track.  mid_or_null may be NULL if unresolved. */
typedef void (*reactor_on_track_fn)(const char *name, const char *mid_or_null, void *userdata);

/* JSON object: { protocol_version, tracks:[{name,kind,direction}], commands:[…] } */
typedef void (*reactor_on_capabilities_fn)(const char *caps_json, void *userdata);

/* session_id_or_null is NULL when the session is cleared. */
typedef void (*reactor_on_session_id_fn)(const char *session_id_or_null, void *userdata);

/*
 * Raw video frame in BGRA format (B, G, R, A bytes).  width * height * 4 bytes.
 *
 * track_name names the declared track the frame arrived on — every recvonly video
 * track decodes into this one callback, so it is the only thing that tells them
 * apart.  Empty ("") when the transceiver could not be matched to a declared
 * track; never NULL.
 */
typedef void (*reactor_on_frame_fn)(
    const char *track_name,
    const uint8_t *data, uint32_t width, uint32_t height,
    uint64_t frame_id,       /* 0 when no metadata trailer present */
    uint64_t timestamp_us,   /* wall-clock µs; 0 when no metadata  */
    const uint8_t *user_data, uint32_t user_data_len, /* NULL/0 when no metadata */
    void *userdata
);

/*
 * Audio frame callback: the track the frame arrived on (as reactor_on_frame_fn),
 * interleaved int16 PCM, total sample count, sample rate (Hz), channels.
 */
typedef void (*reactor_on_audio_fn)(const char *track_name, const int16_t *samples, uint32_t num_samples, uint32_t sample_rate, uint32_t channels, void *userdata);

/* ── Callbacks registration struct ───────────────────────────────────────── */

typedef struct ReactorCallbacks {
    reactor_on_status_fn          on_status;           /* nullable */
    reactor_on_error_fn           on_error;            /* nullable */
    reactor_on_message_fn         on_message;          /* nullable */
    reactor_on_runtime_message_fn on_runtime_message;  /* nullable */
    reactor_on_track_fn           on_track;            /* nullable */
    reactor_on_capabilities_fn    on_capabilities;     /* nullable */
    reactor_on_session_id_fn      on_session_id;       /* nullable */
    reactor_on_frame_fn           on_frame;            /* nullable; own thread, newest frame wins if you fall behind */
    reactor_on_audio_fn           on_audio;            /* nullable; own thread, ~10 ms/frame, short queue */
    void                         *userdata;             /* passed through to every callback */
} ReactorCallbacks;

/* ── Async completion callback ────────────────────────────────────────────── */

/*
 * Called exactly once when an async operation completes.
 *   ok         : 1 on success, 0 on error
 *   result_json: JSON result string on success (NULL if the operation is void)
 *   error_msg  : human-readable error string on failure (NULL on success)
 * Both strings are freed after the callback returns; copy them if needed.
 */
typedef void (*reactor_completion_fn)(
    int         ok,
    const char *result_json,
    const char *error_msg,
    void       *userdata
);

/* ── Lifecycle ────────────────────────────────────────────────────────────── */

/*
 * Create a new Reactor client.
 *
 *   api_url    — coordinator base URL, e.g. "https://api.reactor.inc"
 *   model_name — model to connect to
 *   jwt        — JWT token, or NULL for unauthenticated (local dev)
 *   local      — non-zero to enable local-dev mode (skips TLS cert checks, etc.)
 *   callbacks  — event callbacks struct, or NULL (no events)
 *
 * Returns NULL only on allocation failure (extremely unlikely).
 * The returned handle must be destroyed with reactor_destroy().
 */
ReactorHandle *reactor_create(
    const char           *api_url,
    const char           *model_name,
    const char           *jwt,        /* nullable */
    int                   local,
    const ReactorCallbacks *callbacks /* nullable */
);

/*
 * Like reactor_create but selects the audio device module explicitly:
 *   adm_mode 0 = synthetic (no audio hardware: the app pushes PCM with
 *                            reactor_push_audio_frame and receives decoded audio
 *                            through on_audio),
 *            1 = platform  (real mic capture + speaker playout, with AEC/NS/AGC),
 *            other        = the default, which is synthetic.
 *
 * Synthetic is the default deliberately: nothing opens the microphone unless you
 * ask for it with 1.  A model declaring a sendonly audio track is not you asking —
 * under the platform module that alone put live microphone audio on the wire.
 */
ReactorHandle *reactor_create_with_adm(
    const char           *api_url,
    const char           *model_name,
    const char           *jwt,        /* nullable */
    int                   local,
    const ReactorCallbacks *callbacks, /* nullable */
    int                   adm_mode
);

/*
 * Destroy a handle, and with it the right to call back into the host.
 *
 * Returns 0 when no callback is running and none will start.  That is the only
 * answer on which you may release whatever your callback pointers refer to — a
 * ctypes trampoline, a cgo.Handle, a JNI GlobalRef.
 *
 * Returns -1 when a callback is still executing and could not be waited for.
 * The handle is released either way, but the callback pointers must be kept
 * alive; leaking them is correct, freeing them is a use-after-free.  Two ways to
 * get here: the host is wedged (a callback blocked on a lock this library cannot
 * make it give up, such as a GIL an already-finalising interpreter will never
 * release), or this was called from inside one of the handle's own callbacks,
 * which is therefore still on the stack.
 *
 * NULL is accepted and returns 0.
 *
 * Callers that previously treated this as returning void keep working; the extra
 * return value is ignored by the caller's calling convention.
 */
int reactor_destroy(ReactorHandle *handle);

/* ── Async operations ─────────────────────────────────────────────────────── */

/*
 * Connect: create (or adopt) a session and establish the WebRTC transport.
 *   session_id — nullable; pass NULL to create a new session.
 *   completion — called with ok=1 and result_json="{}" on success.
 */
void reactor_connect(
    ReactorHandle       *handle,
    const char          *session_id,  /* nullable */
    reactor_completion_fn completion,
    void                *userdata
);

/* Gracefully disconnect.  Session is NOT terminated (can reconnect). */
void reactor_disconnect(
    ReactorHandle       *handle,
    reactor_completion_fn completion,
    void                *userdata
);

/* Reconnect using the existing session (after a transient failure). */
void reactor_reconnect(
    ReactorHandle       *handle,
    reactor_completion_fn completion,
    void                *userdata
);

/*
 * Publish a named track (activate the send slot; attach media separately).
 * result_json is "{}" on success.
 */
void reactor_publish_track(
    ReactorHandle       *handle,
    const char          *name,
    reactor_completion_fn completion,
    void                *userdata
);

/* Deactivate (pause) a named track transceiver. */
void reactor_pause_track(
    ReactorHandle       *handle,
    const char          *name,
    reactor_completion_fn completion,
    void                *userdata
);

/* Re-activate (resume) a named track transceiver. */
void reactor_resume_track(
    ReactorHandle       *handle,
    const char          *name,
    reactor_completion_fn completion,
    void                *userdata
);

/*
 * Request a clip of `duration_seconds` length.
 * result_json: { playlist_url, session_id, kind, … }
 */
void reactor_request_clip(
    ReactorHandle       *handle,
    double               duration_seconds,
    reactor_completion_fn completion,
    void                *userdata
);

/*
 * Start a full-session recording.
 * result_json: { playlist_url, session_id, kind, … }
 */
void reactor_request_recording(
    ReactorHandle       *handle,
    reactor_completion_fn completion,
    void                *userdata
);

/*
 * Request the model's command schema.
 * result_json: an OpenAPI document.
 */
void reactor_request_schema(
    ReactorHandle       *handle,
    reactor_completion_fn completion,
    void                *userdata
);

/*
 * Send an application-scoped command over the data channel and wait for its
 * correlated reply.
 *   name         — command name
 *   args_json    — JSON object, or NULL (treated as {})
 *   uploads_json — JSON object of
 *     {param_name: {upload_id, name, mime_type, size}}, or NULL (no uploads).
 *     Values come from reactor_upload_file / reactor_upload_bytes.
 * result_json: { type, data }, or absent if the handler acknowledged the
 * command but returned no message.
 */
void reactor_send_command(
    ReactorHandle       *handle,
    const char          *name,
    const char          *args_json,     /* nullable */
    const char          *uploads_json,  /* nullable */
    reactor_completion_fn completion,
    void                *userdata
);

/*
 * Upload a local file.
 * result_json: { upload_id, name, mime_type, size }
 */
void reactor_upload_file(
    ReactorHandle       *handle,
    const char          *path,
    reactor_completion_fn completion,
    void                *userdata
);

/*
 * Upload a file already in memory. Same result shape as reactor_upload_file;
 * use this when the caller already has the bytes rather than a filesystem path.
 *   data — at least `len` readable bytes, borrowed for the call only
 */
void reactor_upload_bytes(
    ReactorHandle       *handle,
    const uint8_t       *data,
    size_t               len,
    const char          *name,
    const char          *mime_type,
    reactor_completion_fn completion,
    void                *userdata
);

/* ── Synchronous operations ───────────────────────────────────────────────── */

/* Release a previously published track (sync). */
int reactor_unpublish_track(
    ReactorHandle *handle,
    const char    *name
);

/*
 * Current status string.  Points to a static string; never NULL.
 * Valid until the next call to any reactor_* function on this handle
 * (or forever — all returned values are actually static literals).
 */
const char *reactor_status(ReactorHandle *handle);

/*
 * Current session ID, heap-allocated.  Caller must free with
 * reactor_free_string().  Returns NULL when no session is active.
 */
char *reactor_session_id(ReactorHandle *handle);

/*
 * The tracks the runtime declared, as a JSON array of
 * [{"name":…,"kind":"video"|"audio","direction":"sendonly"|"recvonly"}] —
 * the same entries on_capabilities carries, readable at any time.
 *
 * "[]" before the session is accepted and after it is torn down, so a caller can
 * tell "no tracks yet" from a name it does not recognise.  Heap-allocated; free
 * with reactor_free_string().  NULL only if handle is NULL.
 */
char *reactor_tracks(ReactorHandle *handle);

/*
 * Names of the currently paused tracks, as a JSON array of strings, sorted.
 * Recvonly tracks resume automatically once connected, so this is empty on a
 * healthy session until the caller pauses something.  Heap-allocated; free with
 * reactor_free_string().  NULL only if handle is NULL.
 */
char *reactor_paused_tracks(ReactorHandle *handle);

/*
 * Free a string returned by reactor_session_id(), reactor_tracks() or
 * reactor_paused_tracks().
 */
void reactor_free_string(char *s);

/*
 * Push a raw video frame into a named sendonly track.
 *   track_name — name of the sendonly video track
 *   data       — BGRA pixels (B, G, R, A bytes), width * height * 4 bytes total
 *   width      — frame width in pixels
 *   height     — frame height in pixels
 * No-op if handle is NULL, track_name is NULL, data is NULL, or the named
 * track has no attached video source.
 */
void reactor_push_video_frame(
    ReactorHandle *handle,
    const char    *track_name,
    const uint8_t *data,
    uint32_t       width,
    uint32_t       height
);

/*
 * Push a BGRA frame tagged with `user_data`, which reaches the far end as that
 * frame's metadata (see reactor_on_frame_fn's user_data parameter).
 *
 * The bytes are sent as-is — JSON, protobuf or anything else is between the
 * caller and the model.  A tag is dropped unless the peer declared that it reads
 * them, so tagging is safe whatever the far end supports.
 *
 * `user_data` may be NULL with `user_data_len` 0, which is identical to
 * reactor_push_video_frame().  Same buffer requirements as that function:
 * `data` must hold width * height * 4 bytes.
 */
void reactor_push_video_frame_with_metadata(
    ReactorHandle *handle,
    const char    *track_name,
    const uint8_t *data,
    uint32_t       width,
    uint32_t       height,
    const uint8_t *user_data,   /* nullable with user_data_len 0 */
    uint32_t       user_data_len
);

/*
 * Push interleaved i16 PCM audio into a named sendonly track.
 *   track_name          — name of the sendonly audio track
 *   data                — interleaved i16 PCM samples (little-endian)
 *   samples_per_channel — number of samples per channel in this call
 *   sample_rate         — must match the source (48000)
 *   num_channels        — must match the source (1 = mono)
 * No-op if handle is NULL, track_name is NULL, data is NULL, or the named
 * track has no attached audio source.
 */
void reactor_push_audio_frame(
    ReactorHandle  *handle,
    const char     *track_name,
    const int16_t  *data,
    uint32_t        samples_per_channel,
    uint32_t        sample_rate,
    uint32_t        num_channels
);

#ifdef __cplusplus
}
#endif

#endif /* REACTOR_FFI_H */
