//! The `Reactor` orchestrator: ties the coordinator client, the signaling
//! client and the host's WebRTC engine together into the session state
//! machine shared by all Reactor SDKs.
//!
//! Threading model: all state lives behind a non-async `Mutex` (locks are
//! never held across `await`s), so methods can be called from any task and
//! the host can pump [`PeerEvent`]s concurrently with an in-flight
//! `connect()`.

use std::collections::{BTreeMap, HashSet};
use std::sync::Mutex;
use std::time::Duration;

use futures::channel::oneshot;
use serde_json::{json, Value};

use crate::backoff::PollConfig;
use crate::control::ControlCorrelator;
use crate::coordinator::{CoordinatorClient, CoordinatorConfig};
use crate::error::{codes, Component, CoreError, ReactorError};
use crate::events::{Dispatcher, ReactorEvent};
use crate::messaging::{encode_command, parse_incoming, IncomingMessage};
use crate::peer::{PeerConnectionState, PeerEvent};
use crate::protocol::envelope::MessageScope;
use crate::protocol::recording::message_type;
use crate::protocol::session::{
    Capabilities, ClientInfo, ModelConfig, TrackCapability, TrackDirection,
};
use crate::protocol::upload::{CreateUploadRequest, FileRef};
use crate::protocol::webrtc::{IceCandidate, TrackMappingEntry};
use crate::recording::{Clip, RecordingCorrelator};
use crate::runtime::timeout;
use crate::signaling::WebRtcSignaling;
use crate::state::ReactorStatus;
use crate::{SharedAuth, SharedHttp, SharedPeer, SharedPlatform};

/// Host-supplied platform implementations.
pub struct ReactorDeps {
    pub http: SharedHttp,
    pub auth: SharedAuth,
    pub platform: SharedPlatform,
    pub peer: SharedPeer,
}

/// Static configuration of a [`Reactor`].
#[derive(Debug, Clone)]
pub struct ReactorOptions {
    /// Coordinator base URL, e.g. `https://api.reactor.inc`.
    pub api_url: String,
    pub model_name: String,
    /// Reported in `client_info`; bindings set their own SDK version/type.
    pub sdk_version: String,
    pub sdk_type: String,
    /// Resume all recvonly tracks once connected (default true).
    pub auto_resume_tracks: bool,
    /// Free-form model arguments forwarded on session creation.
    pub extra_args: Option<Value>,
    /// How long to wait for the transport readiness gate (peer connected +
    /// both channels open) after applying the SDP answer.
    pub ready_timeout: Duration,
    pub control_request_timeout: Duration,
    pub clip_request_timeout: Duration,
    pub session_poll: PollConfig,
    pub sdp_poll: PollConfig,
    /// When true, use the local HTTP runtime API (`/start_session` etc.)
    /// instead of the cloud coordinator API.
    pub local: bool,
}

impl ReactorOptions {
    pub fn new(api_url: impl Into<String>, model_name: impl Into<String>) -> Self {
        Self {
            api_url: api_url.into(),
            model_name: model_name.into(),
            sdk_version: crate::CORE_VERSION.to_string(),
            sdk_type: crate::DEFAULT_SDK_TYPE.to_string(),
            auto_resume_tracks: true,
            extra_args: None,
            ready_timeout: Duration::from_secs(30),
            control_request_timeout: Duration::from_secs(10),
            clip_request_timeout: Duration::from_secs(10),
            session_poll: PollConfig::session(),
            sdp_poll: PollConfig::sdp(),
            local: false,
        }
    }
}

/// Per-connect options.
#[derive(Debug, Clone, Default)]
pub struct ConnectOptions {
    /// Adopt an existing (backend-created) session instead of creating one.
    /// Adopters never terminate the session on disconnect.
    pub session_id: Option<String>,
    /// Use a pre-registered WebRTC connection id instead of registering one.
    pub connection_id: Option<u32>,
}

