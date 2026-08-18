//! The `#[wasm_bindgen]` client: everything JavaScript touches.
//!
//! Naming follows the JS SDK, not Rust: `#[wasm_bindgen(js_name = ...)]`
//! everywhere the two disagree, so the TypeScript layer reads like TypeScript.

use std::cell::RefCell;
use std::collections::BTreeMap;
use std::rc::Rc;
use std::sync::Arc;
use std::time::Duration;

use futures::channel::{mpsc, oneshot};
use futures::future::{FutureExt, Shared};
use futures::StreamExt;
use js_sys::Function;
use serde::Deserialize;
use wasm_bindgen::prelude::*;
use wasm_bindgen_futures::{spawn_local, JsFuture};

use reactor_core::backoff::PollConfig;
use reactor_core::error::{CoreError, ErrorDetails, ReactorError};
use reactor_core::events::ReactorEvent;
use reactor_core::peer::{PeerEvent, PeerTransport};
use reactor_core::protocol::upload::FileRef;
use reactor_core::reactor::{ConnectOptions, Reactor, ReactorDeps, ReactorOptions};

use crate::auth::WasmAuthProvider;
use crate::http::WasmHttpClient;
use crate::peer::WasmPeerTransport;
use crate::platform::WasmPlatform;
use crate::types::{
    CapabilitiesListener, CapabilitiesOutput, ClientOptionsInput, ClipOutput, CommandData,
    CommandReply, ConnectOptionsInput, ErrorListener, FileRefOutput, JwtSourceInput,
    MessageListener, ReactorErrorOutput, SchemaOutput, SdpTransformInput, SessionIdListener,
    SessionInfoOutput, Status, StatusListener, StringsOutput, TrackListener, TrackMappingOutput,
    TracksOutput, UploadsInput,
};

/// Coordinator URL used when none is given.
const DEFAULT_API_URL: &str = "https://api.reactor.inc";
/// Coordinator URL used when none is given and `local` is set — the address
/// `reactor-runtime` serves its local HTTP API on, matching the JS SDK.
const DEFAULT_LOCAL_API_URL: &str = "http://localhost:8080";
/// SDK type reported to the coordinator in `client_info`.
const SDK_TYPE: &str = "js";

// ── Options ───────────────────────────────────────────────────────────────────

/// Construction options, as a plain JS object.
///
/// Only `modelName` is required. Every duration is milliseconds, matching JS
/// conventions rather than the core's `Duration`s.
#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct ClientOptions {
    api_url: Option<String>,
    model_name: String,
    local: bool,
    sdk_type: Option<String>,
    sdk_version: Option<String>,
    /// Resume every recvonly track once connected. Default `true`; overridable
    /// per connect.
    auto_resume_tracks: Option<bool>,
    /// Free-form model arguments sent on session creation.
    extra_args: Option<serde_json::Value>,
    heartbeat_interval_ms: Option<u64>,
    ready_timeout_ms: Option<u64>,
    control_request_timeout_ms: Option<u64>,
    clip_request_timeout_ms: Option<u64>,
    max_session_attempts: Option<u32>,
    max_sdp_attempts: Option<u32>,
    /// `"off"`, `"error"`, `"warn"`, `"info"`, `"debug"` or `"trace"`.
    /// Defaults to `"warn"`: the core logs freely at debug, and a browser
    /// console is a user-facing surface.
    log_level: Option<String>,
}

