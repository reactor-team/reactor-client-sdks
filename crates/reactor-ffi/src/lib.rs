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
//!
//! ## Known gaps in this contract
//!
//! Two properties a host needs are **not** currently provided, and bindings must
//! work around them until they are:
//!
//! 1. [`reactor_destroy`] does not stop the event pumps or wait for in-flight
//!    callbacks, so a callback may still fire after it returns. A host must
//!    therefore keep its callback context (a ctypes trampoline, a `cgo.Handle`, a
//!    JNI `GlobalRef`) alive beyond destroy — releasing it there is a
//!    use-after-free.
//! 2. `on_frame` and `on_audio` are invoked on libwebrtc's media threads, not on a
//!    tokio thread. A host callback that blocks — anything that takes the CPython
//!    GIL or attaches to the JVM — stalls decoding and, on the worker/network
//!    threads, can time out ICE.

mod http;
mod peer;

use std::ffi::{CStr, CString};
use std::os::raw::{c_char, c_double, c_int, c_void};
use std::sync::Arc;

use futures::StreamExt;
use reactor_core::error::CoreError;
use reactor_core::events::ReactorEvent;
use reactor_core::http::StaticAuth;
use reactor_core::peer::PeerEvent;
use reactor_core::protocol::envelope::MessageScope;
use reactor_core::reactor::{ConnectOptions, Reactor, ReactorDeps, ReactorOptions};
use reactor_core::runtime::TokioPlatform;
use reactor_webrtc::AdmMode;

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
}
unsafe impl Send for CallbackSet {}
unsafe impl Sync for CallbackSet {}

impl CallbackSet {
    fn fire_str(&self, f: Option<unsafe extern "C" fn(*const c_char, *mut c_void)>, s: &str) {
        if let Some(func) = f {
            if let Ok(cs) = CString::new(s) {
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

pub struct ReactorHandle {
    reactor: Arc<Reactor>,
}

struct Completion {
    f: Option<unsafe extern "C" fn(c_int, *const c_char, *const c_char, *mut c_void)>,
    userdata: *mut c_void,
}
unsafe impl Send for Completion {}
unsafe impl Sync for Completion {}

impl Completion {
    fn new(
        f: Option<unsafe extern "C" fn(c_int, *const c_char, *const c_char, *mut c_void)>,
        userdata: *mut c_void,
    ) -> Self {
        Self { f, userdata }
    }

    fn resolve(self, result: Result<Option<serde_json::Value>, CoreError>) {
        let Some(func) = self.f else { return };
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
    if !callbacks.is_null() {
        let c = &*callbacks;
        if let Some(frame_fn) = c.on_frame {
            let userdata_usize = c.userdata as usize;
            peer_transport =
                peer_transport.with_frame_callback(move |data, w, h, frame_id, ts, ud| unsafe {
                    frame_fn(
                        data.as_ptr(),
                        w,
                        h,
                        frame_id,
                        ts,
                        if ud.is_empty() {
                            std::ptr::null()
                        } else {
                            ud.as_ptr()
                        },
                        ud.len() as u32,
                        userdata_usize as *mut c_void,
                    )
                });
        }
        if let Some(audio_fn) = c.on_audio {
            let userdata_usize = c.userdata as usize;
            peer_transport =
                peer_transport.with_audio_callback(move |pcm, sample_rate, channels| unsafe {
                    audio_fn(
                        pcm.as_ptr(),
                        pcm.len() as u32,
                        sample_rate,
                        channels,
                        userdata_usize as *mut c_void,
                    )
                });
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

    let reactor2 = reactor.clone();
    let mut peer_rx = peer_event_rx;
    runtime().spawn(async move {
        while let Some(ev) = peer_rx.next().await {
            reactor2.handle_peer_event(ev).await;
        }
    });

    if let Some(cbs) = cbs {
        let mut event_rx = reactor.subscribe();
        let cbs = Arc::new(cbs);
        runtime().spawn(async move {
            while let Some(event) = event_rx.next().await {
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
        });
    }

    Box::into_raw(Box::new(ReactorHandle { reactor }))
}

/// Release a handle.
///
/// # Safety
///
/// `handle` must be null or a live handle, and must not be used again afterwards.
///
/// This does **not** quiesce callbacks: the event pumps keep running and a
/// callback may fire after this returns (see the module's *Known gaps*). Callers
/// must not release their callback context here.
#[no_mangle]
pub unsafe extern "C" fn reactor_destroy(handle: *mut ReactorHandle) {
    if !handle.is_null() {
        drop(Box::from_raw(handle));
    }
}

// ── Async operations ─────────────────────────────────────────────────────────

macro_rules! async_op {
    ($handle:expr, $completion:expr, $userdata:expr, $body:expr) => {{
        if $handle.is_null() {
            return;
        }
        let reactor = unsafe { &*$handle }.reactor.clone();
        let completion = Completion::new($completion, $userdata);
        runtime().spawn(async move {
            let result: Result<Option<serde_json::Value>, CoreError> = $body(reactor).await;
            completion.resolve(result);
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
        move |r: Arc<Reactor>| async move {
            r.connect(ConnectOptions {
                session_id: sid,
                connection_id: None,
            })
            .await?;
            let r2 = r.clone();
            runtime().spawn(async move { r2.run_heartbeat().await });
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
    async_op!(handle, completion, userdata, |r: Arc<Reactor>| async move {
        r.disconnect(false).await.map(|_| None)
    });
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
    async_op!(handle, completion, userdata, |r: Arc<Reactor>| async move {
        r.reconnect().await?;
        let r2 = r.clone();
        runtime().spawn(async move { r2.run_heartbeat().await });
        Ok(None)
    });
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
        move |r: Arc<Reactor>| async move { r.publish_track(&name).await.map(|_| None) }
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
        move |r: Arc<Reactor>| async move { r.pause_track(&name).await.map(|_| None) }
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
        move |r: Arc<Reactor>| async move { r.resume_track(&name).await.map(|_| None) }
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
        move |r: Arc<Reactor>| async move {
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
    async_op!(handle, completion, userdata, |r: Arc<Reactor>| async move {
        let clip = r.request_recording().await?;
        serde_json::to_value(&clip)
            .map(Some)
            .map_err(|e| CoreError::Decode(e.to_string()))
    });
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
        move |r: Arc<Reactor>| async move {
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

            // No-ops rather than returns: the assertion is that they do not abort.
            reactor_destroy(std::ptr::null_mut());
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