#[derive(Default)]
struct State {
    status: ReactorStatus,
    session_id: Option<String>,
    created_session: bool,
    connection_id: Option<u32>,
    capabilities: Option<Capabilities>,
    tracks: Vec<TrackCapability>,
    track_mapping: Vec<TrackMappingEntry>,
    last_error: Option<ReactorError>,
    peer_connected: bool,
    data_open: bool,
    control_open: bool,
    ready_gate: Option<oneshot::Sender<()>>,
    ice_buffer: Vec<IceCandidate>,
    ice_gathering_complete: bool,
    ice_final_sent: bool,
    ice_ready: bool,
    paused_tracks: HashSet<String>,
    closing: bool,
}

/// The Reactor client core. See the crate docs for the host contract.
pub struct Reactor {
    coordinator: CoordinatorClient,
    http: SharedHttp,
    auth: SharedAuth,
    platform: SharedPlatform,
    peer: SharedPeer,
    options: ReactorOptions,
    dispatcher: Dispatcher,
    control: ControlCorrelator,
    recording: RecordingCorrelator,
    state: Mutex<State>,
}

impl Reactor {
    pub fn new(deps: ReactorDeps, options: ReactorOptions) -> Self {
        let client_info = ClientInfo {
            sdk_version: options.sdk_version.clone(),
            sdk_type: options.sdk_type.clone(),
        };
        let coordinator = CoordinatorClient::new(
            deps.http.clone(),
            deps.auth.clone(),
            deps.platform.clone(),
            CoordinatorConfig {
                api_url: options.api_url.clone(),
                model: ModelConfig {
                    name: options.model_name.clone(),
                    version: None,
                },
                client_info,
                extra_args: options.extra_args.clone(),
                poll: options.session_poll,
                local: options.local,
            },
        );
        Self {
            coordinator,
            http: deps.http,
            auth: deps.auth,
            platform: deps.platform,
            peer: deps.peer,
            options,
            dispatcher: Dispatcher::new(),
            control: ControlCorrelator::new(),
            recording: RecordingCorrelator::new(),
            state: Mutex::new(State::default()),
        }
    }

    // ------------------------------------------------------------------
    // Introspection
    // ------------------------------------------------------------------

    pub fn status(&self) -> ReactorStatus {
        self.state.lock().unwrap().status
    }

    pub fn session_id(&self) -> Option<String> {
        self.state.lock().unwrap().session_id.clone()
    }

    pub fn capabilities(&self) -> Option<Capabilities> {
        self.state.lock().unwrap().capabilities.clone()
    }

    pub fn last_error(&self) -> Option<ReactorError> {
        self.state.lock().unwrap().last_error.clone()
    }

    pub fn track_mapping(&self) -> Vec<TrackMappingEntry> {
        self.state.lock().unwrap().track_mapping.clone()
    }

    pub fn subscribe(&self) -> futures::channel::mpsc::UnboundedReceiver<ReactorEvent> {
        self.dispatcher.subscribe()
    }

    // ------------------------------------------------------------------
    // Connection lifecycle
    // ------------------------------------------------------------------

    pub async fn connect(&self, connect_options: ConnectOptions) -> Result<(), CoreError> {
        {
            let mut state = self.state.lock().unwrap();
            if state.status != ReactorStatus::Disconnected {
                return Err(CoreError::InvalidState(format!(
                    "connect() while status is {}",
                    state.status
                )));
            }
            state.closing = false;
        }
        self.set_status(ReactorStatus::Connecting);

        match self.connect_inner(connect_options).await {
            Ok(()) => Ok(()),
            Err(error) => {
                self.emit_error(
                    codes::CONNECTION_FAILED,
                    error.to_string(),
                    Component::Api,
                    true,
                    None,
                );
                self.teardown(false, true).await;
                Err(error)
            }
        }
    }