impl ClientOptions {
    fn into_reactor_options(self) -> Result<ReactorOptions, JsValue> {
        if self.model_name.is_empty() {
            return Err(invalid("options.modelName is required"));
        }
        let api_url = self.api_url.unwrap_or_else(|| {
            if self.local {
                DEFAULT_LOCAL_API_URL.to_string()
            } else {
                DEFAULT_API_URL.to_string()
            }
        });

        let mut options = ReactorOptions::new(api_url, self.model_name);
        options.local = self.local;
        options.sdk_type = self.sdk_type.unwrap_or_else(|| SDK_TYPE.to_string());
        if let Some(version) = self.sdk_version {
            options.sdk_version = version;
        }
        if let Some(auto_resume) = self.auto_resume_tracks {
            options.auto_resume_tracks = auto_resume;
        }
        options.extra_args = self.extra_args;
        if let Some(ms) = self.heartbeat_interval_ms {
            options.heartbeat_interval = Duration::from_millis(ms);
        }
        if let Some(ms) = self.ready_timeout_ms {
            options.ready_timeout = Duration::from_millis(ms);
        }
        if let Some(ms) = self.control_request_timeout_ms {
            options.control_request_timeout = Duration::from_millis(ms);
        }
        if let Some(ms) = self.clip_request_timeout_ms {
            options.clip_request_timeout = Duration::from_millis(ms);
        }
        if let Some(attempts) = self.max_session_attempts {
            options.session_poll = PollConfig {
                max_attempts: attempts,
                ..options.session_poll
            };
        }
        if let Some(attempts) = self.max_sdp_attempts {
            options.sdp_poll = PollConfig {
                max_attempts: attempts,
                ..options.sdp_poll
            };
        }
        Ok(options)
    }
}

/// Per-connect options, as a plain JS object. All fields optional.
#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct JsConnectOptions {
    /// Adopt a session someone else created (a backend, another client).
    session_id: Option<String>,
    /// Use a connection id already registered under that session.
    connection_id: Option<u32>,
    auto_resume_tracks: Option<bool>,
    /// SDP-answer poll attempts before giving up.
    max_attempts: Option<u32>,
}

impl From<JsConnectOptions> for ConnectOptions {
    fn from(options: JsConnectOptions) -> Self {
        ConnectOptions {
            session_id: options.session_id,
            connection_id: options.connection_id,
            auto_resume_tracks: options.auto_resume_tracks,
            max_sdp_attempts: options.max_attempts,
        }
    }
}

// ── Callbacks ─────────────────────────────────────────────────────────────────

#[derive(Default)]
struct Callbacks {
    status_changed: Option<Function>,
    session_id_changed: Option<Function>,
    message: Option<Function>,
    runtime_message: Option<Function>,
    track_received: Option<Function>,
    error: Option<Function>,
    capabilities_received: Option<Function>,
}

type SharedCallbacks = Rc<RefCell<Callbacks>>;

/// Resolves when the client is dropped. Cloneable, so every task the client
/// spawns can wait on the same signal.
type Shutdown = Shared<oneshot::Receiver<()>>;

/// Run `task` until it finishes or the client is dropped, whichever comes first.
///
/// Every one of these tasks holds the reactor, and the reactor holds the
/// transport whose sender feeds the pump — a cycle that dropping the JS handle
/// cannot break on its own. Cancelling them on shutdown is what lets the whole
/// graph go.
fn spawn_until_shutdown(shutdown: Shutdown, task: impl std::future::Future<Output = ()> + 'static) {
    spawn_local(async move {
        futures::future::select(Box::pin(task), shutdown).await;
    });
}

/// Call a listener, if one is registered.
///
/// A listener that throws is logged and swallowed: it is application code
/// running on our event loop, and letting it abort the loop would take the
/// session down with it.
fn call(callback: &Option<Function>, arguments: &[JsValue]) {
    let Some(callback) = callback else { return };
    let array = js_sys::Array::new();
    for argument in arguments {
        array.push(argument);
    }
    if let Err(error) = js_sys::Reflect::apply(callback, &JsValue::null(), &array) {
        log::error!(
            "[reactor-wasm] event listener threw: {}",
            crate::http::describe(&error)
        );
    }
}

// ── Client ────────────────────────────────────────────────────────────────────

