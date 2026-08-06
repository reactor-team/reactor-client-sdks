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
 * All event callbacks (on_status, on_error, …) are invoked on an internal
 * tokio thread.  The strings passed to them are valid only for the duration
 * of the callback (copy them if you need them later).
 *
 * Completion callbacks (reactor_completion_fn) are also invoked on a tokio
 * thread, exactly once per call.
 *
 * The caller is responsible for ensuring that `userdata` pointers are safe
 * to dereference from any thread that may call the callbacks.
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

/* Raw video frame in BGRA format (B, G, R, A bytes).  width * height * 4 bytes. */
typedef void (*reactor_on_frame_fn)(const uint8_t *data, uint32_t width, uint32_t height, void *userdata);

/* Audio frame callback: interleaved int16 PCM, total sample count, sample rate (Hz), channels. */
typedef void (*reactor_on_audio_fn)(const int16_t *samples, uint32_t num_samples, uint32_t sample_rate, uint32_t channels, void *userdata);

/* ── Callbacks registration struct ───────────────────────────────────────── */

typedef struct ReactorCallbacks {
    reactor_on_status_fn          on_status;           /* nullable */
    reactor_on_error_fn           on_error;            /* nullable */
    reactor_on_message_fn         on_message;          /* nullable */
    reactor_on_runtime_message_fn on_runtime_message;  /* nullable */
    reactor_on_track_fn           on_track;            /* nullable */
    reactor_on_capabilities_fn    on_capabilities;     /* nullable */
    reactor_on_session_id_fn      on_session_id;       /* nullable */
    reactor_on_frame_fn           on_frame;            /* nullable; called on Tokio thread at video rate */
    reactor_on_audio_fn           on_audio;            /* nullable; decoded remote PCM (~10 ms/frame) */
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
 *   adm_mode 0 = synthetic (headless: app pushes PCM, receives via on_audio),
 *            1 = platform  (real mic/speaker + AEC/NS/AGC),
 *            other        = default (platform on desktop, synthetic on Android).
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
 * Destroy a handle.  Must not be called while other operations are in flight
 * (wait for all completion callbacks to fire first).
 */
void reactor_destroy(ReactorHandle *handle);

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
 * Upload a local file.
 * result_json: { upload_id, name, mime_type, size }
 */
void reactor_upload_file(
    ReactorHandle       *handle,
    const char          *path,
    reactor_completion_fn completion,
    void                *userdata
);

/* ── Synchronous operations ───────────────────────────────────────────────── */

/*
 * Send an application-scoped command over the data channel (fire-and-forget).
 *   name      — command name
 *   args_json — JSON object, or NULL (treated as {})
 * Returns 0 on success, -1 on error (not connected, message too large, …).
 */
int reactor_send_command(
    ReactorHandle *handle,
    const char    *name,
    const char    *args_json  /* nullable */
);

/*
 * Send a runtime-scoped command over the data channel.
 * Same signature / semantics as reactor_send_command.
 */
int reactor_send_runtime_command(
    ReactorHandle *handle,
    const char    *name,
    const char    *args_json  /* nullable */
);

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

/* Free a string returned by reactor_session_id(). */
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