    async fn connect_inner(&self, connect_options: ConnectOptions) -> Result<(), CoreError> {
        let session_id = match &connect_options.session_id {
            Some(id) => {
                let mut state = self.state.lock().unwrap();
                state.session_id = Some(id.clone());
                state.created_session = false;
                id.clone()
            }
            None => {
                let created = self.coordinator.create_session().await?;
                let mut state = self.state.lock().unwrap();
                state.session_id = Some(created.session_id.clone());
                state.created_session = true;
                created.session_id
            }
        };
        self.dispatcher
            .dispatch(ReactorEvent::SessionIdChanged(Some(session_id.clone())));
        self.set_status(ReactorStatus::Waiting);

        let session = self.coordinator.poll_session_ready(&session_id).await?;
        let capabilities = session
            .capabilities
            .ok_or_else(|| CoreError::Decode("ready session missing capabilities".into()))?;
        {
            let mut state = self.state.lock().unwrap();
            state.tracks = capabilities.tracks.clone();
            state.capabilities = Some(capabilities.clone());
        }
        self.dispatcher
            .dispatch(ReactorEvent::CapabilitiesReceived(capabilities));

        self.establish_transport(&session_id, false, connect_options.connection_id)
            .await?;

        self.set_status(ReactorStatus::Ready);
        if self.options.auto_resume_tracks {
            self.auto_resume_recv_tracks().await;
        }
        Ok(())
    }

    pub async fn reconnect(&self) -> Result<(), CoreError> {
        let session_id = {
            let state = self.state.lock().unwrap();
            if state.status == ReactorStatus::Ready {
                return Err(CoreError::InvalidState("reconnect() while ready".into()));
            }
            state
                .session_id
                .clone()
                .ok_or_else(|| CoreError::InvalidState("reconnect() without a session".into()))?
        };
        self.set_status(ReactorStatus::Connecting);
        {
            let mut state = self.state.lock().unwrap();
            state.closing = false;
            state.peer_connected = false;
            state.data_open = false;
            state.control_open = false;
            state.ice_buffer.clear();
            state.ice_gathering_complete = false;
            state.ice_final_sent = false;
            state.ice_ready = false;
        }
        self.set_status(ReactorStatus::Waiting);

        match self.establish_transport(&session_id, true, None).await {
            Ok(()) => {
                self.set_status(ReactorStatus::Ready);
                if self.options.auto_resume_tracks {
                    self.auto_resume_recv_tracks().await;
                }
                Ok(())
            }
            Err(error) => {
                self.emit_error(
                    codes::RECONNECTION_FAILED,
                    error.to_string(),
                    Component::Gpu,
                    true,
                    None,
                );
                self.teardown(true, true).await;
                Err(error)
            }
        }
    }

    pub async fn disconnect(&self, recoverable: bool) -> Result<(), CoreError> {
        self.teardown(recoverable, false).await;
        Ok(())
    }

    async fn establish_transport(
        &self,
        session_id: &str,
        reconnect: bool,
        adopt_connection_id: Option<u32>,
    ) -> Result<(), CoreError> {
        let signaling = self.signaling(session_id);

        let ice_servers = signaling.ice_servers().await?;
        let tracks = self.state.lock().unwrap().tracks.clone();

        let gate_receiver = {
            let mut state = self.state.lock().unwrap();
            let (tx, rx) = oneshot::channel();
            state.ready_gate = Some(tx);
            Self::check_ready_locked(&mut state);
            rx
        };

        let prepared = self.peer.prepare(&ice_servers, &tracks).await?;
        {
            let mut state = self.state.lock().unwrap();
            state.track_mapping = prepared.track_mapping.clone();
        }

        let existing_connection_id = self.state.lock().unwrap().connection_id;
        let connection_id = match (adopt_connection_id, reconnect, existing_connection_id) {
            (Some(adopted), _, _) => adopted,
            (None, true, Some(existing)) => existing,
            _ => signaling.register_connection().await?,
        };
        let replace_sdp =
            reconnect && adopt_connection_id.is_none() && existing_connection_id.is_some();
        {
            let mut state = self.state.lock().unwrap();
            state.connection_id = Some(connection_id);
            state.ice_ready = true;
        }
        self.flush_ice(&signaling).await;

        signaling
            .send_sdp_offer(
                connection_id,
                &prepared.sdp_offer,
                &prepared.track_mapping,
                replace_sdp,
            )
            .await?;
        let answer = signaling.poll_sdp_answer(connection_id).await?;
        if let Some(reassigned) = answer.connection_id {
            self.state.lock().unwrap().connection_id = Some(reassigned);
        }
        self.peer.set_remote_description(&answer.sdp_answer).await?;

        match timeout(
            &self.platform,
            self.options.ready_timeout,
            "transport readiness",
            gate_receiver,
        )
        .await?
        {
            Ok(()) => Ok(()),
            Err(_cancelled) => Err(CoreError::Aborted),
        }
    }