/// The Reactor client, as JavaScript sees it.
///
/// ```js
/// import init, { ReactorClient } from "@reactor-team/reactor-wasm";
///
/// await init();
/// const client = new ReactorClient({ modelName: "my-model" }, () => getToken());
/// client.onStatusChanged((status) => console.log(status));
/// client.onTrackReceived((name, mid) => {
///   video.srcObject = client.getStreamByMid(mid);
/// });
/// await client.connect();
/// await client.sendCommand("set_prompt", { prompt: "a cat" });
/// ```
#[wasm_bindgen]
pub struct ReactorClient {
    reactor: Rc<Reactor>,
    // `Arc` rather than `Rc` because the core's dependency aliases are `Arc<dyn
    // …>` on every target; the client keeps the concrete type alongside so it
    // can reach the browser objects the traits have no vocabulary for.
    peer: Arc<WasmPeerTransport>,
    auth: Arc<WasmAuthProvider>,
    callbacks: SharedCallbacks,
    /// Dropped by `Drop`, which is what ends the spawned tasks.
    shutdown_tx: Option<oneshot::Sender<()>>,
    shutdown: Shutdown,
}

#[wasm_bindgen]
impl ReactorClient {
    /// Create a client.
    ///
    /// * `options` — see the `ClientOptions` fields; `modelName` is required.
    /// * `jwt` — a token string, a `() => string | Promise<string>` resolver
    ///   called before every authenticated request, or `null` for an
    ///   unauthenticated local runtime. Replaceable later with `setJwt`.
    #[wasm_bindgen(constructor)]
    // The dependencies below are `Arc`s without being `Send` or `Sync` — see the
    // note on the struct's fields.
    #[allow(clippy::arc_with_non_send_sync)]
    pub fn new(
        options: ClientOptionsInput,
        jwt: Option<JwtSourceInput>,
    ) -> Result<ReactorClient, JsValue> {
        let options: ClientOptions = serde_wasm_bindgen::from_value(options.into())
            .map_err(|e| invalid(&format!("options: {e}")))?;
        init_logging(options.log_level.as_deref());
        let options = options.into_reactor_options()?;

        let auth = Arc::new(WasmAuthProvider::new());
        auth.set(jwt.map(JsValue::from).unwrap_or(JsValue::UNDEFINED))
            .map_err(|e| error_value(&e.details(None)))?;

        let (shutdown_tx, shutdown_rx) = oneshot::channel::<()>();
        let shutdown = shutdown_rx.shared();

        let (peer_event_tx, peer_event_rx) = mpsc::unbounded::<PeerEvent>();
        let peer = Arc::new(WasmPeerTransport::new(peer_event_tx));
        let callbacks: SharedCallbacks = Rc::new(RefCell::new(Callbacks::default()));

        // Under wasm the core's `Shared*` aliases drop their `Send + Sync`
        // bound, which is what lets a transport built out of browser handles be
        // one of them at all.
        let reactor = Rc::new(Reactor::new(
            ReactorDeps {
                http: Arc::new(WasmHttpClient),
                auth: auth.clone() as reactor_core::SharedAuth,
                platform: Arc::new(WasmPlatform),
                peer: peer.clone() as reactor_core::SharedPeer,
            },
            options,
        ));

        // Pump: WebRTC callbacks → core.
        {
            let reactor = reactor.clone();
            let mut peer_events = peer_event_rx;
            spawn_until_shutdown(shutdown.clone(), async move {
                while let Some(event) = peer_events.next().await {
                    reactor.handle_peer_event(event).await;
                }
            });
        }

        // Dispatch: core events → JS listeners.
        {
            let mut events = reactor.subscribe();
            let callbacks = callbacks.clone();
            spawn_until_shutdown(shutdown.clone(), async move {
                while let Some(event) = events.next().await {
                    dispatch(&callbacks, event);
                }
            });
        }

        Ok(ReactorClient {
            reactor,
            peer,
            auth,
            callbacks,
            shutdown_tx: Some(shutdown_tx),
            shutdown,
        })
    }

    // ── Configuration ─────────────────────────────────────────────────────────

    /// Replace the token source. Takes effect on the next request, so this is
    /// how a client built before sign-in gets its token.
    #[wasm_bindgen(js_name = setJwt)]
    pub fn set_jwt(&self, jwt: Option<JwtSourceInput>) -> Result<(), JsValue> {
        self.auth
            .set(jwt.map(JsValue::from).unwrap_or(JsValue::UNDEFINED))
            .map_err(|e| error_value(&e.details(Some("setJwt"))))
    }

