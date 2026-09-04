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

/* ── ABI version ──────────────────────────────────────────────────────────── */

/*
 * The version of this ABI, as a monotonic integer.
 *
 * Two halves of one check.  REACTOR_ABI_VERSION is what the header you are
 * compiling against says; reactor_abi_version() is what the library you end up
 * loading says.  Compare them at startup and refuse to run when they differ,
 * naming both numbers — nothing else catches this failure.
 * scripts/check-abi-parity.py compares the hand-written copies of this ABI *by
 * function name only* — arity and types are not checked and cannot be — so a
 * function that gained a parameter still links, still resolves, and corrupts the
 * stack at the call.  It does not fail at load.  It looks like a hang, or like
 * the operation silently doing nothing.  Twice now the library on disk was simply
 * older than the crates.
 *
 * A macro rather than only prose so a binding can make that comparison without
 * hard-coding a number of its own, which would be a third copy of it.
 *
 * Bump it when an existing declaration below changes: a parameter added or
 * removed, a type changed, a return value repurposed.  Do NOT bump it when a
 * function is added — a binding built against the older version calls everything
 * it knows about exactly as before, and refusing to run would strand it for no
 * reason.
 */
#define REACTOR_ABI_VERSION 2

uint32_t reactor_abi_version(void);

/* ── Opaque client handle ─────────────────────────────────────────────────── */

typedef struct ReactorHandle ReactorHandle;

/* ── Event callback types ─────────────────────────────────────────────────── */

/* Status string: "disconnected" | "connecting" | "waiting" | "ready" */
typedef void (*reactor_on_status_fn)(const char *status, void *userdata);

/* JSON object: { code, message, recoverable, timestamp_ms, status?, operation? }
 * — the same shape and the same codes as reactor_completion_fn's error_json,
 * documented there. */
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
    /* When the sender says it captured the frame, in µs on the sender's own
     * clock — its declared capture time, or a reading its transport took for it.
     * Differences between stamps from one sender are what it supports; it is not
     * comparable with a local clock.  0 when no metadata. */
    uint64_t timestamp_us,
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
 *   error_json : JSON error object on failure (NULL on success)
 * Both strings are freed after the callback returns; copy them if needed.
 *
 * The third argument used to be a bare human-readable string, and callers that
 * only printed it now print JSON.  It carries what that string could not:
 *
 *   {
 *     "code":        stable, matchable — see below; never empty
 *     "message":     human-readable, the old string
 *     "recoverable": bool — whether the same call could pass later
 *     "status":      HTTP status, present only when the failure came from one
 *     "operation":   which call failed, e.g. "connect", "send_command"
 *   }
 *
 * "code" is one of INVALID_STATE, DISCONNECTED, NETWORK_ERROR, REQUEST_TIMEOUT,
 * TRANSPORT_ERROR, UNAUTHORIZED, NOT_FOUND, CONFLICT, RATE_LIMITED, BAD_REQUEST,
 * SERVER_ERROR, VERSION_MISMATCH, DECODE_FAILED, SESSION_TERMINAL,
 * MESSAGE_TOO_LARGE, ABORTED, INTERNAL_ERROR — *or* a code the platform sent for
 * a rejected control request, command or recording, which is open-ended.  Treat
 * an unrecognised code as an error you cannot classify, never as a parse failure.
 *
 * on_error reports the same codes, in the same shape plus "timestamp_ms".  The
 * two channels used to disagree — a 401 during connect was UNAUTHORIZED and not
 * recoverable to the caller, and CONNECTION_FAILED and recoverable on the event.
 * There is no "component" field: which tier of the platform failed is not
 * something a caller can act on, and splitting the codes by it is what produced
 * two names for one failure.
 */
typedef void (*reactor_completion_fn)(
    int         ok,
    const char *result_json,
    const char *error_json,
    void       *userdata
);

/* ── Authentication ───────────────────────────────────────────────────────── */