    async fn teardown(&self, recoverable: bool, already_failing: bool) {
        let (terminate_session, session_id) = {
            let mut state = self.state.lock().unwrap();
            state.closing = true;
            state.ready_gate = None;
            let terminate = !recoverable && state.created_session;
            (terminate, state.session_id.clone())
        };

        self.control.fail_all("disconnected");
        self.recording.fail_all("disconnected");

        if let Err(error) = self.peer.close().await {
            log::warn!("peer close failed: {error}");
        }

        if terminate_session {
            if let Some(id) = &session_id {
                if let Err(error) = self.coordinator.terminate_session(id).await {
                    log::warn!("session termination failed: {error}");
                }
            }
        }

        let session_cleared = {
            let mut state = self.state.lock().unwrap();
            state.peer_connected = false;
            state.data_open = false;
            state.control_open = false;
            state.ice_buffer.clear();
            state.ice_gathering_complete = false;
            state.ice_final_sent = false;
            state.ice_ready = false;
            state.track_mapping.clear();
            state.paused_tracks.clear();
            if recoverable {
                false
            } else {
                state.session_id = None;
                state.created_session = false;
                state.connection_id = None;
                state.capabilities = None;
                state.tracks.clear();
                true
            }
        };

        self.set_status(ReactorStatus::Disconnected);
        if session_cleared && !already_failing {
            self.dispatcher
                .dispatch(ReactorEvent::SessionIdChanged(None));
        }
    }

    // ------------------------------------------------------------------
    // Peer event intake
    // ------------------------------------------------------------------

    pub async fn handle_peer_event(&self, event: PeerEvent) {
        match event {
            PeerEvent::ConnectionStateChanged(connection_state) => {
                self.on_peer_connection_state(connection_state).await;
            }
            PeerEvent::DataChannelOpen => {
                let mut state = self.state.lock().unwrap();
                state.data_open = true;
                Self::check_ready_locked(&mut state);
            }
            PeerEvent::ControlChannelOpen => {
                let mut state = self.state.lock().unwrap();
                state.control_open = true;
                Self::check_ready_locked(&mut state);
            }
            PeerEvent::DataChannelMessage(raw) => self.on_data_message(&raw),
            PeerEvent::ControlChannelMessage(raw) => {
                self.control.handle_message(&raw);
            }
            PeerEvent::TrackReceived { name, mid } => {
                let resolved = name.or_else(|| {
                    let state = self.state.lock().unwrap();
                    mid.as_ref().and_then(|m| {
                        state
                            .track_mapping
                            .iter()
                            .find(|entry| &entry.mid == m)
                            .map(|entry| entry.name.clone())
                    })
                });
                match resolved {
                    Some(name) => {
                        self.dispatcher
                            .dispatch(ReactorEvent::TrackReceived { name, mid });
                    }
                    None => log::warn!("received track with unknown mid {mid:?}"),
                }
            }
            PeerEvent::IceCandidate(candidate) => {
                let ready = {
                    let mut state = self.state.lock().unwrap();
                    state.ice_buffer.push(candidate);
                    state.ice_ready
                };
                if ready {
                    self.flush_ice_current_session().await;
                }
            }
            PeerEvent::IceGatheringComplete => {
                let ready = {
                    let mut state = self.state.lock().unwrap();
                    state.ice_gathering_complete = true;
                    state.ice_ready
                };
                if ready {
                    self.flush_ice_current_session().await;
                }
            }
        }
    }

