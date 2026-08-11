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

use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_double, c_int, c_void};
use std::sync::{Arc, Mutex};

use futures::StreamExt;
use reactor_core::error::CoreError;
use reactor_core::events::ReactorEvent;
use reactor_core::http::StaticAuth;
use reactor_core::peer::PeerEvent;
use reactor_core::protocol::envelope::MessageScope;
use reactor_core::reactor::{ConnectOptions, Reactor, ReactorDeps, ReactorOptions};
use reactor_core::runtime::TokioPlatform;
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
    pub on_frame:
        Option<unsafe extern "C" fn(*const u8, u32, u32, u64, u64, *const u8, u32, *mut c_void)>,
    pub on_audio: Option<unsafe extern "C" fn(*const i16, u32, u32, u32, *mut c_void)>,
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
struct VideoFrame {
    bgra: Vec<u8>,
    width: u32,
    height: u32,
    frame_id: u64,
    timestamp_us: u64,
    user_data: Vec<u8>,
}

/// A decoded remote audio frame, likewise copied.
struct AudioFrame {
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
}
unsafe impl Send for Completion {}
unsafe impl Sync for Completion {}

impl Completion {
    fn new(
        f: Option<unsafe extern "C" fn(c_int, *const c_char, *const c_char, *mut c_void)>,
        userdata: *mut c_void,
        gate: Arc<CallbackGate>,
    ) -> Self {
        Self { f, userdata, gate }
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
                if let Ok(cs) = CString::new(e.to_string()) {
                    unsafe { func(0, std::ptr::null(), cs.as_ptr(), self.userdata) }
                }
            }
        }
    }
}