/*
 * Exchange an API key for a JWT.
 *
 * Takes no handle: everything below wants a token, and a caller holding a key
 * needs this first.  It is one POST, and it lives here so a binding in a language
 * with no HTTP client in its standard library does not have to take on a TLS
 * stack to make it.
 *
 *   api_url      — coordinator base URL, e.g. "https://api.reactor.inc"
 *   api_key      — the key to exchange
 *   options_json — nullable.  JSON object:
 *                    {
 *                      "models":                        ["owner/name", …],  // scopes the token
 *                      "max_sessions":                  n,   // scoped tokens only
 *                      "max_session_duration_seconds":  n,   // scoped tokens only; 1-86400
 *                      "expires_after":                 seconds             // server clamps it
 *                    }
 *                  Null (or "{}") mints a token carrying everything the key's
 *                  roles allow — fine server-to-server, wrong to hand to a client
 *                  you do not control.  An **unrecognised key in this object is
 *                  an error**: dropping a misspelt "models" in silence would mint
 *                  exactly the unscoped token the caller was avoiding.
 *   local        — non-zero to accept a dev coordinator's self-signed certificate
 *   completion   — result_json is {"jwt": "…"} on success; on failure the usual
 *                  error object, so a rejected key reports UNAUTHORIZED and an
 *                  unreachable coordinator NETWORK_ERROR.
 *
 * There is no handle, so reactor_destroy() is not the boundary for the callback
 * context here: the completion fires exactly once, and the context must stay
 * valid until it does.  Release it from inside the completion, or after.
 *
 * For the same reason a null api_url or api_key *completes with an error*, where
 * the handle-taking calls below return without completing at all.  There, a null
 * handle is something the binding already had to check; here there is no handle to
 * have checked, so the only alternative would be a future nothing ever resolves.
 */
void reactor_fetch_jwt(
    const char           *api_url,
    const char           *api_key,
    const char           *options_json,  /* nullable */
    int                   local,
    reactor_completion_fn completion,
    void                 *userdata
);

/* ── Lifecycle ────────────────────────────────────────────────────────────── */

/*
 * Create a new Reactor client.
 *
 *   api_url     — coordinator base URL, e.g. "https://api.reactor.inc"
 *   model_name  — model to connect to
 *   jwt         — JWT token, or NULL for unauthenticated (local dev)
 *   local       — non-zero to enable local-dev mode (skips TLS cert checks, etc.)
 *   callbacks   — event callbacks struct, or NULL (no events)
 *   sdk_version — reported as client_info.sdk_version to the coordinator, or NULL
 *                 to fall back to reactor-core's own workspace version. Pass your
 *                 binding's published package version (npm/PyPI/etc.), not this
 *                 default — the coordinator needs to see what actually shipped.
 *   sdk_type    — reported as client_info.sdk_type, or NULL to fall back to
 *                 "ffi". Pass a language tag ("python", "cpp", …) so the
 *                 coordinator can tell bindings apart; every FFI binding
 *                 defaults to the same "ffi" otherwise.
 *
 * Returns NULL only on allocation failure (extremely unlikely).
 * The returned handle must be destroyed with reactor_destroy().
 */