    async fn on_peer_connection_state(&self, connection_state: PeerConnectionState) {
        match connection_state {
            PeerConnectionState::Connected => {
                let mut state = self.state.lock().unwrap();
                state.peer_connected = true;
                Self::check_ready_locked(&mut state);
            }
            PeerConnectionState::Failed
            | PeerConnectionState::Disconnected
            | PeerConnectionState::Closed => {
                let should_report = {
                    let state = self.state.lock().unwrap();
                    !state.closing && state.status != ReactorStatus::Disconnected
                };
                if should_report {
                    self.emit_error(
                        codes::GPU_CONNECTION_ERROR,
                        format!("peer connection state: {connection_state:?}"),
                        Component::Gpu,
                        true,
                        None,
                    );
                    self.teardown(true, true).await;
                }
            }
            PeerConnectionState::New | PeerConnectionState::Connecting => {}
        }
    }

    fn on_data_message(&self, raw: &str) {
        match parse_incoming(raw) {
            IncomingMessage::Application(value) => {
                self.dispatcher.dispatch(ReactorEvent::Message(value));
            }
            IncomingMessage::Runtime(value) => {
                self.recording
                    .handle_runtime_message(&value, self.coordinator.api_url());
                self.dispatcher
                    .dispatch(ReactorEvent::RuntimeMessage(value));
            }
        }
    }

    fn check_ready_locked(state: &mut State) {
        if state.peer_connected && state.data_open && state.control_open {
            if let Some(gate) = state.ready_gate.take() {
                let _ = gate.send(());
            }
        }
    }

    // ------------------------------------------------------------------
    // Messaging
    // ------------------------------------------------------------------

    pub fn send_command(
        &self,
        command: &str,
        data: Value,
        scope: MessageScope,
    ) -> Result<(), CoreError> {
        self.send_command_with_uploads(command, data, scope, None)
    }

    pub fn send_command_with_uploads(
        &self,
        command: &str,
        data: Value,
        scope: MessageScope,
        uploads: Option<BTreeMap<String, FileRef>>,
    ) -> Result<(), CoreError> {
        {
            let state = self.state.lock().unwrap();
            if state.status != ReactorStatus::Ready {
                let error = CoreError::InvalidState(format!(
                    "cannot send command while status is {}",
                    state.status
                ));
                drop(state);
                self.emit_error(
                    codes::NOT_READY,
                    error.to_string(),
                    Component::Api,
                    false,
                    None,
                );
                return Err(error);
            }
        }
        let payload = encode_command(command, data, scope, uploads, self.peer.max_message_bytes())?;
        self.peer.send_data(&payload).inspect_err(|error| {
            self.emit_error(
                codes::MESSAGE_SEND_FAILED,
                error.to_string(),
                Component::Gpu,
                false,
                None,
            );
        })
    }

    pub fn ping(&self) -> Result<(), CoreError> {
        self.send_command(message_type::PING, json!({}), MessageScope::Runtime)
    }

    // ------------------------------------------------------------------
    // Track control
    // ------------------------------------------------------------------

    pub async fn publish_track(&self, name: &str) -> Result<(), CoreError> {
        self.ensure_ready()?;
        self.control_request("publish_track", json!({ "name": name }))
            .await
            .inspect_err(|error| {
                self.emit_error(
                    codes::TRACK_PUBLISH_FAILED,
                    error.to_string(),
                    Component::Gpu,
                    false,
                    None,
                );
            })
    }

    pub fn unpublish_track(&self, name: &str) -> Result<(), CoreError> {
        self.ensure_ready()?;
        let payload = ControlCorrelator::notification("unpublish_track", json!({ "name": name }));
        self.peer.send_control(&payload)
    }

    pub fn push_video_frame(&self, track_name: &str, data: &[u8], width: u32, height: u32) {
        self.peer.push_video_frame(track_name, data, width, height);
    }

