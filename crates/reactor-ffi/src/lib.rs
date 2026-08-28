//! C ABI shared library for the Reactor client SDK.
//!
//! Bundles a multi-threaded tokio runtime and a libwebrtc-style WebRTC
//! transport.  Language bindings (Python ctypes, Swift, Kotlin, Go, C++)
//! load the resulting dylib/so and call the `reactor_*` functions.
//!
//! # Safety contract
//!
//! Every `reactor_*` entry point shares these invariants; each function's own
//! `# Safety` section states only what it adds.
//!
//! * **Handles.** A `*mut ReactorHandle` is either null or a pointer returned by
//!   [`reactor_create`] / [`reactor_create_with_adm`] that has not yet been passed
//!   to [`reactor_destroy`]. Null is always accepted and handled. A destroyed
//!   handle is not: reusing one is undefined behaviour.
//! * **Strings in.** Every `*const c_char` parameter is either explicitly
//!   documented as nullable or must be a NUL-terminated C string, valid for reads
//!   for the duration of the call. Contents are copied before the call returns, so
//!   the caller may free them immediately after.
//! * **Buffers in.** Pointer + length pairs must be readable for the stated number
//!   of elements. They are borrowed for the call only.
//! * **Callbacks.** Function pointers in [`ReactorCallbacks`] are invoked from
//!   threads this library owns, never from the caller's thread, and may be invoked
//!   concurrently. `userdata` is passed through untouched and must be safe to
//!   access from any such thread.
//! * **Callback lifetime.** Every pointer stays callable until
//!   [`reactor_destroy`] returns 0, and none is called after. That is the boundary a
//!   binding releases its callback context on — a ctypes trampoline, a
//!   `cgo.Handle`, a JNI `GlobalRef`. A non-zero return means a callback could not
//!   be waited for and the context must be kept alive instead. See
//!   [`callbacks::CallbackGate`].
//! * **Blocking is tolerated.** Callbacks run on threads dedicated to them, never on
//!   a libwebrtc media thread or a tokio worker, so a host that takes its time
//!   delays only its own stream. Three threads, because their backpressure differs:
//!   control events and completions on one (unbounded, low-rate), video on one
//!   (newest frame wins, so a slow host sees fresh frames instead of a backlog), and
//!   audio on one (short queue, oldest wins, because there the queue is the jitter
//!   buffer). Media frames are copied out of libwebrtc's buffers to make that
//!   hand-off possible.

mod callbacks;
mod http;
mod peer;

use std::collections::BTreeMap;
use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_double, c_int, c_void};
use std::sync::{Arc, Mutex};

use futures::StreamExt;
use reactor_core::auth::{fetch_jwt, TokenRequest};
use reactor_core::error::CoreError;
use reactor_core::events::ReactorEvent;
use reactor_core::http::{check_status, StaticAuth};
use reactor_core::peer::PeerEvent;
use reactor_core::protocol::upload::FileRef;
use reactor_core::reactor::{ConnectOptions, Reactor, ReactorDeps, ReactorOptions};
use reactor_core::recording::{clip_segment_requests, Readiness};
use reactor_core::runtime::TokioPlatform;
use reactor_core::state::ReactorStatus;
use reactor_core::{SharedHttp, SharedPlatform};
use reactor_webrtc::AdmMode;

use self::callbacks::{CallbackGate, HostSender, HostThread, Overflow, Quiescence};
use self::http::ReqwestHttpClient;
use self::peer::ReactorWebRtcPeerTransport;

// ── Android JNI bootstrap ────────────────────────────────────────────────────

#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn JNI_OnLoad(
    vm: *mut jni::sys::JavaVM,
    _reserved: *mut std::ffi::c_void,
) -> jni::sys::jint {
    // SAFETY: `vm` is the process JavaVM the Android runtime passes on load.
    unsafe { reactor_webrtc::platform::android_init(vm as *mut std::ffi::c_void) };
    jni::sys::JNI_VERSION_1_6
}

// ── Global tokio runtime ─────────────────────────────────────────────────────

static RUNTIME: std::sync::OnceLock<tokio::runtime::Runtime> = std::sync::OnceLock::new();

pub(crate) fn runtime() -> &'static tokio::runtime::Runtime {
    RUNTIME.get_or_init(|| {
        let _ = env_logger::builder().try_init();
        tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()
            .expect("failed to build tokio runtime")
    })
}

// ── C types exposed in reactor_ffi.h ─────────────────────────────────────────

#[repr(C)]
pub struct ReactorCallbacks {
    pub on_status: Option<unsafe extern "C" fn(*const c_char, *mut c_void)>,
    pub on_error: Option<unsafe extern "C" fn(*const c_char, *mut c_void)>,
    pub on_message: Option<unsafe extern "C" fn(*const c_char, *mut c_void)>,
    pub on_runtime_message: Option<unsafe extern "C" fn(*const c_char, *mut c_void)>,
    pub on_track: Option<unsafe extern "C" fn(*const c_char, *const c_char, *mut c_void)>,
    pub on_capabilities: Option<unsafe extern "C" fn(*const c_char, *mut c_void)>,
    pub on_session_id: Option<unsafe extern "C" fn(*const c_char, *mut c_void)>,
    /// Leading `*const c_char` is the name of the track the frame arrived on,
    /// empty when the transceiver could not be matched to a declared track.
    pub on_frame: Option<
        unsafe extern "C" fn(
            *const c_char,
            *const u8,
            u32,
            u32,
            u64,
            u64,
            *const u8,
            u32,
            *mut c_void,
        ),
    >,
    /// Leading `*const c_char` as [`ReactorCallbacks::on_frame`].
    pub on_audio:
        Option<unsafe extern "C" fn(*const c_char, *const i16, u32, u32, u32, *mut c_void)>,
    pub userdata: *mut c_void,
}

// SAFETY: the caller guarantees `userdata` is safe to access from any tokio thread.
unsafe impl Send for ReactorCallbacks {}
unsafe impl Sync for ReactorCallbacks {}

struct CallbackSet {
    on_status: Option<unsafe extern "C" fn(*const c_char, *mut c_void)>,
    on_error: Option<unsafe extern "C" fn(*const c_char, *mut c_void)>,
    on_message: Option<unsafe extern "C" fn(*const c_char, *mut c_void)>,
    on_runtime_message: Option<unsafe extern "C" fn(*const c_char, *mut c_void)>,
    on_track: Option<unsafe extern "C" fn(*const c_char, *const c_char, *mut c_void)>,
    on_capabilities: Option<unsafe extern "C" fn(*const c_char, *mut c_void)>,
    on_session_id: Option<unsafe extern "C" fn(*const c_char, *mut c_void)>,
    userdata: *mut c_void,
    /// Every fire below goes through this. Once `reactor_destroy` retires it, the
    /// pointers above must be treated as dangling.
    gate: Arc<CallbackGate>,
}
unsafe impl Send for CallbackSet {}
unsafe impl Sync for CallbackSet {}

impl CallbackSet {
    fn fire_str(&self, f: Option<unsafe extern "C" fn(*const c_char, *mut c_void)>, s: &str) {
        if let Some(func) = f {
            if let Ok(cs) = CString::new(s) {
                let Some(_admitted) = self.gate.enter() else {
                    return;
                };
                unsafe { func(cs.as_ptr(), self.userdata) }
            }
        }
    }

    fn fire_json_val(
        &self,
        f: Option<unsafe extern "C" fn(*const c_char, *mut c_void)>,
        v: &serde_json::Value,
    ) {
        self.fire_str(f, &v.to_string());
    }

    fn fire_opt_str(
        &self,
        f: Option<unsafe extern "C" fn(*const c_char, *mut c_void)>,
        opt: &Option<String>,
    ) {
        if let Some(func) = f {
            let Some(_admitted) = self.gate.enter() else {
                return;
            };
            match opt {
                Some(s) => {
                    if let Ok(cs) = CString::new(s.as_str()) {
                        unsafe { func(cs.as_ptr(), self.userdata) }
                    }
                }
                None => unsafe { func(std::ptr::null(), self.userdata) },
            }
        }
    }

    fn fire_track(&self, name: &str, mid: &Option<String>) {
        if let Some(func) = self.on_track {
            if let Ok(name_cs) = CString::new(name) {
                let Some(_admitted) = self.gate.enter() else {
                    return;
                };
                match mid {
                    Some(m) => {
                        if let Ok(mid_cs) = CString::new(m.as_str()) {
                            unsafe { func(name_cs.as_ptr(), mid_cs.as_ptr(), self.userdata) }
                        }
                    }
                    None => unsafe { func(name_cs.as_ptr(), std::ptr::null(), self.userdata) },
                }
            }
        }
    }
}

// ── ReactorHandle ────────────────────────────────────────────────────────────

/// A unit of host work queued for the control thread: an event dispatch or a
/// completion. Boxed because the two have nothing else in common.
type HostJob = Box<dyn FnOnce() + Send + 'static>;

/// A decoded remote video frame, copied out of libwebrtc's buffer so the media
/// thread can return immediately.
///
/// `track` is built here, on the media thread that already copies the pixels,
/// rather than on the delivery thread — one allocation next to a frame-sized one
/// costs nothing, and it keeps the host thread doing nothing but the call.
struct VideoFrame {
    track: CString,
    bgra: Vec<u8>,
    width: u32,
    height: u32,
    frame_id: u64,
    timestamp_us: u64,
    user_data: Vec<u8>,
}

/// A decoded remote audio frame, likewise copied.
struct AudioFrame {
    track: CString,
    pcm: Vec<i16>,
    sample_rate: u32,
    channels: u32,
}

/// How many audio frames may queue before the oldest wins.
///
/// Frames are ~10 ms, so this is roughly a third of a second of slack — enough to
/// ride out a GC pause or a scheduling hiccup in the host, short enough that a host
/// which is simply too slow does not accumulate unbounded latency.
const AUDIO_QUEUE_DEPTH: usize = 32;

/// The threads this handle uses to call the host.
///
/// Three, because their backpressure requirements differ and one slow consumer must
/// not delay the others: a host stalling on video should not hold up a status change
/// or make audio glitch.
struct HostThreads {
    /// Control events and completions. Unbounded: low-rate, and dropping a status
    /// change or a session id would leave the host with a wrong view of the session.
    control: HostThread<HostJob>,
    /// Their senders were moved into the media callbacks at construction, so these
    /// exist to be torn down: dropping them closes each queue and joins the thread,
    /// which is what guarantees no delivery outlives the handle.
    video: Option<HostThread<VideoFrame>>,
    audio: Option<HostThread<AudioFrame>>,
}

impl HostThreads {
    /// Close every queue without waiting for the threads.
    ///
    /// Used when teardown could not confirm quiescence: at least one of these
    /// threads may be stuck inside a host callback that never returns, and joining
    /// it would hang `reactor_destroy` — which would also stop the caller from ever
    /// learning that it must keep its callback pointers alive.
    fn abandon(&mut self) {
        self.control.abandon();
        if let Some(video) = self.video.as_mut() {
            video.abandon();
        }
        if let Some(audio) = self.audio.as_mut() {
            audio.abandon();
        }
    }
}

/// Background tasks this handle owns, so `reactor_destroy` can stop them.
///
/// Without this the handle leaks: the peer-event pump holds an `Arc<Reactor>`, and
/// `Reactor` owns the `Dispatcher` whose sender feeds the event pump, so neither
/// stream ever ends and neither task ever exits. The heartbeat leaks the same way —
/// it stops on a session epoch change, which destroying a handle does not cause.
type TaskSet = Arc<Mutex<Vec<tokio::task::JoinHandle<()>>>>;