// ── extern "C" API ───────────────────────────────────────────────────────────

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
/// `0` = synthetic (headless), `1` = platform (real mic/speaker with AEC/NS/AGC),
/// anything else = platform on desktop, synthetic on Android.
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
                peer_transport.with_frame_callback(move |data, w, h, frame_id, ts, ud| {
                    // Runs on a libwebrtc decode thread. Copy and hand off; the one
                    // thing not to do here is wait for the host.
                    tx.send(VideoFrame {
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
                peer_transport.with_audio_callback(move |pcm, sample_rate, channels| {
                    tx.send(AudioFrame {
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
    ($handle:expr, $completion:expr, $userdata:expr, $body:expr) => {{
        if $handle.is_null() {
            return;
        }
        let handle = unsafe { &*$handle };
        let reactor = handle.reactor.clone();
        let tasks = handle.tasks.clone();
        let body_tasks = handle.tasks.clone();
        let completion = Completion::new($completion, $userdata, handle.gate.clone());
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
/// `session_id` may be null to create a new session. `completion` is invoked
/// exactly once, on a tokio thread, and must stay callable until it fires — which
/// may be after the awaiting caller has given up.
#[no_mangle]
pub unsafe extern "C" fn reactor_connect(
    handle: *mut ReactorHandle,
    session_id: *const c_char,
    completion: Option<unsafe extern "C" fn(c_int, *const c_char, *const c_char, *mut c_void)>,
    userdata: *mut c_void,
) {
    let sid = if session_id.is_null() {
        None
    } else {
        Some(CStr::from_ptr(session_id).to_string_lossy().into_owned())
    };
    async_op!(
        handle,
        completion,
        userdata,
        move |r: Arc<Reactor>, tasks: TaskSet| async move {
            r.connect(ConnectOptions {
                session_id: sid,
                connection_id: None,
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
        handle,
        completion,
        userdata,
        |r: Arc<Reactor>, _tasks: TaskSet| async move { r.disconnect(false).await.map(|_| None) }
    );
}

/// Reconnect using the existing session, after a transient failure.
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
        handle,
        completion,
        userdata,
        |r: Arc<Reactor>, tasks: TaskSet| async move {
            r.reconnect().await?;
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
        handle,
        completion,
        userdata,
        move |r: Arc<Reactor>, _tasks: TaskSet| async move { r.resume_track(&name).await.map(|_| None) }
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

// ── Synchronous operations ────────────────────────────────────────────────────

/// Send an application-scoped command over the data channel, fire-and-forget.
/// Returns 0 on success, -1 on any failure (null handle, malformed JSON, channel
/// not ready, payload too large).
///
/// # Safety
///
/// `name` must be a NUL-terminated C string. `args_json` may be null (treated as
/// `{}`); otherwise it must be a NUL-terminated C string holding a JSON value.
#[no_mangle]
pub unsafe extern "C" fn reactor_send_command(
    handle: *mut ReactorHandle,
    name: *const c_char,
    args_json: *const c_char,
) -> c_int {
    if handle.is_null() {
        return -1;
    }
    let reactor = &(*handle).reactor;
    let name = CStr::from_ptr(name).to_string_lossy();
    let args: serde_json::Value = if args_json.is_null() {
        serde_json::json!({})
    } else {
        let raw = CStr::from_ptr(args_json).to_string_lossy();
        match serde_json::from_str(&raw) {
            Ok(v) => v,
            Err(_) => return -1,
        }
    };
    match reactor.send_command(&name, args, MessageScope::Application) {
        Ok(_) => 0,
        Err(_) => -1,
    }
}

/// Send a runtime-scoped (platform) command over the data channel.
///
/// # Safety
///
/// As [`reactor_send_command`].
#[no_mangle]
pub unsafe extern "C" fn reactor_send_runtime_command(
    handle: *mut ReactorHandle,
    name: *const c_char,
    args_json: *const c_char,
) -> c_int {
    if handle.is_null() {
        return -1;
    }
    let reactor = &(*handle).reactor;
    let name = CStr::from_ptr(name).to_string_lossy();
    let args: serde_json::Value = if args_json.is_null() {
        serde_json::json!({})
    } else {
        let raw = CStr::from_ptr(args_json).to_string_lossy();
        match serde_json::from_str(&raw) {
            Ok(v) => v,
            Err(_) => return -1,
        }
    };
    match reactor.send_command(&name, args, MessageScope::Runtime) {
        Ok(_) => 0,
        Err(_) => -1,
    }
}

/// Deactivate a sendonly track. Returns 0 on success, -1 on failure.
///
/// # Safety
///
/// `name` must be a NUL-terminated C string.
#[no_mangle]
pub unsafe extern "C" fn reactor_unpublish_track(
    handle: *mut ReactorHandle,
    name: *const c_char,
) -> c_int {
    if handle.is_null() {
        return -1;
    }
    let name = CStr::from_ptr(name).to_string_lossy();
    match (*handle).reactor.unpublish_track(&name) {
        Ok(_) => 0,
        Err(_) => -1,
    }
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

/// Release a string returned by [`reactor_session_id`].
///
/// # Safety
///
/// `s` must be null (no-op) or a pointer returned by [`reactor_session_id`] that
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
        let args = CString::new("{}").unwrap();

        unsafe {
            let status = CStr::from_ptr(reactor_status(std::ptr::null_mut()));
            assert_eq!(status.to_str().unwrap(), "disconnected");

            assert!(reactor_session_id(std::ptr::null_mut()).is_null());

            assert_eq!(
                reactor_send_command(std::ptr::null_mut(), name.as_ptr(), args.as_ptr()),
                -1
            );
            assert_eq!(
                reactor_send_runtime_command(std::ptr::null_mut(), name.as_ptr(), args.as_ptr()),
                -1
            );
            assert_eq!(
                reactor_unpublish_track(std::ptr::null_mut(), name.as_ptr()),
                -1
            );

            // Nothing to quiesce, so destroying nothing reports success.
            assert_eq!(reactor_destroy(std::ptr::null_mut()), 0);

            // A no-op rather than a return value: the assertion is that it does not
            // abort.
            reactor_free_string(std::ptr::null_mut());
        }
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
}