    pub fn push_audio_frame(&self, track_name: &str, data: &[i16]) {
        self.peer.push_audio_frame(track_name, data);
    }

    pub async fn pause_track(&self, name: &str) -> Result<(), CoreError> {
        self.ensure_ready()?;
        self.peer.set_track_direction(name, false).await?;
        let payload = ControlCorrelator::notification("pause_track", json!({ "name": name }));
        self.peer.send_control(&payload)?;
        self.state
            .lock()
            .unwrap()
            .paused_tracks
            .insert(name.to_string());
        Ok(())
    }

    pub async fn resume_track(&self, name: &str) -> Result<(), CoreError> {
        self.ensure_ready()?;
        self.peer.set_track_direction(name, true).await?;
        let payload = ControlCorrelator::notification("resume_track", json!({ "name": name }));
        self.peer.send_control(&payload)?;
        self.state.lock().unwrap().paused_tracks.remove(name);
        Ok(())
    }

    pub fn paused_tracks(&self) -> HashSet<String> {
        self.state.lock().unwrap().paused_tracks.clone()
    }

    async fn auto_resume_recv_tracks(&self) {
        let recv_tracks: Vec<String> = {
            let state = self.state.lock().unwrap();
            state
                .tracks
                .iter()
                .filter(|t| t.direction == TrackDirection::Recvonly)
                .map(|t| t.name.clone())
                .collect()
        };
        for name in recv_tracks {
            if let Err(error) = self.resume_track(&name).await {
                log::warn!("auto-resume of track '{name}' failed: {error}");
            }
        }
    }

    async fn control_request(&self, method: &str, data: Value) -> Result<(), CoreError> {
        let pending = self.control.begin(method, data);
        if let Err(error) = self.peer.send_control(&pending.payload) {
            self.control.cancel(&pending.request_id);
            return Err(error);
        }
        match timeout(
            &self.platform,
            self.options.control_request_timeout,
            method,
            pending.receiver,
        )
        .await
        {
            Ok(Ok(result)) => result,
            Ok(Err(_cancelled)) => Err(CoreError::Aborted),
            Err(timeout_error) => {
                self.control.cancel(&pending.request_id);
                Err(timeout_error)
            }
        }
    }

    // ------------------------------------------------------------------
    // Recording
    // ------------------------------------------------------------------

    pub async fn request_clip(&self, duration_seconds: f64) -> Result<Clip, CoreError> {
        if !duration_seconds.is_finite() || duration_seconds <= 0.0 {
            return Err(CoreError::Recording {
                code: codes::INVALID_DURATION.to_string(),
                message: "duration_seconds must be a positive finite number".to_string(),
            });
        }
        self.dispatch_clip_request(
            message_type::REQUEST_CLIP,
            json!({ "duration_seconds": duration_seconds }),
        )
        .await
    }

    pub async fn request_recording(&self) -> Result<Clip, CoreError> {
        self.dispatch_clip_request(message_type::REQUEST_RECORDING, json!({}))
            .await
    }

    async fn dispatch_clip_request(&self, kind: &str, data: Value) -> Result<Clip, CoreError> {
        if self.status() != ReactorStatus::Ready {
            return Err(CoreError::Recording {
                code: codes::DISCONNECTED.to_string(),
                message: format!("cannot request clip while status is {}", self.status()),
            });
        }
        let (ticket, receiver) = self.recording.begin();
        if let Err(error) = self.send_command(kind, data, MessageScope::Runtime) {
            self.recording.cancel(ticket);
            return Err(error);
        }
        match timeout(
            &self.platform,
            self.options.clip_request_timeout,
            kind,
            receiver,
        )
        .await
        {
            Ok(Ok(result)) => result,
            Ok(Err(_cancelled)) => Err(CoreError::Aborted),
            Err(_) => {
                self.recording.cancel(ticket);
                Err(CoreError::Recording {
                    code: codes::REQUEST_TIMEOUT.to_string(),
                    message: "clip request timed out".to_string(),
                })
            }
        }
    }