pub struct ReactorHandle {
    reactor: Arc<Reactor>,
    gate: Arc<CallbackGate>,
    tasks: TaskSet,
    /// Owned outright rather than shared, so `reactor_destroy` is what joins these
    /// threads — not whichever tokio task happened to hold the last reference.
    hosts: HostThreads,
}

/// Spawn a task the handle will stop on destroy.
///
/// Finished handles are reaped first: `connect` and `reconnect` each add a
/// heartbeat, and a long-lived client should not accumulate them.
fn spawn_tracked(tasks: &TaskSet, future: impl std::future::Future<Output = ()> + Send + 'static) {
    let handle = runtime().spawn(future);
    let mut tasks = tasks.lock().unwrap();
    tasks.retain(|t| !t.is_finished());
    tasks.push(handle);
}

struct Completion {
    f: Option<unsafe extern "C" fn(c_int, *const c_char, *const c_char, *mut c_void)>,
    userdata: *mut c_void,
    gate: Arc<CallbackGate>,
    /// The entry point this completion belongs to, reported as `operation` so a
    /// host can say which call failed without tracking it itself.
    operation: &'static str,
}
unsafe impl Send for Completion {}
unsafe impl Sync for Completion {}

impl Completion {
    fn new(
        f: Option<unsafe extern "C" fn(c_int, *const c_char, *const c_char, *mut c_void)>,
        userdata: *mut c_void,
        gate: Arc<CallbackGate>,
        operation: &'static str,
    ) -> Self {
        Self {
            f,
            userdata,
            gate,
            operation,
        }
    }

    fn resolve(self, result: Result<Option<serde_json::Value>, CoreError>) {
        let Some(func) = self.f else { return };
        // An operation can still be in flight when the handle is destroyed. Firing
        // its completion afterwards would call a pointer the host has already
        // released.
        let Some(_admitted) = self.gate.enter() else {
            return;
        };
        match result {
            Ok(v) => {
                let json = v.map(|j| j.to_string()).unwrap_or_else(|| "{}".to_string());
                if let Ok(cs) = CString::new(json) {
                    unsafe { func(1, cs.as_ptr(), std::ptr::null(), self.userdata) }
                }
            }
            Err(e) => {
                // A JSON object rather than the Display string this used to send.
                // The string was enough to log and not enough to branch on, so
                // every binding above collapsed all of these into one error type;
                // the code and whether retrying is worth anything were both in
                // the `CoreError` and both thrown away here.
                let details = e.details(Some(self.operation));
                let json = serde_json::to_string(&details)
                    .unwrap_or_else(|_| fallback_error_json(&details.message));
                if let Ok(cs) = CString::new(json) {
                    unsafe { func(0, std::ptr::null(), cs.as_ptr(), self.userdata) }
                }
            }
        }
    }
}

/// A last-resort error payload, for the case where serialising the real one fails.
///
/// Reaching this means something is very wrong, but a host waiting on a completion
/// must still be given a well-formed object — dropping the call would leave it
/// awaiting forever, which is a worse failure than a vague error.
fn fallback_error_json(message: &str) -> String {
    serde_json::json!({
        "code": reactor_core::error::codes::INTERNAL_ERROR,
        "message": message,
        "recoverable": false,
    })
    .to_string()
}

// ── extern "C" API ───────────────────────────────────────────────────────────

/// The version of the C ABI this library exposes, reported by
/// [`reactor_abi_version`] and stated in prose at the top of `reactor_ffi.h`.
///
/// Bump it when an existing declaration changes — a parameter added or removed, a
/// type changed, a return value repurposed. Do **not** bump it when a function is
/// added: a binding built against the older version calls every function it knows
/// about exactly as before, so refusing to run would strand it for no reason.
pub const ABI_VERSION: u32 = 1;

/// The ABI version, so a binding can refuse a library it was not built for.
///
/// [`scripts/check-abi-parity.py`] compares the hand-written copies of this ABI by
/// function *name*; arity and types are not checked and cannot be. So a function
/// that gained a parameter still links, still resolves, and corrupts the stack at
/// the call — a hang, or an operation silently doing nothing, never a version
/// error. Twice now the library on disk was simply older than the crates. This is
/// the guard that turns that into a message.
///
/// Takes no handle and no lock: it is readable before anything is created.
///
/// [`scripts/check-abi-parity.py`]: https://github.com/reactor-team/reactor-client-sdks/blob/main/scripts/check-abi-parity.py
#[no_mangle]
pub extern "C" fn reactor_abi_version() -> u32 {
    ABI_VERSION
}

/// Fire a completion that belongs to no handle.
///
/// Handle-based completions travel over that handle's control thread, which is
/// what makes blocking inside one tolerable. A handle-less call has no such
/// thread, so its completion goes to the blocking pool instead of running on the
/// tokio worker that produced the result: a host that takes its time in one must
/// delay only itself, never the runtime.
fn resolve_detached(completion: Completion, result: Result<Option<serde_json::Value>, CoreError>) {
    runtime().spawn_blocking(move || completion.resolve(result));
}

/// A completion with no handle to be quiesced against.
///
/// [`reactor_destroy`] is the boundary for every callback a handle owns. These
/// have no handle, so the gate is per call and open for exactly as long as the
/// call: nothing can retire it early, and the caller keeps its context alive
/// until the completion fires — which it always does, exactly once.
fn detached_completion(
    completion: Option<unsafe extern "C" fn(c_int, *const c_char, *const c_char, *mut c_void)>,
    userdata: *mut c_void,
    operation: &'static str,
) -> Completion {
    Completion::new(
        completion,
        userdata,
        Arc::new(CallbackGate::new()),
        operation,
    )
}

/// Exchange an API key for a JWT.
///
/// Takes no handle: there is no session yet, and a caller needs the token before
/// it can create one. `options_json` may be null for a token carrying everything
/// the key's roles allow; otherwise it is a JSON object of
/// `{"models": ["owner/name", …], "max_sessions": n, "max_session_duration_seconds": n, "expires_after": seconds}`,
/// where `models` makes the token session-scoped. **An unrecognised key in there
/// is an error**, not a field to ignore: silently dropping a misspelt `models`
/// would mint the unscoped token the caller was trying to avoid.
///
/// On success `result_json` is `{"jwt": "…"}`. On failure it is the usual error
/// object, so a rejected key reports `UNAUTHORIZED` and an unreachable
/// coordinator `NETWORK_ERROR`.
///
/// # Safety
///
/// `api_url` and `api_key` must be NUL-terminated C strings; `options_json` may be
/// null, otherwise it must be a NUL-terminated C string holding a JSON object. All
/// are copied before this returns.
///
/// `completion` is called exactly once, on a thread this library owns, and must
/// stay callable until it does. There is no handle here, so [`reactor_destroy`] is
/// not the boundary for it: whatever the callback context is, release it from
/// inside the completion or after it has run.
#[no_mangle]
pub unsafe extern "C" fn reactor_fetch_jwt(
    api_url: *const c_char,
    api_key: *const c_char,
    options_json: *const c_char,
    local: c_int,
    completion: Option<unsafe extern "C" fn(c_int, *const c_char, *const c_char, *mut c_void)>,
    userdata: *mut c_void,
) {
    let done = detached_completion(completion, userdata, "fetch_jwt");

    if api_url.is_null() || api_key.is_null() {
        return resolve_detached(
            done,
            Err(CoreError::InvalidState(
                "fetch_jwt requires an api_url and an api_key".into(),
            )),
        );
    }

    let api_url = CStr::from_ptr(api_url).to_string_lossy().into_owned();
    let api_key = CStr::from_ptr(api_key).to_string_lossy().into_owned();

    let request = if options_json.is_null() {
        Ok(TokenRequest::default())
    } else {
        serde_json::from_str::<TokenRequest>(&CStr::from_ptr(options_json).to_string_lossy())
            .map_err(|e| CoreError::InvalidState(format!("fetch_jwt options are not valid: {e}")))
    };
    let request = match request {
        Ok(request) => request,
        Err(e) => return resolve_detached(done, Err(e)),
    };

    let http: SharedHttp = Arc::new(ReqwestHttpClient::new(local != 0));
    runtime().spawn(async move {
        let result = fetch_jwt(&http, &api_url, &api_key, &request)
            .await
            .map(|jwt| Some(serde_json::json!({ "jwt": jwt })));
        resolve_detached(done, result);
    });
}

/// Download a clip's HLS segments into one playable file.
///
/// Reactor does not host clips: the playlist names the fragments and it is on the
/// caller to fetch and assemble them. That assembly has rules that took three
/// shipped bugs to learn — the `#EXT-X-MAP` init segment is a comment line and
/// has to be written first, a presigned segment on another host *rejects* an
/// `Authorization` header rather than ignoring it, and a 202 means the chunk
/// holding the end of the window has not closed yet — so it lives in
/// [`reactor_core::recording`] and every binding gets the same answer.
///
/// `handle` is optional and is what bounds the wait: given one, this stops asking
/// as soon as that session stops being able to produce the clip. Only the session
/// state is read, and it is read through a clone taken before this returns, so
/// destroying the handle mid-download is safe and simply ends the waiting.
///
/// `ready_timeout_seconds` is the grace past when the runtime predicted the clip
/// would be ready, and `predicted_ready_at_ms` is that prediction — the
/// `predicted_ready_at_ms` a clip carries, in Unix milliseconds. The grace is
/// measured from there rather than from this call, so a clip expected in ten
/// seconds with five of grace is given fifteen; pass 0 to have the grace run from
/// now, which is the only thing left when the runtime offered no prediction.
///
/// Negative `ready_timeout_seconds` waits as long as the session lives, which is
/// the right answer when a handle was given and the only sane one when the model
/// generates slower than real time. An infinity asks for the same thing. A NaN is
/// refused through `completion`: it is a caller bug, and interpreting one as a
/// duration would panic.
///
/// # Safety
///
/// `playlist_url` and `out_path` must be NUL-terminated C strings; `jwt` may be
/// null. `handle` may be null; otherwise it must be live at the moment of the
/// call. `progress` may be null.
///
/// `completion` is called exactly once and, like [`reactor_fetch_jwt`]'s, is not
/// bounded by [`reactor_destroy`] — a download outlives the handle it was given.
/// Keep its context alive until it fires.
#[no_mangle]
pub unsafe extern "C" fn reactor_download_clip(
    handle: *mut ReactorHandle,
    playlist_url: *const c_char,
    jwt: *const c_char,
    out_path: *const c_char,
    predicted_ready_at_ms: c_double,
    ready_timeout_seconds: c_double,
    local: c_int,
    progress: Option<unsafe extern "C" fn(u32, u32, *mut c_void)>,
    completion: Option<unsafe extern "C" fn(c_int, *const c_char, *const c_char, *mut c_void)>,
    userdata: *mut c_void,
) {
    let done = detached_completion(completion, userdata, "download_clip");

    if playlist_url.is_null() || out_path.is_null() {
        return resolve_detached(
            done,
            Err(CoreError::InvalidState(
                "download_clip requires a playlist_url and an out_path".into(),
            )),
        );
    }

    let playlist_url = CStr::from_ptr(playlist_url).to_string_lossy().into_owned();
    let out_path = CStr::from_ptr(out_path).to_string_lossy().into_owned();
    let jwt = (!jwt.is_null()).then(|| CStr::from_ptr(jwt).to_string_lossy().into_owned());

    // Before the spawn, so a bad number comes back as an error rather than as a
    // panic in a task the host cannot see.
    let timeout = match readiness_timeout(ready_timeout_seconds) {
        Ok(timeout) => timeout,
        Err(error) => return resolve_detached(done, Err(error)),
    };

    // A prediction that is not a number is no prediction. Zero is what a caller
    // with nothing to anchor to passes, and it means the grace runs from now.
    let predicted_ready_at_ms = if predicted_ready_at_ms.is_finite() {
        predicted_ready_at_ms
    } else {
        0.0
    };

    // Cloned now, while the handle is known live. An `Arc` keeps the session state
    // readable for the length of the download even if the host destroys the handle
    // meanwhile, which a raw pointer captured into the task would not.
    let session = (!handle.is_null()).then(|| (*handle).reactor.clone());

    let progress = progress.map(|f| ProgressCallback { f, userdata });
    let http: SharedHttp = Arc::new(ReqwestHttpClient::new(local != 0));
    let platform: SharedPlatform = Arc::new(TokioPlatform);

    runtime().spawn(async move {
        let readiness = Readiness {
            timeout,
            predicted_ready_at_ms,
            session_is_live: || match &session {
                Some(reactor) => reactor.status() != ReactorStatus::Disconnected,
                // Nothing to ask: the caller's timeout is the only bound there is.
                None => true,
            },
        };

        let result = download_clip_to_file(
            &http,
            &platform,
            &playlist_url,
            jwt.as_deref(),
            &out_path,
            &readiness,
            progress.as_ref(),
        )
        .await;

        resolve_detached(done, result.map(Some));
    });
}

