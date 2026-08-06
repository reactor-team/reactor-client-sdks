//! C ABI shared library for the Reactor client SDK.
//!
//! Bundles a multi-threaded tokio runtime and a libwebrtc-style WebRTC
//! transport.  Language bindings (Python ctypes, Swift, Kotlin, Go, C++)
//! load the resulting dylib/so and call the `reactor_*` functions.

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
    pub on_frame: Option<unsafe extern "C" fn(*const u8, u32, u32, *mut c_void)>,
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
            peer_transport = peer_transport.with_frame_callback(move |data, w, h| unsafe {
                frame_fn(data.as_ptr(), w, h, userdata_usize as *mut c_void)
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
            .await
            .map(|_| None)
        }
    );
}

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

#[no_mangle]
pub unsafe extern "C" fn reactor_reconnect(
    handle: *mut ReactorHandle,
    completion: Option<unsafe extern "C" fn(c_int, *const c_char, *const c_char, *mut c_void)>,
    userdata: *mut c_void,
) {
    async_op!(handle, completion, userdata, |r: Arc<Reactor>| async move {
        r.reconnect().await.map(|_| None)
    });
}

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

#[no_mangle]
pub unsafe extern "C" fn reactor_status(handle: *mut ReactorHandle) -> *const c_char {
    let s = if handle.is_null() {
        "disconnected"
    } else {
        (*handle).reactor.status().as_str()
    };
    match s {
        "connecting" => b"connecting\0".as_ptr() as *const c_char,
        "waiting" => b"waiting\0".as_ptr() as *const c_char,
        "ready" => b"ready\0".as_ptr() as *const c_char,
        _ => b"disconnected\0".as_ptr() as *const c_char,
    }
}

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

#[no_mangle]
pub unsafe extern "C" fn reactor_free_string(s: *mut c_char) {
    if !s.is_null() {
        drop(CString::from_raw(s));
    }
}

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