    // ------------------------------------------------------------------
    // Uploads
    // ------------------------------------------------------------------

    pub async fn upload_file(
        &self,
        name: &str,
        mime_type: &str,
        bytes: Vec<u8>,
    ) -> Result<FileRef, CoreError> {
        let session_id = self
            .session_id()
            .ok_or_else(|| CoreError::InvalidState("upload_file() without a session".into()))?;
        let size = bytes.len() as u64;
        let upload = self
            .coordinator
            .create_upload(
                &session_id,
                &CreateUploadRequest {
                    name: name.to_string(),
                    size,
                    mime_type: mime_type.to_string(),
                },
            )
            .await?;

        let response = self
            .http
            .request(crate::http::HttpRequest {
                method: crate::http::Method::Put,
                url: upload.presigned_url.clone(),
                headers: vec![("Content-Type".to_string(), mime_type.to_string())],
                body: Some(bytes),
            })
            .await?;
        crate::http::check_status(&response, "presigned upload")?;

        let file_ref = FileRef {
            upload_id: upload.presigned_id,
            name: name.to_string(),
            mime_type: mime_type.to_string(),
            size,
        };

        if self.status() == ReactorStatus::Ready {
            let _ = self.send_command(
                message_type::FILE_UPLOADED,
                serde_json::to_value(&file_ref).map_err(CoreError::decode)?,
                MessageScope::Runtime,
            );
        }
        Ok(file_ref)
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    fn signaling(&self, session_id: &str) -> WebRtcSignaling {
        WebRtcSignaling::new(
            self.http.clone(),
            self.auth.clone(),
            self.platform.clone(),
            self.coordinator.transport_base_url(session_id),
            self.coordinator.client_info().clone(),
            self.options.sdp_poll,
        )
    }

    async fn flush_ice_current_session(&self) {
        let session_id = match self.session_id() {
            Some(id) => id,
            None => return,
        };
        let signaling = self.signaling(&session_id);
        self.flush_ice(&signaling).await;
    }

    async fn flush_ice(&self, signaling: &WebRtcSignaling) {
        let (candidates, is_final, connection_id) = {
            let mut state = self.state.lock().unwrap();
            let Some(connection_id) = state.connection_id else {
                return;
            };
            if !state.ice_ready {
                return;
            }
            let candidates = std::mem::take(&mut state.ice_buffer);
            let is_final = state.ice_gathering_complete && !state.ice_final_sent;
            if candidates.is_empty() && !is_final {
                return;
            }
            if is_final {
                state.ice_final_sent = true;
            }
            (candidates, is_final, connection_id)
        };
        if let Err(error) = signaling
            .send_ice_candidates(connection_id, &candidates, is_final)
            .await
        {
            log::warn!("failed to send ICE candidates: {error}");
        }
    }

    fn ensure_ready(&self) -> Result<(), CoreError> {
        let status = self.status();
        if status != ReactorStatus::Ready {
            return Err(CoreError::InvalidState(format!(
                "operation requires ready status, currently {status}"
            )));
        }
        Ok(())
    }

    fn set_status(&self, status: ReactorStatus) {
        let changed = {
            let mut state = self.state.lock().unwrap();
            if state.status == status {
                false
            } else {
                state.status = status;
                true
            }
        };
        if changed {
            self.dispatcher
                .dispatch(ReactorEvent::StatusChanged(status));
        }
    }

    fn emit_error(
        &self,
        code: &str,
        message: String,
        component: Component,
        recoverable: bool,
        retry_after_ms: Option<f64>,
    ) {
        let error = ReactorError {
            code: code.to_string(),
            message,
            timestamp_ms: self.platform.now_ms(),
            recoverable,
            component,
            retry_after_ms,
        };
        self.state.lock().unwrap().last_error = Some(error.clone());
        self.dispatcher.dispatch(ReactorEvent::Error(error));
    }
}