/// The wall-clock bound a C `double` asks for, or an error when it asks for
/// something that is not a bound at all.
///
/// A NaN is the case that has to be caught rather than interpreted:
/// `Duration::from_secs_f64` panics on one, and a panic inside the detached task
/// would drop the completion instead of firing it — the binding would then wait
/// for a callback that can no longer come, which is the one outcome every
/// argument check in this file exists to prevent. Negative asks for no bound at
/// all, and an infinity asks for the same thing by another spelling. A finite
/// value too large for a `Duration` saturates: no caller outlives either wait, so
/// there is nothing to tell them apart by.
fn readiness_timeout(seconds: c_double) -> Result<Option<std::time::Duration>, CoreError> {
    if seconds.is_nan() {
        return Err(CoreError::InvalidState(
            "download_clip's ready_timeout_seconds is not a number".into(),
        ));
    }
    if seconds < 0.0 || seconds.is_infinite() {
        return Ok(None);
    }
    Ok(Some(
        std::time::Duration::try_from_secs_f64(seconds).unwrap_or(std::time::Duration::MAX),
    ))
}

/// A host progress callback and the context to hand back to it.
struct ProgressCallback {
    f: unsafe extern "C" fn(u32, u32, *mut c_void),
    userdata: *mut c_void,
}

// SAFETY: as for `Completion` — the host guarantees `userdata` is usable from the
// threads this library calls back on.
unsafe impl Send for ProgressCallback {}
unsafe impl Sync for ProgressCallback {}

impl ProgressCallback {
    /// Report `done` of `total` segments written.
    ///
    /// Inside `block_in_place`, so a host that takes its time here hands the
    /// worker's other tasks to another thread instead of stalling them — the same
    /// promise the event callbacks make, kept the way an async task can keep it.
    fn report(&self, done: u32, total: u32) {
        tokio::task::block_in_place(|| unsafe { (self.f)(done, total, self.userdata) });
    }
}

/// Fetch every segment of a clip and write them, in order, to `out_path`.
async fn download_clip_to_file<L: Fn() -> bool>(
    http: &SharedHttp,
    platform: &SharedPlatform,
    playlist_url: &str,
    jwt: Option<&str>,
    out_path: &str,
    readiness: &Readiness<L>,
    progress: Option<&ProgressCallback>,
) -> Result<serde_json::Value, CoreError> {
    // Opened before anything is asked of the network: an unwritable path is worth
    // finding out about before a full-session recording is on the wire, not after.
    let mut file = tokio::fs::File::create(out_path)
        .await
        .map_err(|e| CoreError::Http(format!("cannot write {out_path}: {e}")))?;

    // Anything that fails from here leaves a partial file behind, and a truncated
    // clip that looks like a clip is worse than no clip: it opens, plays some of
    // itself, and gives no reason to suspect the download.
    match write_segments(
        http,
        platform,
        playlist_url,
        jwt,
        out_path,
        readiness,
        progress,
        &mut file,
    )
    .await
    {
        Ok(result) => Ok(result),
        Err(error) => {
            drop(file);
            let _ = tokio::fs::remove_file(out_path).await;
            Err(error)
        }
    }
}

#[allow(clippy::too_many_arguments)]
async fn write_segments<L: Fn() -> bool>(
    http: &SharedHttp,
    platform: &SharedPlatform,
    playlist_url: &str,
    jwt: Option<&str>,
    out_path: &str,
    readiness: &Readiness<L>,
    progress: Option<&ProgressCallback>,
    file: &mut tokio::fs::File,
) -> Result<serde_json::Value, CoreError> {
    use tokio::io::AsyncWriteExt;

    let requests = clip_segment_requests(http, platform, playlist_url, jwt, readiness).await?;
    let total = requests.len() as u32;

    let mut written = 0_u64;
    for (index, request) in requests.into_iter().enumerate() {
        let url = request.url.clone();
        let response = http.request(request).await?;
        check_status(&response, &format!("fetch clip segment {url}"))?;

        // Written as each segment arrives rather than accumulated: a full-session
        // recording has no bound on its size, and there is never a reason to hold
        // more than one segment of it.
        file.write_all(&response.body)
            .await
            .map_err(|e| CoreError::Http(format!("cannot write {out_path}: {e}")))?;
        written += response.body.len() as u64;

        if let Some(progress) = progress {
            progress.report(index as u32 + 1, total);
        }
    }

    file.flush()
        .await
        .map_err(|e| CoreError::Http(format!("cannot write {out_path}: {e}")))?;

    Ok(serde_json::json!({
        "path": out_path,
        "bytes": written,
        "segments": total,
    }))
}

/// Create a client. The returned handle must be released with
/// [`reactor_destroy`].
///
/// # Safety
///
/// `api_url` and `model_name` must be NUL-terminated C strings. `jwt` may be null
/// (unauthenticated local dev). `callbacks` may be null (no events); when
/// non-null it must point to a readable [`ReactorCallbacks`], which is copied
/// during the call.
#[no_mangle]
pub unsafe extern "C" fn reactor_create(
    api_url: *const c_char,
    model_name: *const c_char,
    jwt: *const c_char,
    local: c_int,
    callbacks: *const ReactorCallbacks,
) -> *mut ReactorHandle {
    create_impl(api_url, model_name, jwt, local, callbacks, None)
}

/// Like [`reactor_create`], but selects the audio device module explicitly:
/// `0` = synthetic, `1` = platform (real mic capture and speaker playout, with
/// AEC/NS/AGC), anything else = the default, which is synthetic.
///
/// Nothing opens the microphone unless `1` asks for it. See
/// [`peer::default_adm_mode`](crate::peer) for why that is the default.
///
/// # Safety
///
/// Same as [`reactor_create`].
#[no_mangle]
pub unsafe extern "C" fn reactor_create_with_adm(
    api_url: *const c_char,
    model_name: *const c_char,
    jwt: *const c_char,
    local: c_int,
    callbacks: *const ReactorCallbacks,
    adm_mode: c_int,
) -> *mut ReactorHandle {
    let adm = match adm_mode {
        0 => Some(AdmMode::Synthetic),
        1 => Some(AdmMode::Platform),
        _ => None,
    };
    create_impl(api_url, model_name, jwt, local, callbacks, adm)
}

#[allow(clippy::too_many_arguments)]
unsafe fn create_impl(
    api_url: *const c_char,
    model_name: *const c_char,
    jwt: *const c_char,
    local: c_int,
    callbacks: *const ReactorCallbacks,
    adm: Option<AdmMode>,
) -> *mut ReactorHandle {
    let api_url = CStr::from_ptr(api_url).to_string_lossy().into_owned();
    let model_name = CStr::from_ptr(model_name).to_string_lossy().into_owned();
    let jwt = if jwt.is_null() {
        None
    } else {
        Some(CStr::from_ptr(jwt).to_string_lossy().into_owned())
    };

    // Guards every host pointer below, including the media callbacks and the
    // completions of operations still in flight when the handle is destroyed.
    let gate = Arc::new(CallbackGate::new());

    let cbs: Option<CallbackSet> = if callbacks.is_null() {
        None
    } else {
        let c = &*callbacks;
        Some(CallbackSet {
            on_status: c.on_status,
            on_error: c.on_error,
            on_message: c.on_message,
            on_runtime_message: c.on_runtime_message,
            on_track: c.on_track,
            on_capabilities: c.on_capabilities,
            on_session_id: c.on_session_id,
            userdata: c.userdata,
            gate: gate.clone(),
        })
    };

    let (peer_event_tx, peer_event_rx) = futures::channel::mpsc::unbounded::<PeerEvent>();

    let http = Arc::new(ReqwestHttpClient::new(local != 0));
    let auth = Arc::new(StaticAuth(jwt));
    let platform = Arc::new(TokioPlatform);

    let mut peer_transport = match adm {
        Some(mode) => ReactorWebRtcPeerTransport::with_adm_mode(peer_event_tx, mode),
        None => ReactorWebRtcPeerTransport::new(peer_event_tx),
    };
    let mut video: Option<HostThread<VideoFrame>> = None;
    let mut audio: Option<HostThread<AudioFrame>> = None;
    if !callbacks.is_null() {
        let c = &*callbacks;
        if let Some(frame_fn) = c.on_frame {
            let userdata_usize = c.userdata as usize;
            // Capacity one, newest wins: a frame the host could not keep up with is
            // stale by the time it would be delivered, so queueing it would only add
            // latency. This is also what bounds memory when the host falls behind.
            let thread = HostThread::spawn(
                "reactor-on-frame",
                Some(1),
                Overflow::DropOldest,
                gate.clone(),
                move |frame: VideoFrame| unsafe {
                    frame_fn(
                        frame.track.as_ptr(),
                        frame.bgra.as_ptr(),
                        frame.width,
                        frame.height,
                        frame.frame_id,
                        frame.timestamp_us,
                        if frame.user_data.is_empty() {
                            std::ptr::null()
                        } else {
                            frame.user_data.as_ptr()
                        },
                        frame.user_data.len() as u32,
                        userdata_usize as *mut c_void,
                    )
                },
            );
            let tx = thread.sender();
            peer_transport =
                peer_transport.with_frame_callback(move |track, data, w, h, frame_id, ts, ud| {
                    // Runs on a libwebrtc decode thread. Copy and hand off; the one
                    // thing not to do here is wait for the host.
                    tx.send(VideoFrame {
                        track: CString::new(track).unwrap_or_default(),
                        bgra: data.to_vec(),
                        width: w,
                        height: h,
                        frame_id,
                        timestamp_us: ts,
                        user_data: ud.to_vec(),
                    });
                });
            video = Some(thread);
        }
        if let Some(audio_fn) = c.on_audio {
            let userdata_usize = c.userdata as usize;
            // Oldest wins here: for audio the queue is the jitter buffer, and a hole
            // punched in the middle of it is audible.
            let thread = HostThread::spawn(
                "reactor-on-audio",
                Some(AUDIO_QUEUE_DEPTH),
                Overflow::DropNewest,
                gate.clone(),
                move |frame: AudioFrame| unsafe {
                    audio_fn(
                        frame.track.as_ptr(),
                        frame.pcm.as_ptr(),
                        frame.pcm.len() as u32,
                        frame.sample_rate,
                        frame.channels,
                        userdata_usize as *mut c_void,
                    )
                },
            );
            let tx = thread.sender();
            peer_transport =
                peer_transport.with_audio_callback(move |track, pcm, sample_rate, channels| {
                    tx.send(AudioFrame {
                        track: CString::new(track).unwrap_or_default(),
                        pcm: pcm.to_vec(),
                        sample_rate,
                        channels,
                    });
                });
            audio = Some(thread);
        }
    }
    let peer_transport = Arc::new(peer_transport);

    let mut options = ReactorOptions::new(&api_url, &model_name);
    options.sdk_type = "ffi".to_string();
    options.local = local != 0;

    let deps = ReactorDeps {
        http,
        auth,
        platform,
        peer: peer_transport,
    };
    let reactor = Arc::new(Reactor::new(deps, options));
    let tasks: TaskSet = Arc::new(Mutex::new(Vec::new()));

    let reactor2 = reactor.clone();
    let mut peer_rx = peer_event_rx;
    spawn_tracked(&tasks, async move {
        while let Some(ev) = peer_rx.next().await {
            reactor2.handle_peer_event(ev).await;
        }
    });

    // Always present, even without event callbacks: completions are delivered here
    // too, so that a tokio worker never waits on the host either.
    let control = HostThread::spawn(
        "reactor-host-events",
        None,
        Overflow::DropNewest,
        gate.clone(),
        |job: HostJob| job(),
    );

    if let Some(cbs) = cbs {
        let mut event_rx = reactor.subscribe();
        let cbs = Arc::new(cbs);
        let control_tx = control.sender();
        spawn_tracked(&tasks, async move {
            while let Some(event) = event_rx.next().await {
                // Forwarding only — the fire itself happens on the control thread.
                let cbs = cbs.clone();
                control_tx.send(Box::new(move || dispatch_event(&cbs, event)));
            }
        });
    }

    Box::into_raw(Box::new(ReactorHandle {
        reactor,
        gate,
        tasks,
        hosts: HostThreads {
            control,
            video,
            audio,
        },
    }))
}