    /// Install a `(sdp: string) => string` transform applied to the local offer
    /// before it is set and sent, or `null` to remove it.
    ///
    /// Browsers need a few normalizations on their own offer that libwebrtc
    /// hosts do not (dynamic payload types inside \[96,127\], no
    /// telephone-event, Chrome-style attribute ordering). That logic is data
    /// munging, not session logic, so it stays in the SDK that already has it
    /// rather than being ported into the core.
    #[wasm_bindgen(js_name = setSdpTransform)]
    pub fn set_sdp_transform(&self, transform: Option<SdpTransformInput>) -> Result<(), JsValue> {
        let Some(transform) = transform.map(JsValue::from).filter(|t| !t.is_null()) else {
            self.peer.set_sdp_transform(None);
            return Ok(());
        };
        if !transform.is_function() {
            return Err(invalid("sdp transform must be a function or null"));
        }
        self.peer
            .set_sdp_transform(Some(transform.unchecked_into()));
        Ok(())
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /// Create (or adopt) a session and bring up the transport.
    pub async fn connect(&self, options: Option<ConnectOptionsInput>) -> Result<(), JsValue> {
        let options: JsConnectOptions = from_optional(options, "connect options")?;
        self.reactor
            .connect(options.into())
            .await
            .map_err(|e| error_value(&e.details(Some("connect"))))?;
        self.start_heartbeat();
        Ok(())
    }

    /// Tear down the transport and end the session server-side — unless this
    /// client only adopted the session, in which case it stays alive for
    /// whoever created it.
    pub async fn disconnect(&self) -> Result<(), JsValue> {
        self.reactor
            .disconnect(false)
            .await
            .map_err(|e| error_value(&e.details(Some("disconnect"))))
    }

    /// Rebuild the transport on the same session, without ending it.
    pub async fn reconnect(&self) -> Result<(), JsValue> {
        self.reactor
            .reconnect()
            .await
            .map_err(|e| error_value(&e.details(Some("reconnect"))))?;
        self.start_heartbeat();
        Ok(())
    }

    // ── Messaging ─────────────────────────────────────────────────────────────

    /// Send a command to the model and resolve with its reply.
    ///
    /// * `data` — a JSON-serializable object.
    /// * `uploads` — optional `{ param: FileRef }` map, from `uploadFile`.
    ///
    /// Resolves with `{ type, data }`, or `undefined` when the model's handler
    /// acknowledged the command without answering.
    #[wasm_bindgen(js_name = sendCommand)]
    pub async fn send_command(
        &self,
        command: String,
        data: Option<CommandData>,
        uploads: Option<UploadsInput>,
    ) -> Result<CommandReply, JsValue> {
        let data: serde_json::Value = match data.map(JsValue::from).filter(|d| !d.is_null()) {
            Some(data) => serde_wasm_bindgen::from_value(data)
                .map_err(|e| invalid(&format!("command data: {e}")))?,
            None => serde_json::json!({}),
        };
        let uploads: Option<BTreeMap<String, FileRef>> = from_optional(uploads, "command uploads")?;

        let reply = self
            .reactor
            .send_command(&command, data, uploads)
            .await
            .map_err(|e| error_value(&e.details(Some("sendCommand"))))?;
        cast(&reply)
    }

    /// Request the model's command schema (an OpenAPI document).
    #[wasm_bindgen(js_name = requestSchema)]
    pub async fn request_schema(&self) -> Result<SchemaOutput, JsValue> {
        let schema = self
            .reactor
            .request_schema()
            .await
            .map_err(|e| error_value(&e.details(Some("requestSchema"))))?;
        cast(&schema)
    }

    // ── Tracks ────────────────────────────────────────────────────────────────

    /// Claim a sendonly track and start sending `track` on it.
    #[wasm_bindgen(js_name = publishTrack)]
    pub async fn publish_track(
        &self,
        name: String,
        track: web_sys::MediaStreamTrack,
    ) -> Result<(), JsValue> {
        // Claim the slot first (a control round-trip): attaching media to a
        // track the runtime has not accepted would send into a void.
        self.reactor
            .publish_track(&name)
            .await
            .map_err(|e| error_value(&e.details(Some("publishTrack"))))?;
        self.peer
            .replace_sender_track(&name, Some(&track))
            .await
            .map_err(|e| error_value(&e.details(Some("publishTrack"))))
    }

    /// Stop sending on a published track and release it.
    #[wasm_bindgen(js_name = unpublishTrack)]
    pub async fn unpublish_track(&self, name: String) -> Result<(), JsValue> {
        self.reactor
            .unpublish_track(&name)
            .map_err(|e| error_value(&e.details(Some("unpublishTrack"))))?;
        self.peer
            .replace_sender_track(&name, None)
            .await
            .map_err(|e| error_value(&e.details(Some("unpublishTrack"))))
    }

    /// Stop receiving a track: the receiver goes inactive and the runtime stops
    /// producing it.
    #[wasm_bindgen(js_name = pauseTrack)]
    pub async fn pause_track(&self, name: String) -> Result<(), JsValue> {
        self.reactor
            .pause_track(&name)
            .await
            .map_err(|e| error_value(&e.details(Some("pauseTrack"))))
    }

    /// Resume a paused track.
    #[wasm_bindgen(js_name = resumeTrack)]
    pub async fn resume_track(&self, name: String) -> Result<(), JsValue> {
        self.reactor
            .resume_track(&name)
            .await
            .map_err(|e| error_value(&e.details(Some("resumeTrack"))))
    }

    /// The tracks the runtime declared for this session:
    /// `[{ name, kind, direction }]`. Empty until capabilities arrive.
    pub fn tracks(&self) -> Result<TracksOutput, JsValue> {
        cast(&self.reactor.tracks())
    }

    /// The negotiated `name` → `mid` mapping: `[{ name, kind, direction, mid }]`.
    #[wasm_bindgen(js_name = trackMapping)]
    pub fn track_mapping(&self) -> Result<TrackMappingOutput, JsValue> {
        cast(&self.reactor.track_mapping())
    }

    /// Names of the tracks currently paused.
    #[wasm_bindgen(js_name = pausedTracks)]
    pub fn paused_tracks(&self) -> Result<StringsOutput, JsValue> {
        cast(&self.reactor.paused_tracks())
    }

    // ── Recording ─────────────────────────────────────────────────────────────

    /// Capture the last `durationSeconds` of the session.
    #[wasm_bindgen(js_name = requestClip)]
    pub async fn request_clip(&self, duration_seconds: f64) -> Result<ClipOutput, JsValue> {
        let clip = self
            .reactor
            .request_clip(duration_seconds)
            .await
            .map_err(|e| error_value(&e.details(Some("requestClip"))))?;
        cast(&clip)
    }

    /// Capture the session in full.
    #[wasm_bindgen(js_name = requestRecording)]
    pub async fn request_recording(&self) -> Result<ClipOutput, JsValue> {
        let clip = self
            .reactor
            .request_recording()
            .await
            .map_err(|e| error_value(&e.details(Some("requestRecording"))))?;
        cast(&clip)
    }

    // ── Uploads ───────────────────────────────────────────────────────────────

    /// Upload a `File` or `Blob` to the session's object store and resolve with
    /// a `FileRef` to pass in a command's `uploads`.
    ///
    /// `name` overrides the file name; a `Blob` has none, so it needs one.
    #[wasm_bindgen(js_name = uploadFile)]
    pub async fn upload_file(
        &self,
        file: web_sys::Blob,
        name: Option<String>,
    ) -> Result<FileRefOutput, JsValue> {
        let name = name.unwrap_or_else(|| {
            file.dyn_ref::<web_sys::File>()
                .map(|file| file.name())
                .unwrap_or_else(|| "upload".to_string())
        });
        let mime_type = match file.type_() {
            mime if mime.is_empty() => "application/octet-stream".to_string(),
            mime => mime,
        };
        let buffer = JsFuture::from(file.array_buffer()).await.map_err(|e| {
            invalid(&format!(
                "could not read the file: {}",
                crate::http::describe(&e)
            ))
        })?;
        let bytes = js_sys::Uint8Array::new(&buffer).to_vec();

        let file_ref = self
            .reactor
            .upload_file(&name, &mime_type, bytes)
            .await
            .map_err(|e| error_value(&e.details(Some("uploadFile"))))?;
        cast(&file_ref)
    }

    // ── Introspection ─────────────────────────────────────────────────────────

    /// `"disconnected"` | `"connecting"` | `"waiting"` | `"ready"`.
    pub fn status(&self) -> Status {
        JsValue::from_str(self.reactor.status().as_str()).unchecked_into()
    }

    /// The current session id, or `undefined`.
    #[wasm_bindgen(js_name = sessionId)]
    pub fn session_id(&self) -> Option<String> {
        self.reactor.session_id()
    }

    /// The session resource from the coordinator, or `undefined` when
    /// disconnected: model, cluster, server info, selected transport.
    #[wasm_bindgen(js_name = sessionInfo)]
    pub fn session_info(&self) -> Result<SessionInfoOutput, JsValue> {
        cast(&self.reactor.session_info())
    }

    /// The runtime's capabilities, or `undefined` before they arrive.
    pub fn capabilities(&self) -> Result<CapabilitiesOutput, JsValue> {
        cast(&self.reactor.capabilities())
    }

    /// The last error, in the shape the `onError` listener receives.
    #[wasm_bindgen(js_name = lastError)]
    pub fn last_error(&self) -> Result<ReactorErrorOutput, JsValue> {
        cast(&self.reactor.last_error())
    }

    // ── Browser handles ───────────────────────────────────────────────────────

    /// The live `RTCPeerConnection` — for `getStats()`, or anything else the
    /// binding does not wrap. `undefined` before the first connect.
    #[wasm_bindgen(js_name = getPeerConnection)]
    pub fn get_peer_connection(&self) -> Option<web_sys::RtcPeerConnection> {
        self.peer.peer_connection()
    }

    /// A received track by its SDP mid, as reported by `onTrackReceived`.
    #[wasm_bindgen(js_name = getTrackByMid)]
    pub fn get_track_by_mid(&self, mid: String) -> Option<web_sys::MediaStreamTrack> {
        self.peer.received_track(&mid).map(|entry| entry.track)
    }

    /// The `MediaStream` a received track arrived on — what a `<video>` or
    /// `<audio>` element's `srcObject` wants.
    #[wasm_bindgen(js_name = getStreamByMid)]
    pub fn get_stream_by_mid(&self, mid: String) -> Option<web_sys::MediaStream> {
        self.peer.received_track(&mid).map(|entry| entry.stream)
    }

    /// A received track by its declared name.
    ///
    /// Useful when the track arrived before the listener was registered — a
    /// component that mounts mid-connect misses the event but not the track.
    #[wasm_bindgen(js_name = getTrackByName)]
    pub fn get_track_by_name(&self, name: String) -> Option<web_sys::MediaStreamTrack> {
        self.get_track_by_mid(self.mid_of(&name)?)
    }

    /// The stream of a received track, by declared name.
    #[wasm_bindgen(js_name = getStreamByName)]
    pub fn get_stream_by_name(&self, name: String) -> Option<web_sys::MediaStream> {
        self.get_stream_by_mid(self.mid_of(&name)?)
    }

    // ── Events ────────────────────────────────────────────────────────────────

    /// `(status: string) => void`
    #[wasm_bindgen(js_name = onStatusChanged)]
    pub fn on_status_changed(&self, listener: StatusListener) {
        self.callbacks.borrow_mut().status_changed = Some(listener.unchecked_into());
    }

    /// `(sessionId: string | undefined) => void`
    #[wasm_bindgen(js_name = onSessionIdChanged)]
    pub fn on_session_id_changed(&self, listener: SessionIdListener) {
        self.callbacks.borrow_mut().session_id_changed = Some(listener.unchecked_into());
    }

    /// `(message: { type, data }) => void` — application messages from the model.
    #[wasm_bindgen(js_name = onMessage)]
    pub fn on_message(&self, listener: MessageListener) {
        self.callbacks.borrow_mut().message = Some(listener.unchecked_into());
    }

    /// `(message: { type, data }) => void` — platform messages: moderation,
    /// recording lifecycle, and the rest of the runtime's own traffic.
    #[wasm_bindgen(js_name = onRuntimeMessage)]
    pub fn on_runtime_message(&self, listener: MessageListener) {
        self.callbacks.borrow_mut().runtime_message = Some(listener.unchecked_into());
    }

    /// `(name: string, mid: string | undefined) => void` — a remote track
    /// arrived. Fetch the media with `getTrackByMid` / `getStreamByMid`.
    #[wasm_bindgen(js_name = onTrackReceived)]
    pub fn on_track_received(&self, listener: TrackListener) {
        self.callbacks.borrow_mut().track_received = Some(listener.unchecked_into());
    }

    /// `(error: { code, message, recoverable, timestampMs, status?, operation?,
    /// retryAfterMs? }) => void`
    #[wasm_bindgen(js_name = onError)]
    pub fn on_error(&self, listener: ErrorListener) {
        self.callbacks.borrow_mut().error = Some(listener.unchecked_into());
    }

    /// `(capabilities: { protocolVersion, tracks, commands? }) => void`
    #[wasm_bindgen(js_name = onCapabilitiesReceived)]
    pub fn on_capabilities_received(&self, listener: CapabilitiesListener) {
        self.callbacks.borrow_mut().capabilities_received = Some(listener.unchecked_into());
    }
}

impl ReactorClient {
    /// Keep the session alive for as long as it is ready. The core's loop exits
    /// on its own when the connection ends or another connect starts, so this is
    /// spawn-and-forget rather than something to cancel.
    fn start_heartbeat(&self) {
        let reactor = self.reactor.clone();
        spawn_until_shutdown(self.shutdown.clone(), async move {
            reactor.run_heartbeat().await
        });
    }