ReactorHandle *reactor_create(
    const char           *api_url,
    const char           *model_name,
    const char           *jwt,        /* nullable */
    int                   local,
    const ReactorCallbacks *callbacks, /* nullable */
    const char           *sdk_version, /* nullable */
    const char           *sdk_type     /* nullable */
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
 *
 * sdk_version and sdk_type: see reactor_create.
 */
ReactorHandle *reactor_create_with_adm(
    const char           *api_url,
    const char           *model_name,
    const char           *jwt,        /* nullable */
    int                   local,
    const ReactorCallbacks *callbacks, /* nullable */
    int                   adm_mode,
    const char           *sdk_version, /* nullable */
    const char           *sdk_type     /* nullable */
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
 *   session_id    — nullable; pass NULL to create a new session.
 *   connection_id — nullable; pass NULL to register a new connection. Non-NULL
 *                   adopts a connection id a backend already registered for
 *                   this session, the same way a non-NULL session_id adopts an
 *                   existing session — most callers pass NULL for both.
 *   completion    — called with ok=1 and result_json="{}" on success.
 */
void reactor_connect(
    ReactorHandle       *handle,
    const char          *session_id,     /* nullable */
    const uint32_t      *connection_id,  /* nullable */
    reactor_completion_fn completion,
    void                *userdata
);

/*
 * Disconnect and end the session server-side.  Not recoverable -- call
 * reactor_reconnect instead of reactor_disconnect + reactor_connect to keep it.
 */
void reactor_disconnect(
    ReactorHandle       *handle,
    reactor_completion_fn completion,
    void                *userdata
);

/*
 * Reconnect using the existing session -- after a transient failure, or from
 * "ready" to deliberately cycle the connection.  Tears down the live connection
 * first if there is one, without ending the session server-side.  Fails if there
 * is no session to reconnect to.
 */
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
 * Bitrate bounds, in bits per second. Pass -1 for any bound that should keep
 * the WebRTC default. -1 is the only value that means that: any other negative
 * fails the operation rather than clearing the bound, since reading a typo as
 * "remove the cap" would be the opposite of what was asked, and would report
 * success for it. Zero is a value like any other and reaches the engine.
 *
 * There are two ceilings and they are conjunctive — the lower one wins:
 *
 *   reactor_set_bitrate       bounds what the whole CONNECTION may allocate.
 *   reactor_set_track_bitrate bounds ONE SENDER's share of that allocation.
 *
 * Raising the connection's ceiling alone will not make a video track exceed
 * 2.5 Mbps. With no per-sender maximum set, WebRTC derives one from the frame
 * size, and that is 2500 kbps for anything above 960x540 — so 720p, 1080p and
 * 4K all cap there. reactor_set_track_bitrate is the one that lifts it.
 *
 * Both are callable before reactor_connect. The bounds are remembered and
 * applied as soon as the peer connection exists, and again on every reconnect,
 * which is the only way start_bps can skip the ramp it exists to skip.
 *
 * result_json is "{}" on success.
 */
void reactor_set_bitrate(
    ReactorHandle       *handle,
    int32_t              min_bps,
    int32_t              start_bps,
    int32_t              max_bps,
    reactor_completion_fn completion,
    void                *userdata
);

/*
 * Per-sender bitrate bounds for one track. See reactor_set_bitrate above for
 * how the two ceilings relate; this is the one that lifts the 2.5 Mbps video
 * default.
 *
 * Once the session has declared its tracks, a name it did not declare fails
 * the operation rather than being remembered for a track that will never
 * exist.
 */
void reactor_set_track_bitrate(
    ReactorHandle       *handle,
    const char          *name,
    int32_t              min_bps,
    int32_t              max_bps,
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
 * A statistics snapshot for the live connection.
 *
 * Asynchronous, unlike the synchronous reads further down, and for a reason
 * worth knowing: the engine collects a report by dispatching onto its own thread
 * and waiting for it, so a synchronous read here would block the caller's thread
 * for however long libwebrtc takes — up to ten seconds in the pathological case.
 *
 * result_json is an object:
 *
 *   {
 *     "rtt_ms":               ms, from the ICE candidate pair ICE nominated
 *     "jitter_s":             seconds, on the received video stream
 *     "packet_loss_ratio":    0..1, cumulative, on the received video stream
 *     "incoming_bitrate_bps": measured on the nominated pair, over the window
 *                             since the previous call
 *     "outgoing_bitrate_bps": likewise
 *     "available_incoming_bitrate_bps": the congestion controller's estimate of
 *                             what the path can carry — not what is flowing
 *     "available_outgoing_bitrate_bps": likewise
 *     "target_bitrate_bps":   what the encoders are aiming at, summed
 *     "frames_per_second":    on the received video stream
 *     "candidate_type":       "host" | "srflx" | "prflx" | "relay" — "relay"
 *                             means the media is going through TURN
 *     "relay_protocol":       "udp" | "tcp" | "tls" when relayed, else null
 *     "candidate_pair_state": "succeeded" | "waiting" | "in-progress" |
 *                             "failed" | "cancelled"
 *     "packets_received", "packets_lost", "packets_sent",
 *     "bytes_received", "bytes_sent":  cumulative counters, summed over streams
 *     "timestamp_ms":         when the sample was taken, Unix ms
 *     "inbound":  [{ssrc, kind, packets_received, packets_lost, bytes_received,
 *                   jitter_s, nack_count, total_decode_time_s,
 *                   frames_per_second, frames_decoded, frames_dropped,
 *                   frame_width, frame_height}, …]
 *     "outbound": [{ssrc, kind, packets_sent, retransmitted_packets_sent,
 *                   bytes_sent, target_bitrate_bps, round_trip_time_s,
 *                   total_round_trip_time_s, fraction_lost, packets_lost,
 *                   frames_per_second, frames_sent, frame_width,
 *                   frame_height}, …]
 *     "candidate_pairs": [{current_round_trip_time_s, total_round_trip_time_s,
 *                          priority, state, nominated, writable,
 *                          available_outgoing_bitrate_bps,
 *                          available_incoming_bitrate_bps, bytes_sent,
 *                          bytes_received, packets_sent, packets_received,
 *                          local_candidate_type, local_relay_protocol}, …]
 *   }
 *
 * A stream's "kind" is "audio", "video" or null.  It is what makes "the video
 * stream's jitter" answerable, and the scalars above take it: jitter, loss and
 * fps come from the received video stream, which is the stream the browser SDK
 * reads.  With no video stream, jitter and loss fall back to the aggregate
 * across receive streams — the browser reports nothing there, and this
 * deliberately does not.
 *
 * Every scalar that can be unknown is present as null rather than omitted, so a
 * binding can tell "the engine has not measured this" from "this SDK does not
 * report it".  The two measured bitrates are derived against the previous call,
 * on the nominated pair's byte counters: the first call after connecting reports
 * null for them, so does a call made less than 200 ms after the last one, and so
 * does one made before ICE has nominated a pair.
 *
 * Read "nominated" rather than inferring the selected pair from "state" and
 * "priority".  A connection gathers many pairs — a plain loopback produces
 * eighteen — and exactly one carries traffic; the rest report zeroes.
 *
 * A pair's byte counters cover everything it carried, RTCP and data channel
 * included, where the per-stream counters are RTP payload for one stream.  A
 * bitrate derived from one will not match a bitrate derived from the other.
 *
 * "packets_lost" is signed.  RFC 3550 allows it to go negative when duplicates
 * arrive; "packet_loss_ratio" floors each stream at zero first, so one stream's
 * duplicates cannot cancel another's real loss.
 *
 * An outbound stream's "round_trip_time_s", "total_round_trip_time_s",
 * "fraction_lost" and "packets_lost" come from the far end's RTCP report about
 * us, so they stay 0 until it has sent one — a zero there means "not measured
 * yet", not a zero-latency link.
 *
 * Fails with INVALID_STATE unless the session is ready — a report of zeroes
 * cannot be told from a connection carrying nothing.
 */
void reactor_get_stats(
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

/* ── Clip download ────────────────────────────────────────────────────────── */

/* Segments written so far, out of how many the clip has. */
typedef void (*reactor_progress_fn)(uint32_t done, uint32_t total, void *userdata);

/*
 * Download a clip's HLS segments into one playable file at `out_path`.
 *
 * Reactor does not host clips: the playlist names the fragments and it is on the
 * caller to fetch and assemble them.  That assembly is here rather than in each
 * binding because it has three rules that each cost a shipped bug to learn:
 *
 *   - The init segment is a comment line.  `#EXT-X-MAP:URI="…"` carries the
 *     `ftyp`/`moov` every fragment is parsed against, so a parser that skips `#`
 *     lines writes a file no player opens.  It is fetched first and written first.
 *   - A segment can be presigned on another host, and a presigned URL *rejects* an
 *     Authorization header rather than ignoring it.  The token goes same-origin
 *     only.
 *   - A 202 is not an error.  It means the chunk holding the end of the window has
 *     not closed, and it closes because the model keeps generating — so the bound
 *     on waiting is the session, not a number of seconds.
 *
 *   handle                — nullable.  Given one, the wait ends as soon as that
 *                           session can no longer produce the clip: once it is
 *                           gone a 202 is a 202 forever.  Only its state is read,
 *                           and through a clone taken before this returns, so
 *                           destroying it mid-download is safe.
 *   playlist_url          — from a clip's result_json.
 *   jwt                   — nullable; needed for a coordinator-hosted playlist.
 *   out_path              — file to create.  Opened before the first segment is
 *                           fetched, so an unwritable path fails early.
 *   predicted_ready_at_ms — the runtime's own prediction, in Unix milliseconds, as
 *                           carried by the clip.  The grace below is measured from
 *                           there, not from this call: a clip expected in ten
 *                           seconds with five of grace gets fifteen.  0 when the
 *                           runtime offered none, which runs the grace from now.
 *   ready_timeout_seconds — grace past that prediction.  Negative waits as long as
 *                           the session lives, which is the only sane answer for a
 *                           model generating slower than real time; an infinity
 *                           asks for the same.  A NaN comes back through
 *                           `completion` as an error.
 *   local                 — non-zero to accept a dev coordinator's certificate.
 *   progress              — nullable.  Called after each segment is written, on
 *                           the download's own thread; blocking it delays this
 *                           download and nothing else.
 *   completion            — result_json is {"path", "bytes", "segments"}.
 *
 * As with reactor_fetch_jwt, the completion is *not* bounded by
 * reactor_destroy(): a download outlives the handle it was given one of.  Keep
 * its context alive until the completion fires, which it does exactly once.
 */
void reactor_download_clip(
    ReactorHandle        *handle,      /* nullable */
    const char           *playlist_url,
    const char           *jwt,         /* nullable */
    const char           *out_path,
    double                predicted_ready_at_ms,
    double                ready_timeout_seconds,
    int                   local,
    reactor_progress_fn   progress,    /* nullable */
    reactor_completion_fn completion,
    void                 *userdata
);

/* ── Synchronous operations ───────────────────────────────────────────────── */

/*
 * Deactivate a sendonly track (sync — no network round trip, only a local
 * status check and a fire-and-forget notification).
 *
 * NULL on success. On failure, a heap JSON error object
 * ({"code","message","recoverable","status","operation","retry_after_ms"} —
 * the same shape every completion reports) that the caller must free with
 * reactor_free_string().
 */
char *reactor_unpublish_track(
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
 * The engine's monotonic clock, in microseconds — the epoch
 * reactor_push_video_frame_with_metadata_at() reads its capture time in.
 *
 * Read it once per unit of produced media and stamp every track with that one
 * value: tracks are synchronised by sharing a capture time, not by reaching the
 * encoder at the same moment.  Unrelated to time(2)'s epoch — a UNIX timestamp is
 * not a substitute.  Takes no handle.
 */
int64_t reactor_time_micros(void);

/*
 * Push a BGRA frame stamped with the caller's own capture time, in microseconds,
 * read from reactor_time_micros(), optionally tagged with `user_data`.
 *
 * Without a capture time a frame is stamped as it is pushed, so several tracks
 * capturing one moment arrive stamped microseconds apart.  Pass the same
 * `capture_time_us` for every track of one capture and the far end reads them as
 * the one moment they are.
 *
 * Stamping and tagging are independent: `user_data` may be NULL with
 * `user_data_len` 0.  Same buffer requirements as the functions above: `data`
 * must hold width * height * 4 bytes.
 */
void reactor_push_video_frame_with_metadata_at(
    ReactorHandle *handle,
    const char    *track_name,
    const uint8_t *data,
    uint32_t       width,
    uint32_t       height,
    const uint8_t *user_data,   /* nullable with user_data_len 0 */
    uint32_t       user_data_len,
    int64_t        capture_time_us
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