/// Turn one core event into the matching host callback. Runs on the control thread.
fn dispatch_event(cbs: &CallbackSet, event: ReactorEvent) {
    match event {
        ReactorEvent::StatusChanged(s) => {
            cbs.fire_str(cbs.on_status, s.as_str());
        }
        ReactorEvent::Error(e) => {
            if let Ok(v) = serde_json::to_value(&e) {
                cbs.fire_json_val(cbs.on_error, &v);
            }
        }
        ReactorEvent::Message(v) => {
            cbs.fire_json_val(cbs.on_message, &v);
        }
        ReactorEvent::RuntimeMessage(v) => {
            cbs.fire_json_val(cbs.on_runtime_message, &v);
        }
        ReactorEvent::TrackReceived { name, mid } => {
            cbs.fire_track(&name, &mid);
        }
        ReactorEvent::CapabilitiesReceived(caps) => {
            if let Ok(v) = serde_json::to_value(&caps) {
                cbs.fire_json_val(cbs.on_capabilities, &v);
            }
        }
        ReactorEvent::SessionIdChanged(sid) => {
            cbs.fire_opt_str(cbs.on_session_id, &sid);
        }
    }
}

/// Release a handle, and with it the right to call back into the host.
///
/// Returns **0** when no callback is running and none will start. That is the only
/// answer on which a binding may release whatever its callback pointers refer to —
/// a ctypes trampoline, a `cgo.Handle`, a JNI `GlobalRef`.
///
/// Returns **-1** when a callback is still executing and could not be waited for.
/// The handle is still released, but the callback pointers must be kept alive;
/// leaking them is correct, freeing them is a use-after-free. Two ways to get here:
///
/// * the host is wedged — a callback blocked on a lock this library cannot make it
///   give up, such as a ctypes trampoline waiting on a GIL that an already
///   finalising interpreter will never release. Tear down while the host runtime is
///   still running and this does not arise.
/// * this was called from inside one of the handle's own callbacks, which is
///   therefore still on the stack. There is no way to wait for the caller.
///
/// # Safety
///
/// `handle` must be null or a live handle, and must not be used again afterwards.
/// Null is accepted and returns 0.
#[no_mangle]
pub unsafe extern "C" fn reactor_destroy(handle: *mut ReactorHandle) -> c_int {
    if handle.is_null() {
        return 0;
    }
    let mut handle = Box::from_raw(handle);

    // Order matters. Close the gate and drain first: a pump blocked inside a host
    // callback has to come back out before its task can be stopped, and a task
    // aborted mid-callback would leave the count non-zero forever.
    let quiescence = handle.gate.retire();

    // Then stop the pumps and the heartbeat. Nothing here can re-enter the host,
    // because the gate now turns every attempt away.
    for task in handle.tasks.lock().unwrap().drain(..) {
        task.abort();
    }

    if quiescence == Quiescence::Incomplete {
        // A callback is still running somewhere. Dropping the host threads would
        // join them, and joining the stuck one would block here forever — which
        // would also mean the caller never learns it has to keep its pointers
        // alive, making the whole -1 path unreachable. Detach instead and leak.
        handle.hosts.abandon();
    }

    // On the quiesced path, dropping the handle closes each host queue and joins its
    // thread, so no delivery outlives this call.
    drop(handle);

    match quiescence {
        Quiescence::Complete => 0,
        Quiescence::Incomplete => -1,
    }
}

// ── Async operations ─────────────────────────────────────────────────────────

/// Run `$body` on the runtime and hand its result to the completion.
///
/// The body receives the reactor and the handle's [`TaskSet`], so an operation that
/// spawns something long-lived — `connect` and `reconnect` start a heartbeat —
/// registers it for `reactor_destroy` to stop rather than leaking it.
macro_rules! async_op {
    ($name:literal, $handle:expr, $completion:expr, $userdata:expr, $body:expr) => {{
        if $handle.is_null() {
            return;
        }
        let handle = unsafe { &*$handle };
        let reactor = handle.reactor.clone();
        let tasks = handle.tasks.clone();
        let body_tasks = handle.tasks.clone();
        let completion = Completion::new($completion, $userdata, handle.gate.clone(), $name);
        let control_tx: HostSender<HostJob> = handle.hosts.control.sender();
        spawn_tracked(&tasks, async move {
            let result: Result<Option<serde_json::Value>, CoreError> =
                $body(reactor, body_tasks).await;
            // Resolved on the control thread, not here: a completion callback that
            // blocks would otherwise park a tokio worker.
            control_tx.send(Box::new(move || completion.resolve(result)));
        });
    }};
}

/// Create (or adopt) a session and establish the WebRTC transport.
///
/// # Safety
///
/// `session_id` may be null to create a new session. `connection_id`, if
/// non-null, must point to a valid, readable `uint32_t` for the duration of this
/// call — it is read synchronously, before this function returns, so the pointee
/// need not outlive the call the way `session_id`'s string does not either.
/// `completion` is invoked exactly once, on a tokio thread, and must stay
/// callable until it fires — which may be after the awaiting caller has given up.
#[no_mangle]
pub unsafe extern "C" fn reactor_connect(
    handle: *mut ReactorHandle,
    session_id: *const c_char,
    connection_id: *const u32,
    completion: Option<unsafe extern "C" fn(c_int, *const c_char, *const c_char, *mut c_void)>,
    userdata: *mut c_void,
) {
    let sid = if session_id.is_null() {
        None
    } else {
        Some(CStr::from_ptr(session_id).to_string_lossy().into_owned())
    };
    let cid = connection_id.as_ref().copied();
    async_op!(
        "connect",
        handle,
        completion,
        userdata,
        move |r: Arc<Reactor>, tasks: TaskSet| async move {
            r.connect(ConnectOptions {
                session_id: sid,
                connection_id: cid,
                // The C ABI exposes no per-connect overrides; native hosts
                // configure both at creation time, so the client's own
                // defaults stand.
                auto_resume_tracks: None,
                max_sdp_attempts: None,
            })
            .await?;
            let r2 = r.clone();
            spawn_tracked(&tasks, async move { r2.run_heartbeat().await });
            Ok(None)
        }
    );
}

/// Disconnect gracefully. The session is preserved, so [`reactor_reconnect`] can
/// resume it.
///
/// # Safety
///
/// As [`reactor_connect`], minus `session_id`.
#[no_mangle]
pub unsafe extern "C" fn reactor_disconnect(
    handle: *mut ReactorHandle,
    completion: Option<unsafe extern "C" fn(c_int, *const c_char, *const c_char, *mut c_void)>,
    userdata: *mut c_void,
) {
    async_op!(
        "disconnect",
        handle,
        completion,
        userdata,
        |r: Arc<Reactor>, _tasks: TaskSet| async move { r.disconnect(false).await.map(|_| None) }
    );
}

/// Reconnect using the existing session — after a transient failure, or from
/// `ready` to deliberately cycle the connection. Tears down the live connection
/// first if there is one, without ending the session server-side.
///
/// Fails if there is no session to reconnect to: nothing has connected yet, or a
/// previous `reactor_disconnect` already ended it.
///
/// # Safety
///
/// As [`reactor_connect`], minus `session_id`.
#[no_mangle]
pub unsafe extern "C" fn reactor_reconnect(
    handle: *mut ReactorHandle,
    completion: Option<unsafe extern "C" fn(c_int, *const c_char, *const c_char, *mut c_void)>,
    userdata: *mut c_void,
) {
    async_op!(
        "reconnect",
        handle,
        completion,
        userdata,
        |r: Arc<Reactor>, tasks: TaskSet| async move {
            r.reconnect(None).await?;
            let r2 = r.clone();
            spawn_tracked(&tasks, async move { r2.run_heartbeat().await });
            Ok(None)
        }
    );
}

/// Activate a named sendonly track slot. Media is attached separately, with
/// [`reactor_push_video_frame`] or [`reactor_push_audio_frame`].
///
/// # Safety
///
/// `name` must be a NUL-terminated C string. `completion` as [`reactor_connect`].
#[no_mangle]
pub unsafe extern "C" fn reactor_publish_track(
    handle: *mut ReactorHandle,
    name: *const c_char,
    completion: Option<unsafe extern "C" fn(c_int, *const c_char, *const c_char, *mut c_void)>,
    userdata: *mut c_void,
) {
    let name = CStr::from_ptr(name).to_string_lossy().into_owned();
    async_op!(
        "publish_track",
        handle,
        completion,
        userdata,
        move |r: Arc<Reactor>, _tasks: TaskSet| async move {
            r.publish_track(&name).await.map(|_| None)
        }
    );
}

/// Pause receiving a named track.
///
/// # Safety
///
/// As [`reactor_publish_track`].
#[no_mangle]
pub unsafe extern "C" fn reactor_pause_track(
    handle: *mut ReactorHandle,
    name: *const c_char,
    completion: Option<unsafe extern "C" fn(c_int, *const c_char, *const c_char, *mut c_void)>,
    userdata: *mut c_void,
) {
    let name = CStr::from_ptr(name).to_string_lossy().into_owned();
    async_op!(
        "pause_track",
        handle,
        completion,
        userdata,
        move |r: Arc<Reactor>, _tasks: TaskSet| async move { r.pause_track(&name).await.map(|_| None) }
    );
}