    fn mid_of(&self, name: &str) -> Option<String> {
        self.reactor
            .track_mapping()
            .into_iter()
            .find(|entry| entry.name == name)
            .map(|entry| entry.mid)
    }
}

impl Drop for ReactorClient {
    fn drop(&mut self) {
        // Ends the pump, the dispatcher and the heartbeat. Without it they keep
        // the reactor — and the transport, the listeners and the token source
        // with it — alive for the lifetime of the page.
        drop(self.shutdown_tx.take());
        // A freed client must also stop decoding media. Ending the *session* is
        // still `disconnect()`'s job, which a holder should call first: tearing
        // down a session because a handle was garbage collected would be a
        // surprising thing for a free() to do over the network.
        let peer = self.peer.clone();
        spawn_local(async move {
            let _ = peer.close().await;
        });
    }
}

// ── Event dispatch ────────────────────────────────────────────────────────────

fn dispatch(callbacks: &SharedCallbacks, event: ReactorEvent) {
    // Snapshot the listener, then release the borrow: a listener is free to
    // register another one (or to call back into the client) while it runs.
    let callback = {
        let callbacks = callbacks.borrow();
        match &event {
            ReactorEvent::StatusChanged(_) => callbacks.status_changed.clone(),
            ReactorEvent::SessionIdChanged(_) => callbacks.session_id_changed.clone(),
            ReactorEvent::Message(_) => callbacks.message.clone(),
            ReactorEvent::RuntimeMessage(_) => callbacks.runtime_message.clone(),
            ReactorEvent::TrackReceived { .. } => callbacks.track_received.clone(),
            ReactorEvent::Error(_) => callbacks.error.clone(),
            ReactorEvent::CapabilitiesReceived(_) => callbacks.capabilities_received.clone(),
        }
    };
    if callback.is_none() {
        return;
    }

    match event {
        ReactorEvent::StatusChanged(status) => {
            call(&callback, &[JsValue::from_str(status.as_str())]);
        }
        ReactorEvent::SessionIdChanged(session_id) => {
            call(&callback, &[optional_string(session_id)]);
        }
        ReactorEvent::Message(message) | ReactorEvent::RuntimeMessage(message) => {
            if let Ok(value) = to_js(&message) {
                call(&callback, &[value]);
            }
        }
        ReactorEvent::TrackReceived { name, mid } => {
            call(&callback, &[JsValue::from_str(&name), optional_string(mid)]);
        }
        ReactorEvent::Error(error) => {
            if let Ok(value) = to_js(&error) {
                call(&callback, &[value]);
            }
        }
        ReactorEvent::CapabilitiesReceived(capabilities) => {
            if let Ok(value) = to_js(&capabilities) {
                call(&callback, &[value]);
            }
        }
    }
}

// ── Conversions ───────────────────────────────────────────────────────────────

/// Serialize a core type into a plain JS object.
///
/// `Serializer::json_compatible` matters: the default emits ES `Map`s for maps,
/// which is not what an app that does `JSON.stringify(message)` or reads
/// `clip.playlistUrl` expects.
fn to_js<T: serde::Serialize + ?Sized>(value: &T) -> Result<JsValue, JsValue> {
    value
        .serialize(&serde_wasm_bindgen::Serializer::json_compatible())
        .map_err(|e| invalid(&e.to_string()))
}

/// Serialize a core type into the TypeScript type the signature promises.
fn cast<T: serde::Serialize + ?Sized, R: JsCast>(value: &T) -> Result<R, JsValue> {
    to_js(value).map(JsCast::unchecked_from_js)
}

/// Deserialize an optional JS argument, treating `null`/`undefined` as absent.
fn from_optional<T: serde::de::DeserializeOwned + Default, V: Into<JsValue>>(
    value: Option<V>,
    what: &str,
) -> Result<T, JsValue> {
    let Some(value) = value.map(Into::into).filter(|v| !v.is_null()) else {
        return Ok(T::default());
    };
    serde_wasm_bindgen::from_value(value).map_err(|e| invalid(&format!("{what}: {e}")))
}

fn optional_string(value: Option<String>) -> JsValue {
    value.map(JsValue::from).unwrap_or(JsValue::UNDEFINED)
}

/// A rejected call, as a JS `Error` that also carries the core's error details.
///
/// A message alone would force the SDK back to matching on text, which is what
/// the core's typed codes exist to end. So the thrown error is stamped into a
/// full `ReactorError` — the same type, with the same field names, that the
/// `onError` event delivers, `timestamp_ms` included — and a caller can handle
/// both through one code path.
fn error_value(details: &ErrorDetails) -> JsValue {
    let error = js_sys::Error::new(&details.message);
    error.set_name("ReactorError");
    let record = ReactorError {
        details: details.clone(),
        timestamp_ms: js_sys::Date::now(),
    };
    if let Ok(serialized) = to_js(&record) {
        if let Some(serialized) = serialized.dyn_ref::<js_sys::Object>() {
            let _ = js_sys::Object::assign(error.unchecked_ref(), serialized);
        }
    }
    error.into()
}

/// A caller mistake — bad options, an unreadable file, a listener of the wrong
/// type — rather than a failure of the session.
fn invalid(message: &str) -> JsValue {
    error_value(&CoreError::InvalidState(message.to_string()).details(None))
}

/// Route `log::*` to the browser console, once per module instance.
fn init_logging(level: Option<&str>) {
    use std::sync::Once;
    static ONCE: Once = Once::new();
    ONCE.call_once(|| {
        let level = match level.unwrap_or("warn") {
            "off" => return,
            "error" => log::Level::Error,
            "info" => log::Level::Info,
            "debug" => log::Level::Debug,
            "trace" => log::Level::Trace,
            _ => log::Level::Warn,
        };
        let _ = console_log::init_with_level(level);
    });
}