/// Resume receiving a named track.
///
/// # Safety
///
/// As [`reactor_publish_track`].
#[no_mangle]
pub unsafe extern "C" fn reactor_resume_track(
    handle: *mut ReactorHandle,
    name: *const c_char,
    completion: Option<unsafe extern "C" fn(c_int, *const c_char, *const c_char, *mut c_void)>,
    userdata: *mut c_void,
) {
    let name = CStr::from_ptr(name).to_string_lossy().into_owned();
    async_op!(
        "resume_track",
        handle,
        completion,
        userdata,
        move |r: Arc<Reactor>, _tasks: TaskSet| async move { r.resume_track(&name).await.map(|_| None) }
    );
}

/// Read a bitrate bound off the ABI, where C has no optional integer.
///
/// `-1`, and only `-1`, means "leave this bound at the WebRTC default". Treating
/// every negative that way would make a typo — or an arithmetic slip like
/// `budget - overhead` going below zero — indistinguishable from the sentinel,
/// and it would resolve the wrong way: quietly removing a cap somebody set, and
/// reporting success for it.
///
/// Zero is a value like any other and goes through. A caller who wrote 0 asked
/// for something, and the engine's own answer says more than a reinterpretation
/// here would.
fn bitrate_bound(label: &str, v: i32) -> Result<Option<i32>, CoreError> {
    match v {
        -1 => Ok(None),
        v if v < 0 => Err(CoreError::InvalidState(format!(
            "{label} must be >= 0, or -1 to leave it at the WebRTC default (got {v})"
        ))),
        v => Ok(Some(v)),
    }
}

/// Aggregate congestion-control bitrate bounds for the connection, in bits per
/// second. Pass `-1` for any bound that should keep the WebRTC default.
///
/// This bounds what the *connection* may allocate. It does not lift the
/// per-stream video ceiling — see [`reactor_set_track_bitrate`], which does, and
/// which most callers asking for higher-quality video actually want. The two are
/// conjunctive: the lower one wins.
///
/// Callable as soon as the handle exists — including before [`reactor_connect`],
/// which is where `start_bps` has to land to do its job: the ramp it exists to
/// skip happens during connection setup. The bounds are remembered and applied
/// to the peer connection as soon as it exists, and again on every reconnect.
///
/// They belong to the handle, not to the session, so a binding that destroys and
/// recreates its handle — Python does, on a re-minted token — starts over.
///
/// # Safety
///
/// `handle` must be null or a live handle. `completion` as [`reactor_connect`].
#[no_mangle]
pub unsafe extern "C" fn reactor_set_bitrate(
    handle: *mut ReactorHandle,
    min_bps: i32,
    start_bps: i32,
    max_bps: i32,
    completion: Option<unsafe extern "C" fn(c_int, *const c_char, *const c_char, *mut c_void)>,
    userdata: *mut c_void,
) {
    async_op!(
        "set_bitrate",
        handle,
        completion,
        userdata,
        move |r: Arc<Reactor>, _tasks: TaskSet| async move {
            let min = bitrate_bound("min_bps", min_bps)?;
            let start = bitrate_bound("start_bps", start_bps)?;
            let max = bitrate_bound("max_bps", max_bps)?;
            r.set_bitrate(min, start, max).await.map(|_| None)
        }
    );
}

/// Per-sender bitrate bounds for one track, in bits per second. Pass `-1` for
/// any bound that should keep the WebRTC default.
///
/// Raising `max_bps` here is the only way past WebRTC's resolution-keyed video
/// ceiling: with nothing set, a sender's maximum comes from the frame size alone
/// and is 2500 kbps for anything above 960x540 — so 720p, 1080p and 4K all cap
/// at 2.5 Mbps however much headroom [`reactor_set_bitrate`] granted the
/// connection.
///
/// Callable as soon as the handle exists, like [`reactor_set_bitrate`], and with
/// the same handle-scoped lifetime. Once the session has declared its tracks, a
/// name it did not declare fails the operation rather than being remembered for
/// a track that will never exist.
///
/// # Safety
///
/// `handle` must be null or a live handle. `name` must be a NUL-terminated C
/// string. `completion` as [`reactor_connect`].
#[no_mangle]
pub unsafe extern "C" fn reactor_set_track_bitrate(
    handle: *mut ReactorHandle,
    name: *const c_char,
    min_bps: i32,
    max_bps: i32,
    completion: Option<unsafe extern "C" fn(c_int, *const c_char, *const c_char, *mut c_void)>,
    userdata: *mut c_void,
) {
    let name = CStr::from_ptr(name).to_string_lossy().into_owned();
    async_op!(
        "set_track_bitrate",
        handle,
        completion,
        userdata,
        move |r: Arc<Reactor>, _tasks: TaskSet| async move {
            let min = bitrate_bound("min_bps", min_bps)?;
            let max = bitrate_bound("max_bps", max_bps)?;
            r.set_track_bitrate(&name, min, max).await.map(|_| None)
        }
    );
}

/// Request a clip covering the last `duration_seconds` of the session. On success
/// `result_json` is a clip object.
///
/// # Safety
///
/// `completion` as [`reactor_connect`].
#[no_mangle]
pub unsafe extern "C" fn reactor_request_clip(
    handle: *mut ReactorHandle,
    duration_seconds: c_double,
    completion: Option<unsafe extern "C" fn(c_int, *const c_char, *const c_char, *mut c_void)>,
    userdata: *mut c_void,
) {
    async_op!(
        "request_clip",
        handle,
        completion,
        userdata,
        move |r: Arc<Reactor>, _tasks: TaskSet| async move {
            let clip = r.request_clip(duration_seconds).await?;
            serde_json::to_value(&clip)
                .map(Some)
                .map_err(|e| CoreError::Decode(e.to_string()))
        }
    );
}

/// Request a clip covering the whole session up to now.
///
/// # Safety
///
/// `completion` as [`reactor_connect`].
#[no_mangle]
pub unsafe extern "C" fn reactor_request_recording(
    handle: *mut ReactorHandle,
    completion: Option<unsafe extern "C" fn(c_int, *const c_char, *const c_char, *mut c_void)>,
    userdata: *mut c_void,
) {
    async_op!(
        "request_recording",
        handle,
        completion,
        userdata,
        |r: Arc<Reactor>, _tasks: TaskSet| async move {
            let clip = r.request_recording().await?;
            serde_json::to_value(&clip)
                .map(Some)
                .map_err(|e| CoreError::Decode(e.to_string()))
        }
    );
}

/// Request the model's command schema. On success `result_json` is an OpenAPI
/// document.
///
/// # Safety
///
/// `completion` as [`reactor_connect`].
#[no_mangle]
pub unsafe extern "C" fn reactor_request_schema(
    handle: *mut ReactorHandle,
    completion: Option<unsafe extern "C" fn(c_int, *const c_char, *const c_char, *mut c_void)>,
    userdata: *mut c_void,
) {
    async_op!(
        "request_schema",
        handle,
        completion,
        userdata,
        |r: Arc<Reactor>, _tasks: TaskSet| async move { r.request_schema().await.map(Some) }
    );
}

/// Send an application-scoped command over the data channel and wait for its
/// correlated reply. On success `result_json` is `{type, data}`.
///
/// # Safety
///
/// `name` must be a NUL-terminated C string. `args_json` may be null (treated
/// as `{}`); otherwise it must be a NUL-terminated C string holding a JSON
/// value. `uploads_json` may be null (treated as no uploads); otherwise it must
/// be a NUL-terminated C string holding a JSON object of
/// `{param_name: {upload_id, name, mime_type, size}}`, as returned by
/// [`reactor_upload_file`] / [`reactor_upload_bytes`]. `completion` as
/// [`reactor_connect`].
#[no_mangle]
pub unsafe extern "C" fn reactor_send_command(
    handle: *mut ReactorHandle,
    name: *const c_char,
    args_json: *const c_char,
    uploads_json: *const c_char,
    completion: Option<unsafe extern "C" fn(c_int, *const c_char, *const c_char, *mut c_void)>,
    userdata: *mut c_void,
) {
    if handle.is_null() {
        return;
    }
    let name = CStr::from_ptr(name).to_string_lossy().into_owned();
    let args: serde_json::Value = if args_json.is_null() {
        serde_json::json!({})
    } else {
        let raw = CStr::from_ptr(args_json).to_string_lossy();
        match serde_json::from_str(&raw) {
            Ok(v) => v,
            Err(e) => {
                if let Some(cb) = completion {
                    if let Ok(msg) = CString::new(format!("invalid args_json: {e}")) {
                        cb(-1, std::ptr::null(), msg.as_ptr(), userdata);
                    }
                }
                return;
            }
        }
    };
    let uploads: Option<BTreeMap<String, FileRef>> = if uploads_json.is_null() {
        None
    } else {
        let raw = CStr::from_ptr(uploads_json).to_string_lossy();
        match serde_json::from_str(&raw) {
            Ok(v) => v,
            Err(e) => {
                if let Some(cb) = completion {
                    if let Ok(msg) = CString::new(format!("invalid uploads_json: {e}")) {
                        cb(-1, std::ptr::null(), msg.as_ptr(), userdata);
                    }
                }
                return;
            }
        }
    };
    async_op!(
        "send_command",
        handle,
        completion,
        userdata,
        move |r: Arc<Reactor>, _tasks: TaskSet| async move {
            r.send_command(&name, args, uploads).await
        }
    );
}

/// Upload a local file and return a reference to pass as a command argument.
///
/// # Safety
///
/// `path` must be a NUL-terminated C string naming a readable file. `completion`
/// as [`reactor_connect`].
#[no_mangle]
pub unsafe extern "C" fn reactor_upload_file(
    handle: *mut ReactorHandle,
    path: *const c_char,
    completion: Option<unsafe extern "C" fn(c_int, *const c_char, *const c_char, *mut c_void)>,
    userdata: *mut c_void,
) {
    let path = CStr::from_ptr(path).to_string_lossy().into_owned();
    async_op!(
        "upload_file",
        handle,
        completion,
        userdata,
        move |r: Arc<Reactor>, _tasks: TaskSet| async move {
            let p = std::path::Path::new(&path);
            let name = p
                .file_name()
                .and_then(|s| s.to_str())
                .unwrap_or("upload")
                .to_string();
            let mime_type = mime_guess::from_path(p).first_or_octet_stream().to_string();
            let bytes = tokio::fs::read(p)
                .await
                .map_err(|e| CoreError::Http(e.to_string()))?;
            let file_ref = r.upload_file(&name, &mime_type, bytes).await?;
            serde_json::to_value(&file_ref)
                .map(Some)
                .map_err(|e| CoreError::Decode(e.to_string()))
        }
    );
}

/// Copies `len` bytes out of `data` into an owned `Vec`.
///
/// `slice::from_raw_parts` requires a non-null, aligned pointer even for a
/// zero-length slice, so a null `data` — a caller's spelling of "no bytes" —
/// must short-circuit before reaching it rather than pass straight through.
///
/// # Safety
///
/// As [`reactor_upload_bytes`]: `data` must be null, or point to at least
/// `len` readable bytes.
unsafe fn copy_bytes(data: *const u8, len: usize) -> Vec<u8> {
    if len == 0 {
        Vec::new()
    } else {
        std::slice::from_raw_parts(data, len).to_vec()
    }
}

/// Upload a file already in memory and return a reference to pass as a command
/// argument. Same result shape as [`reactor_upload_file`]; use this when the
/// caller already has the bytes (a `bytes` object, a file-like object, a
/// `Blob`) rather than a filesystem path.
///
/// # Safety
///
/// `data` must point to at least `len` readable bytes, borrowed for the call
/// only. `name` and `mime_type` must be NUL-terminated C strings. `completion`
/// as [`reactor_connect`].
#[no_mangle]
pub unsafe extern "C" fn reactor_upload_bytes(
    handle: *mut ReactorHandle,
    data: *const u8,
    len: usize,
    name: *const c_char,
    mime_type: *const c_char,
    completion: Option<unsafe extern "C" fn(c_int, *const c_char, *const c_char, *mut c_void)>,
    userdata: *mut c_void,
) {
    if handle.is_null() || (data.is_null() && len > 0) {
        return;
    }
    let bytes = copy_bytes(data, len);
    let name = CStr::from_ptr(name).to_string_lossy().into_owned();
    let mime_type = CStr::from_ptr(mime_type).to_string_lossy().into_owned();
    async_op!(
        "upload_bytes",
        handle,
        completion,
        userdata,
        move |r: Arc<Reactor>, _tasks: TaskSet| async move {
            let file_ref = r.upload_file(&name, &mime_type, bytes).await?;
            serde_json::to_value(&file_ref)
                .map(Some)
                .map_err(|e| CoreError::Decode(e.to_string()))
        }
    );
}

// ── Synchronous operations ────────────────────────────────────────────────────

/// Deactivate a sendonly track (sync — this never touches the network, only a
/// local status check and a fire-and-forget notification, so there is nothing
/// here for a completion callback to wait on).
///
/// Returns null on success. On failure, returns a heap JSON error object — the
/// same `{code, message, recoverable, status, operation, retry_after_ms}` shape
/// every completion reports — which the caller must release with
/// [`reactor_free_string`].
///
/// # Safety
///
/// `handle` must be null or a live handle. `name` must be a NUL-terminated C
/// string.
#[no_mangle]
pub unsafe extern "C" fn reactor_unpublish_track(
    handle: *mut ReactorHandle,
    name: *const c_char,
) -> *mut c_char {
    if handle.is_null() {
        return error_details_ptr(
            &CoreError::InvalidState("no active handle".to_string()),
            "unpublish_track",
        );
    }
    let name = CStr::from_ptr(name).to_string_lossy();
    match (*handle).reactor.unpublish_track(&name) {
        Ok(_) => std::ptr::null_mut(),
        Err(e) => error_details_ptr(&e, "unpublish_track"),
    }
}

/// Serialise a `CoreError` into the heap JSON payload every completion reports,
/// for a synchronous caller with no completion callback to hand it to instead.
fn error_details_ptr(err: &CoreError, operation: &str) -> *mut c_char {
    let details = err.details(Some(operation));
    let json =
        serde_json::to_string(&details).unwrap_or_else(|_| fallback_error_json(&details.message));
    CString::new(json)
        .map(|cs| cs.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Current status: `"disconnected"`, `"connecting"`, `"waiting"` or `"ready"`.
///
/// The returned pointer is a static string literal — never null, valid for the
/// life of the process, and must not be freed.
///
/// # Safety
///
/// `handle` must be null (reports `"disconnected"`) or a live handle.
#[no_mangle]
pub unsafe extern "C" fn reactor_status(handle: *mut ReactorHandle) -> *const c_char {
    let s = if handle.is_null() {
        "disconnected"
    } else {
        (*handle).reactor.status().as_str()
    };
    match s {
        "connecting" => c"connecting".as_ptr(),
        "waiting" => c"waiting".as_ptr(),
        "ready" => c"ready".as_ptr(),
        _ => c"disconnected".as_ptr(),
    }
}

/// Current session id, or null when no session is active.
///
/// Unlike [`reactor_status`], the result is heap-allocated and owned by the
/// caller, who must release it with [`reactor_free_string`].
///
/// # Safety
///
/// `handle` must be null (returns null) or a live handle.
#[no_mangle]
pub unsafe extern "C" fn reactor_session_id(handle: *mut ReactorHandle) -> *mut c_char {
    if handle.is_null() {
        return std::ptr::null_mut();
    }
    match (*handle).reactor.session_id() {
        Some(id) => CString::new(id)
            .map(|cs| cs.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        None => std::ptr::null_mut(),
    }
}

/// The tracks the runtime declared, as a JSON array of
/// `{"name","kind","direction"}` — the same entries the capabilities event
/// carries, readable at any time.
///
/// Returns `"[]"` before the session is accepted and after it is torn down, so a
/// binding can tell "no tracks yet" from a track it does not recognise without
/// having to have caught the event. Owned by the caller; release it with
/// [`reactor_free_string`].
///
/// # Safety
///
/// `handle` must be null (returns null) or a live handle.
#[no_mangle]
pub unsafe extern "C" fn reactor_tracks(handle: *mut ReactorHandle) -> *mut c_char {
    if handle.is_null() {
        return std::ptr::null_mut();
    }
    let tracks = (*handle).reactor.tracks();
    match serde_json::to_string(&tracks) {
        Ok(json) => CString::new(json)
            .map(|cs| cs.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Err(_) => std::ptr::null_mut(),
    }
}

/// The names of the tracks currently paused, as a JSON array of strings.
///
/// Recvonly tracks are resumed automatically once connected, so this is empty on
/// a healthy session until the host pauses something. Owned by the caller;
/// release it with [`reactor_free_string`].
///
/// # Safety
///
/// `handle` must be null (returns null) or a live handle.
#[no_mangle]
pub unsafe extern "C" fn reactor_paused_tracks(handle: *mut ReactorHandle) -> *mut c_char {
    if handle.is_null() {
        return std::ptr::null_mut();
    }
    // Sorted, so the same set is always the same string: a host diffing this
    // between polls should not see a change that HashSet ordering invented.
    let mut names: Vec<String> = (*handle).reactor.paused_tracks().into_iter().collect();
    names.sort();
    match serde_json::to_string(&names) {
        Ok(json) => CString::new(json)
            .map(|cs| cs.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Release a string returned by [`reactor_session_id`], [`reactor_tracks`] or
/// [`reactor_paused_tracks`].
///
/// # Safety
///
/// `s` must be null (no-op) or a pointer returned by one of those functions that
/// has not already been freed. Passing any other pointer — including the static
/// one from [`reactor_status`] — is undefined behaviour.
#[no_mangle]
pub unsafe extern "C" fn reactor_free_string(s: *mut c_char) {
    if !s.is_null() {
        drop(CString::from_raw(s));
    }
}

/// Push a raw BGRA frame into a named sendonly video track. No-op when the track
/// has no attached video source.
///
/// # Safety
///
/// `track_name` must be a NUL-terminated C string, and `data` must point to at
/// least `width * height * 4` readable bytes — the length is **not** passed, so a
/// short buffer is read out of bounds. Both are borrowed for the call only.
#[no_mangle]
pub unsafe extern "C" fn reactor_push_video_frame(
    handle: *mut ReactorHandle,
    track_name: *const c_char,
    data: *const u8,
    width: u32,
    height: u32,
) {
    if handle.is_null() || track_name.is_null() || data.is_null() {
        return;
    }
    let name = CStr::from_ptr(track_name).to_string_lossy();
    let n = (width * height * 4) as usize;
    let slice = std::slice::from_raw_parts(data, n);
    (*handle)
        .reactor
        .push_video_frame(&name, slice, width, height);
}

/// Push a BGRA frame tagged with `user_data`, which reaches the far end as the
/// frame's metadata.
///
/// `user_data` may be null with `user_data_len` 0, which sends the frame untagged
/// and is the same as [`reactor_push_video_frame`].
///
/// # Safety
///
/// `handle` must come from `reactor_new`, `track_name` must be a NUL-terminated C
/// string, `data` must point to `width * height * 4` readable bytes, and
/// `user_data` must point to `user_data_len` readable bytes. All are borrowed for
/// the duration of the call.
#[no_mangle]
pub unsafe extern "C" fn reactor_push_video_frame_with_metadata(
    handle: *mut ReactorHandle,
    track_name: *const c_char,
    data: *const u8,
    width: u32,
    height: u32,
    user_data: *const u8,
    user_data_len: u32,
) {
    if handle.is_null() || track_name.is_null() || data.is_null() {
        return;
    }
    let name = CStr::from_ptr(track_name).to_string_lossy();
    let n = (width * height * 4) as usize;
    let slice = std::slice::from_raw_parts(data, n);
    let tag: &[u8] = if user_data.is_null() || user_data_len == 0 {
        &[]
    } else {
        std::slice::from_raw_parts(user_data, user_data_len as usize)
    };
    (*handle)
        .reactor
        .push_video_frame_with_metadata(&name, slice, width, height, tag);
}

/// The engine's monotonic clock in microseconds — the epoch
/// [`reactor_push_video_frame_with_metadata_at`] reads its capture time in.
///
/// Read it once per unit of produced media and stamp every track with that one
/// value: tracks are synchronised by sharing a capture time, not by reaching the
/// encoder at the same moment. It is a wall clock's opposite — no handle, no
/// state, and unrelated to `time(2)`'s epoch, so a UNIX timestamp is not a
/// substitute for it.
#[no_mangle]
pub extern "C" fn reactor_time_micros() -> i64 {
    reactor_webrtc::time_micros()
}

/// Push a BGRA frame stamped with the caller's own capture time in microseconds,
/// optionally tagged with `user_data`.
///
/// Same as [`reactor_push_video_frame_with_metadata`] but for the timestamp the
/// frame carries: without one, each push is stamped as it happens, so several
/// tracks capturing one moment arrive stamped microseconds apart. Pass the same
/// `capture_time_us` for every track of one capture and the far end reads them as
/// the one moment they are — read from [`reactor_time_micros`], the engine's
/// clock rather than the system's.
///
/// `user_data` may be null with `user_data_len` 0 — stamping and tagging are
/// independent choices.
///
/// # Safety
///
/// `handle` must come from `reactor_new`, `track_name` must be a NUL-terminated C
/// string, `data` must point to `width * height * 4` readable bytes, and
/// `user_data` must point to `user_data_len` readable bytes. All are borrowed for
/// the duration of the call.
#[no_mangle]
pub unsafe extern "C" fn reactor_push_video_frame_with_metadata_at(
    handle: *mut ReactorHandle,
    track_name: *const c_char,
    data: *const u8,
    width: u32,
    height: u32,
    user_data: *const u8,
    user_data_len: u32,
    capture_time_us: i64,
) {
    if handle.is_null() || track_name.is_null() || data.is_null() {
        return;
    }
    let name = CStr::from_ptr(track_name).to_string_lossy();
    let n = (width * height * 4) as usize;
    let slice = std::slice::from_raw_parts(data, n);
    let tag: &[u8] = if user_data.is_null() || user_data_len == 0 {
        &[]
    } else {
        std::slice::from_raw_parts(user_data, user_data_len as usize)
    };
    (*handle).reactor.push_video_frame_with_metadata_at(
        &name,
        slice,
        width,
        height,
        tag,
        capture_time_us,
    );
}

/// Push interleaved i16 PCM into a named sendonly audio track. No-op when the
/// track has no attached audio source.
///
/// `sample_rate` is currently ignored and the source is driven at 48 kHz mono
/// regardless of `num_channels`, which only sizes the input slice.
///
/// # Safety
///
/// `track_name` must be a NUL-terminated C string, and `data` must point to at
/// least `samples_per_channel * num_channels` readable `i16`s. The length is not
/// passed; a short buffer is read out of bounds.
#[no_mangle]
pub unsafe extern "C" fn reactor_push_audio_frame(
    handle: *mut ReactorHandle,
    track_name: *const c_char,
    data: *const i16,
    samples_per_channel: u32,
    _sample_rate: u32,
    num_channels: u32,
) {
    if handle.is_null() || track_name.is_null() || data.is_null() {
        return;
    }
    let name = CStr::from_ptr(track_name).to_string_lossy();
    let n = (samples_per_channel * num_channels) as usize;
    let slice = std::slice::from_raw_parts(data, n);
    (*handle).reactor.push_audio_frame(&name, slice);
}

// ── Tests ────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};

    /// Every entry point documents null as accepted-and-handled, and bindings lean
    /// on it: a Python `Reactor` reports `"disconnected"` from a client that never
    /// connected by calling straight through with a null handle. These assert the
    /// guards rather than the behaviour behind them, so they need no session, no
    /// network and no libwebrtc device.
    #[test]
    fn null_handle_is_accepted_by_every_sync_entry_point() {
        let name = CString::new("video").unwrap();

        unsafe {
            let status = CStr::from_ptr(reactor_status(std::ptr::null_mut()));
            assert_eq!(status.to_str().unwrap(), "disconnected");

            assert!(reactor_session_id(std::ptr::null_mut()).is_null());

            assert!(reactor_tracks(std::ptr::null_mut()).is_null());
            assert!(reactor_paused_tracks(std::ptr::null_mut()).is_null());

            let err_ptr = reactor_unpublish_track(std::ptr::null_mut(), name.as_ptr());
            assert!(!err_ptr.is_null());
            let json: serde_json::Value =
                serde_json::from_str(CStr::from_ptr(err_ptr).to_str().unwrap()).unwrap();
            assert_eq!(json["code"], reactor_core::error::codes::INVALID_STATE);
            reactor_free_string(err_ptr);

            // Nothing to quiesce, so destroying nothing reports success.
            assert_eq!(reactor_destroy(std::ptr::null_mut()), 0);

            // A no-op rather than a return value: the assertion is that it does not
            // abort.
            reactor_free_string(std::ptr::null_mut());
        }
    }

    /// A host awaiting a completion is stuck forever if one never fires, so the
    /// last-resort payload has to be something a binding can actually parse —
    /// this is the path taken when serialising the real error has already failed.
    #[test]
    fn the_fallback_error_payload_is_a_well_formed_object() {
        let json: serde_json::Value =
            serde_json::from_str(&fallback_error_json("something went wrong")).unwrap();
        assert_eq!(json["code"], "INTERNAL_ERROR");
        assert_eq!(json["message"], "something went wrong");
        assert_eq!(json["recoverable"], false);
    }

    /// The keys a binding reads, produced by the path that actually produces them.
    #[test]
    fn a_failed_operation_reports_a_code_and_the_call_that_failed() {
        let error = CoreError::Status {
            status: 401,
            context: "POST /sessions".into(),
            body: String::new(),
            retry_after_ms: None,
        };
        let json = serde_json::to_value(error.details(Some("connect"))).unwrap();
        assert_eq!(json["code"], "UNAUTHORIZED");
        assert_eq!(json["operation"], "connect");
        assert_eq!(json["status"], 401);
        assert_eq!(json["recoverable"], false);
    }

    /// The clock a caller reads capture times from: no handle, and forward-moving,
    /// which is the whole contract — a stamp is only meaningful against the value
    /// the next frame gets.
    #[test]
    fn the_engine_clock_is_readable_without_a_handle_and_advances() {
        let first = reactor_time_micros();
        assert!(first > 0);
        assert!(reactor_time_micros() >= first);
    }

    /// What a completion was told, for the calls that take no handle: they answer
    /// on a thread this library owns, so a test has to wait rather than assume.
    struct Recorded {
        calls: Mutex<Vec<(bool, String)>>,
        fired: std::sync::Condvar,
    }

    impl Recorded {
        fn new() -> Self {
            Self {
                calls: Mutex::new(Vec::new()),
                fired: std::sync::Condvar::new(),
            }
        }

        /// The first completion, or a failed test. A completion that never fires is
        /// the failure mode worth catching here: a binding awaiting it would hang,
        /// which is what these calls exist to make impossible.
        fn wait(&self) -> (bool, String) {
            let mut calls = self.calls.lock().unwrap();
            while calls.is_empty() {
                let (guard, timeout) = self
                    .fired
                    .wait_timeout(calls, std::time::Duration::from_secs(5))
                    .unwrap();
                calls = guard;
                assert!(!timeout.timed_out(), "the completion never fired");
            }
            calls[0].clone()
        }

        fn count(&self) -> usize {
            self.calls.lock().unwrap().len()
        }
    }

    unsafe extern "C" fn record_completion(
        ok: c_int,
        result: *const c_char,
        error: *const c_char,
        userdata: *mut c_void,
    ) {
        let recorded = &*(userdata as *const Recorded);
        let payload = if ok == 1 { result } else { error };
        let text = if payload.is_null() {
            String::new()
        } else {
            CStr::from_ptr(payload).to_string_lossy().into_owned()
        };
        recorded.calls.lock().unwrap().push((ok == 1, text));
        recorded.fired.notify_all();
    }

    /// The mirror image of the null-handle rule above, and deliberately so. A call
    /// with no handle has no earlier guard a binding could have applied, so the only
    /// alternatives are an error or a future nothing resolves.
    #[test]
    fn a_handle_less_call_reports_a_missing_argument_rather_than_going_quiet() {
        let recorded = Recorded::new();
        let key = CString::new("key").unwrap();

        unsafe {
            reactor_fetch_jwt(
                std::ptr::null(),
                key.as_ptr(),
                std::ptr::null(),
                0,
                Some(record_completion),
                &recorded as *const Recorded as *mut c_void,
            );
        }

        let (ok, payload) = recorded.wait();
        assert!(!ok);
        let json: serde_json::Value = serde_json::from_str(&payload).unwrap();
        assert_eq!(json["code"], reactor_core::error::codes::INVALID_STATE);
        assert_eq!(json["operation"], "fetch_jwt");
        assert_eq!(recorded.count(), 1, "a completion fires exactly once");
    }

    /// A binding builds this JSON from its own caller's arguments, so a key can be
    /// wrong — and scoping is the one place where ignoring an unknown key hands back
    /// a *more* powerful token than was asked for. It has to fail, and it has to
    /// fail before the key is ever sent anywhere.
    #[test]
    fn misspelt_token_options_are_refused_before_any_request_is_made() {
        let recorded = Recorded::new();
        let url = CString::new("https://api.reactor.inc").unwrap();
        let key = CString::new("key").unwrap();
        let options = CString::new(r#"{"model":["reactor/helios"]}"#).unwrap();

        unsafe {
            reactor_fetch_jwt(
                url.as_ptr(),
                key.as_ptr(),
                options.as_ptr(),
                0,
                Some(record_completion),
                &recorded as *const Recorded as *mut c_void,
            );
        }

        let (ok, payload) = recorded.wait();
        assert!(!ok);
        let json: serde_json::Value = serde_json::from_str(&payload).unwrap();
        assert_eq!(json["code"], reactor_core::error::codes::INVALID_STATE);
        assert!(
            json["message"].as_str().unwrap().contains("model"),
            "the message must name the field that was wrong: {payload}"
        );
    }

    /// Same rule as fetch_jwt's: no handle to have been validated first, so a
    /// missing argument has to arrive as an error rather than as silence.
    #[test]
    fn a_download_with_nowhere_to_write_reports_it_rather_than_going_quiet() {
        let recorded = Recorded::new();
        let url = CString::new("https://api.reactor.inc/hls/clip.m3u8").unwrap();

        unsafe {
            reactor_download_clip(
                std::ptr::null_mut(),
                url.as_ptr(),
                std::ptr::null(),
                std::ptr::null(),
                0.0,
                -1.0,
                0,
                None,
                Some(record_completion),
                &recorded as *const Recorded as *mut c_void,
            );
        }

        let (ok, payload) = recorded.wait();
        assert!(!ok);
        let json: serde_json::Value = serde_json::from_str(&payload).unwrap();
        assert_eq!(json["code"], reactor_core::error::codes::INVALID_STATE);
        assert_eq!(json["operation"], "download_clip");
        assert_eq!(recorded.count(), 1);
    }

    /// The output file is opened before anything is asked of the network, so a path
    /// that cannot be written fails now rather than after a full-session recording
    /// has been fetched. The unroutable host in the URL is the assertion: reaching
    /// it would hang, so a prompt failure proves nothing was attempted.
    #[test]
    fn an_unwritable_path_fails_before_any_request_is_made() {
        let recorded = Recorded::new();
        let url = CString::new("http://127.0.0.1:1/hls/clip.m3u8").unwrap();
        let out = CString::new("/nonexistent-directory/clip.mp4").unwrap();

        unsafe {
            reactor_download_clip(
                std::ptr::null_mut(),
                url.as_ptr(),
                std::ptr::null(),
                out.as_ptr(),
                0.0,
                -1.0,
                0,
                None,
                Some(record_completion),
                &recorded as *const Recorded as *mut c_void,
            );
        }

        let (ok, payload) = recorded.wait();
        assert!(!ok);
        let json: serde_json::Value = serde_json::from_str(&payload).unwrap();
        assert!(
            json["message"]
                .as_str()
                .unwrap()
                .contains("/nonexistent-directory/clip.mp4"),
            "the message must name the path that could not be written: {payload}"
        );
    }

    /// A partial clip is worse than none: it opens, plays some of itself, and gives
    /// no reason to suspect the download. So a failed download leaves nothing.
    #[test]
    fn a_failed_download_leaves_no_partial_file_behind() {
        let recorded = Recorded::new();
        let out_path =
            std::env::temp_dir().join(format!("reactor-clip-test-{}.mp4", std::process::id()));
        let _ = std::fs::remove_file(&out_path);

        // Port 1 on loopback refuses immediately, so the playlist fetch fails after
        // the file has been created — which is the window this is about.
        let url = CString::new("http://127.0.0.1:1/hls/clip.m3u8").unwrap();
        let out = CString::new(out_path.to_str().unwrap()).unwrap();

        unsafe {
            reactor_download_clip(
                std::ptr::null_mut(),
                url.as_ptr(),
                std::ptr::null(),
                out.as_ptr(),
                0.0,
                0.0,
                0,
                None,
                Some(record_completion),
                &recorded as *const Recorded as *mut c_void,
            );
        }

        let (ok, _payload) = recorded.wait();
        assert!(!ok, "a refused connection is a failure");
        assert!(
            !out_path.exists(),
            "a failed download must not leave {out_path:?} behind"
        );
    }

    /// A C caller has a `double` and no type system to stop them handing over one
    /// that is not a duration. Interpreting a NaN as one panics, and a panic inside
    /// the detached task takes the completion with it — the binding waits for a
    /// callback that can never come, which is exactly the hang these entry points
    /// promise not to produce.
    #[test]
    fn a_timeout_that_is_not_a_number_is_refused_rather_than_left_to_panic() {
        let recorded = Recorded::new();
        let url = CString::new("http://127.0.0.1:1/hls/clip.m3u8").unwrap();
        let out_path =
            std::env::temp_dir().join(format!("reactor-clip-nan-{}.mp4", std::process::id()));
        let _ = std::fs::remove_file(&out_path);
        let out = CString::new(out_path.to_str().unwrap()).unwrap();

        unsafe {
            reactor_download_clip(
                std::ptr::null_mut(),
                url.as_ptr(),
                std::ptr::null(),
                out.as_ptr(),
                0.0,
                f64::NAN,
                0,
                None,
                Some(record_completion),
                &recorded as *const Recorded as *mut c_void,
            );
        }

        let (ok, payload) = recorded.wait();
        assert!(!ok);
        let json: serde_json::Value = serde_json::from_str(&payload).unwrap();
        assert_eq!(json["code"], reactor_core::error::codes::INVALID_STATE);
        assert_eq!(json["operation"], "download_clip");
        assert_eq!(recorded.count(), 1, "a completion fires exactly once");
        assert!(
            !out_path.exists(),
            "a refused argument must not have created {out_path:?}"
        );
    }

    /// The rest of the range, which has no error in it: an infinity is how a caller
    /// spells "no bound" when negative zero is awkward to produce, and a value past
    /// what a `Duration` holds is a wait nothing outlives either way. Both used to
    /// reach `Duration::from_secs_f64`, which panics on the first and overflows on
    /// the second.
    #[test]
    fn every_other_timeout_a_double_can_hold_maps_to_a_bound_or_to_none() {
        assert_eq!(readiness_timeout(-1.0).unwrap(), None);
        assert_eq!(readiness_timeout(f64::INFINITY).unwrap(), None);
        assert_eq!(readiness_timeout(f64::NEG_INFINITY).unwrap(), None);
        assert_eq!(
            readiness_timeout(5.0).unwrap(),
            Some(std::time::Duration::from_secs(5))
        );
        assert_eq!(
            readiness_timeout(0.0).unwrap(),
            Some(std::time::Duration::ZERO)
        );
        assert_eq!(
            readiness_timeout(f64::MAX).unwrap(),
            Some(std::time::Duration::MAX)
        );
        assert!(readiness_timeout(f64::NAN).is_err());
    }

    /// The version exists twice — here as a constant, and in the header as the
    /// REACTOR_ABI_VERSION macro a binding compiles against. It has to: the whole
    /// check is "what the header said" against "what the library says", and one
    /// number cannot compare against itself. So this is the test that keeps the two
    /// copies one number, which is the same drift the guard exists to catch.
    #[test]
    fn the_headers_macro_matches_the_version_this_library_reports() {
        const MARKER: &str = "#define REACTOR_ABI_VERSION ";
        let header = include_str!("../include/reactor_ffi.h");

        let occurrences = header.matches(MARKER).count();
        assert_eq!(
            occurrences, 1,
            "reactor_ffi.h must define REACTOR_ABI_VERSION exactly once — \
             found {occurrences}"
        );

        let declared: u32 = header
            .split(MARKER)
            .nth(1)
            .and_then(|rest| rest.lines().next())
            .expect("unreachable: the marker was just counted")
            .trim()
            .parse()
            .expect("REACTOR_ABI_VERSION must be an integer");

        assert_eq!(
            declared, ABI_VERSION,
            "reactor_ffi.h defines REACTOR_ABI_VERSION as {declared}, lib.rs \
             reports {ABI_VERSION}. Bump both, or neither."
        );
        assert_eq!(reactor_abi_version(), ABI_VERSION);
    }

    #[test]
    fn null_handle_is_accepted_by_the_media_push_path() {
        let name = CString::new("video").unwrap();
        let pixels = [0u8; 4];
        let pcm = [0i16; 2];
        let tag = [1u8, 2, 3];

        unsafe {
            reactor_push_video_frame(std::ptr::null_mut(), name.as_ptr(), pixels.as_ptr(), 1, 1);
            reactor_push_video_frame_with_metadata(
                std::ptr::null_mut(),
                name.as_ptr(),
                pixels.as_ptr(),
                1,
                1,
                tag.as_ptr(),
                tag.len() as u32,
            );
            reactor_push_video_frame_with_metadata_at(
                std::ptr::null_mut(),
                name.as_ptr(),
                pixels.as_ptr(),
                1,
                1,
                tag.as_ptr(),
                tag.len() as u32,
                1_700_000_000_000_000,
            );
            // Stamping without tagging is a valid combination, null tag included.
            reactor_push_video_frame_with_metadata_at(
                std::ptr::null_mut(),
                name.as_ptr(),
                pixels.as_ptr(),
                1,
                1,
                std::ptr::null(),
                0,
                0,
            );
            reactor_push_audio_frame(
                std::ptr::null_mut(),
                name.as_ptr(),
                pcm.as_ptr(),
                2,
                48_000,
                1,
            );
        }
    }

    /// A null `track_name` or `data` must be caught before the pointer is read.
    /// Worth pinning separately from the handle guard: these are the arguments a
    /// binding derives from user input, so they are the ones that go null in
    /// practice.
    #[test]
    fn null_media_arguments_are_rejected_before_any_read() {
        let name = CString::new("video").unwrap();
        let pixels = [0u8; 4];

        unsafe {
            reactor_push_video_frame(
                std::ptr::null_mut(),
                std::ptr::null(),
                pixels.as_ptr(),
                1,
                1,
            );
            reactor_push_video_frame(
                std::ptr::null_mut(),
                name.as_ptr(),
                std::ptr::null(),
                640,
                480,
            );
            reactor_push_audio_frame(
                std::ptr::null_mut(),
                name.as_ptr(),
                std::ptr::null(),
                480,
                48_000,
                1,
            );
        }
    }

    static COMPLETIONS: AtomicUsize = AtomicUsize::new(0);

    unsafe extern "C" fn count_completion(
        _ok: c_int,
        _result: *const c_char,
        _error: *const c_char,
        _userdata: *mut c_void,
    ) {
        COMPLETIONS.fetch_add(1, Ordering::SeqCst);
    }

    /// The async entry points return early on a null handle *without* invoking the
    /// completion. That asymmetry is load-bearing and easy to break: a binding that
    /// awaits a future resolved only by the completion would hang forever, so the
    /// contract is "null handle means the call never happened".
    ///
    /// Note this is the opposite of what a host usually wants; it is asserted here
    /// because it is the current contract, not because it is the better one.
    #[test]
    fn async_entry_points_skip_the_completion_on_a_null_handle() {
        COMPLETIONS.store(0, Ordering::SeqCst);
        let name = CString::new("video").unwrap();

        unsafe {
            reactor_connect(
                std::ptr::null_mut(),
                std::ptr::null(),
                std::ptr::null(),
                Some(count_completion),
                std::ptr::null_mut(),
            );
            reactor_disconnect(
                std::ptr::null_mut(),
                Some(count_completion),
                std::ptr::null_mut(),
            );
            reactor_reconnect(
                std::ptr::null_mut(),
                Some(count_completion),
                std::ptr::null_mut(),
            );
            reactor_publish_track(
                std::ptr::null_mut(),
                name.as_ptr(),
                Some(count_completion),
                std::ptr::null_mut(),
            );
            reactor_request_recording(
                std::ptr::null_mut(),
                Some(count_completion),
                std::ptr::null_mut(),
            );
            // Malformed JSON too: the null-handle check must win before the
            // args_json parse failure gets a chance to invoke completion.
            let bad_args = CString::new("not json").unwrap();
            reactor_send_command(
                std::ptr::null_mut(),
                name.as_ptr(),
                bad_args.as_ptr(),
                std::ptr::null(),
                Some(count_completion),
                std::ptr::null_mut(),
            );
            let upload_name = CString::new("f.bin").unwrap();
            let mime_type = CString::new("application/octet-stream").unwrap();
            reactor_upload_bytes(
                std::ptr::null_mut(),
                std::ptr::null(),
                0,
                upload_name.as_ptr(),
                mime_type.as_ptr(),
                Some(count_completion),
                std::ptr::null_mut(),
            );
        }

        assert_eq!(COMPLETIONS.load(Ordering::SeqCst), 0);
    }

    /// `reactor_status` hands out static literals, so the pointer stays valid after
    /// any number of further calls and must never be freed. Pinning that keeps a
    /// future refactor from returning heap strings without also changing the header
    /// and every binding that trusts this.
    #[test]
    fn status_pointer_is_static_and_stable() {
        unsafe {
            let first = reactor_status(std::ptr::null_mut());
            let second = reactor_status(std::ptr::null_mut());
            assert_eq!(first, second, "expected the same static pointer");
            assert_eq!(CStr::from_ptr(first).to_str().unwrap(), "disconnected");
        }
    }

    /// `slice::from_raw_parts` requires a non-null pointer even at length 0 — a
    /// null `data` (a caller's spelling of "no bytes") must short-circuit before
    /// ever reaching it.
    /// The ABI has no optional integer, so -1 carries "leave this at the WebRTC
    /// default". Everything here is about keeping that sentinel from swallowing
    /// values that are not it.
    #[test]
    fn only_minus_one_reads_as_an_unset_bitrate_bound() {
        assert_eq!(bitrate_bound("max_bps", -1).unwrap(), None);

        // Zero is a value, not a second spelling of "unset".
        assert_eq!(bitrate_bound("max_bps", 0).unwrap(), Some(0));
        assert_eq!(
            bitrate_bound("max_bps", 8_000_000).unwrap(),
            Some(8_000_000)
        );
    }

    /// The failure this exists to prevent is silent: read as the sentinel, a
    /// typo'd negative removes a cap the caller had set and the call reports
    /// success. So the refusal is the point, and it has to name the parameter —
    /// three bounds cross this boundary and "invalid bitrate" would not say
    /// which.
    #[test]
    fn a_negative_bitrate_bound_is_refused_by_name() {
        let err = bitrate_bound("max_bps", -8_000_000).expect_err("must be refused");
        let message = err.to_string();
        assert!(
            message.contains("max_bps"),
            "message lost the name: {message}"
        );
        assert!(
            message.contains("-8000000"),
            "message lost the value: {message}"
        );

        assert!(bitrate_bound("min_bps", -2).is_err());
        assert!(bitrate_bound("start_bps", i32::MIN).is_err());
    }

    #[test]
    fn copy_bytes_of_a_null_pointer_at_zero_length_is_empty() {
        unsafe {
            assert_eq!(copy_bytes(std::ptr::null(), 0), Vec::<u8>::new());
        }
    }

    #[test]
    fn copy_bytes_copies_the_given_length() {
        let data = [1u8, 2, 3, 4];
        unsafe {
            assert_eq!(copy_bytes(data.as_ptr(), data.len()), vec![1, 2, 3, 4]);
        }
    }

    // `reactor_connect`'s `connection_id` follows the same nullable-pointer
    // convention as every other optional argument in this file, but it is the
    // first `Option<u32>` (everything else optional is a string or a callback) —
    // these pin that `.as_ref().copied()` reads it the same way a binding would
    // have to write it: a null pointer for `None`, a pointer to a live `u32` for
    // `Some`. `establish_transport`'s own handling of the resulting value is
    // reactor-core's to test; there is no mock coordinator in this crate or that
    // one to drive a real `reactor_connect` call end to end (session_id's
    // adoption path is exactly as untested past this boundary).
    #[test]
    fn a_null_connection_id_pointer_is_none() {
        let ptr: *const u32 = std::ptr::null();
        assert_eq!(unsafe { ptr.as_ref().copied() }, None);
    }

    #[test]
    fn a_connection_id_pointer_is_read_before_the_call_returns() {
        let value: u32 = 42;
        let ptr: *const u32 = &value;
        assert_eq!(unsafe { ptr.as_ref().copied() }, Some(42));
    }
}
