//! The `Reactor` orchestrator: ties the coordinator client, the signaling
//! client and the host's WebRTC engine together into the session state
//! machine shared by all Reactor SDKs.
//!
//! Threading model: all state lives behind a non-async `std::sync::Mutex`
//! (locks are **never** held across `await`s).  The guard check and the
//! corresponding status update always happen in the **same** lock
//! acquisition so that two concurrent callers cannot both pass the guard.

use std::collections::{BTreeMap, HashSet};
use std::sync::Mutex;
use std::time::Duration;

use futures::channel::oneshot;
use serde_json::{json, Value};

use crate::backoff::PollConfig;
use crate::control::ControlCorrelator;
use crate::coordinator::{CoordinatorClient, CoordinatorConfig};
use crate::data::DataCorrelator;
use crate::error::{codes, CoreError, ErrorDetails, ReactorError};
use crate::events::{Dispatcher, ReactorEvent};
use crate::messaging::build_command_payload;
use crate::peer::{PeerConnectionState, PeerEvent, PreparedOffer};
use crate::protocol::recording::message_type;
use crate::protocol::session::{
    Capabilities, ClientInfo, ModelConfig, SessionResponse, TrackCapability, TrackDirection,
};
use crate::protocol::upload::{CreateUploadRequest, FileRef};
use crate::protocol::webrtc::{IceCandidate, TrackMappingEntry};
use crate::protocol::wire::struct_convert::struct_to_value;
use crate::protocol::wire::v1::control::control_client_message::Payload as ClientPayload;
use crate::protocol::wire::v1::control::control_server_message::Payload as ServerPayload;
use crate::protocol::wire::v1::data::{data_client_message, data_server_message};
use crate::protocol::wire::v1::platform::{
    FileUploaded, Ping, RequestClip, RequestRecording, RequestSchema,
};
use crate::protocol::wire::v1::track::{PauseTrack, PublishTrack, ResumeTrack, UnpublishTrack};
use crate::recording::{clip_failed_code, clip_from_ready, Clip};
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
    /// When the caller already knows the model's track list ahead of time,
    /// `connect()` runs `poll_session_ready()` concurrently with building the
    /// SDP offer instead of waiting for the poll to report capabilities
    /// first. If the coordinator's real `capabilities.tracks` disagrees with
    /// this once the poll resolves, `connect()` fails with
    /// [`CoreError::PresetTracksMismatch`] rather than sending a mismatched
    /// offer.
    pub preset_tracks: Option<Vec<TrackCapability>>,
    /// When true, use the local HTTP runtime API (`/start_session` etc.)
    /// instead of the cloud coordinator API.
    pub local: bool,
    /// How often to send a keep-alive ping while the session is ready.
    /// Must stay comfortably under the runtime's own liveness timeout
    /// (`reactor-runtime`'s default `ping_timeout` is 20s, polled every 2s)
    /// — a client that pings less often than the runtime's timeout gets
    /// disconnected between pings no matter when the first one goes out.
    /// Set to `Duration::ZERO` to disable the heartbeat.
    pub heartbeat_interval: Duration,
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
            preset_tracks: None,
            local: false,
            heartbeat_interval: Duration::from_secs(10),
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
    /// Override [`ReactorOptions::auto_resume_tracks`] for this connection.
    /// Sticky: a later `reconnect()` keeps whatever this connect decided,
    /// because a client that deliberately connected with its output tracks
    /// paused does not want them resumed behind its back on a reconnect.
    pub auto_resume_tracks: Option<bool>,
    /// Override the SDP-answer poll attempt limit for this connection (and the
    /// reconnects that follow it).
    pub max_sdp_attempts: Option<u32>,
}

#[derive(Default)]
struct State {
    status: ReactorStatus,
    session_id: Option<String>,
    /// The session resource as the coordinator last reported it: slim after
    /// creation, fully populated once the runtime accepted it. Kept so a
    /// binding can read the session's model, cluster and server info without
    /// re-fetching what connect() already had in its hands.
    session_info: Option<SessionResponse>,
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
    /// The error that triggered the teardown currently in progress, when it
    /// was `on_peer_connection_state` reacting to a `Failed`/`Disconnected`/
    /// `Closed` transport — as opposed to a caller-initiated `disconnect()`,
    /// which has no such cause. Reset alongside `closing` at the start of
    /// every `connect()`/`reconnect()`, so `finish_transport` never attributes
    /// a *previous* attempt's failure to the current one merely because
    /// `closing` is set again.
    teardown_cause: Option<ReactorError>,
    /// Incremented on every `connect()` / `reconnect()`.  Each `run_heartbeat`
    /// instance captures the epoch at spawn time and exits when it changes.
    heartbeat_epoch: u64,
    /// Effective values for the current connection: the [`ReactorOptions`]
    /// defaults unless the last `connect()` overrode them.
    auto_resume_tracks: bool,
    sdp_max_attempts: u32,
}

/// Order-independent equality for the `preset_tracks` fast path: whether `a`
/// and `b` name the same tracks, regardless of the order either side listed
/// them in.
fn tracks_match(a: &[TrackCapability], b: &[TrackCapability]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    // True multiset equality, not "every item in `a` is present somewhere in
    // `b`" — that weaker check would let a preset with a duplicate name (e.g.
    // `[A, A]`) match real tracks `[A, B]`, since both `A`s independently find
    // a match in `b` while `B` goes unrepresented.
    let mut remaining: Vec<&TrackCapability> = b.iter().collect();
    for track in a {
        match remaining.iter().position(|candidate| *candidate == track) {
            Some(index) => {
                remaining.swap_remove(index);
            }
            None => return false,
        }
    }
    true
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
    data: DataCorrelator,
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
            dispatcher: Dispatcher::new(),
            control: ControlCorrelator::new(),
            data: DataCorrelator::new(),
            state: Mutex::new(State {
                auto_resume_tracks: options.auto_resume_tracks,
                sdp_max_attempts: options.sdp_poll.max_attempts,
                ..State::default()
            }),
            options,
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

    /// The session resource from the coordinator, or `None` when disconnected.
    pub fn session_info(&self) -> Option<SessionResponse> {
        self.state.lock().unwrap().session_info.clone()
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
            // Atomically guard + update: both happen in the same lock acquisition
            // so that two concurrent connect() calls cannot both pass the check.
            state.closing = false;
            state.teardown_cause = None;
            state.status = ReactorStatus::Connecting;
            state.heartbeat_epoch = state.heartbeat_epoch.wrapping_add(1);
            if let Some(auto_resume) = connect_options.auto_resume_tracks {
                state.auto_resume_tracks = auto_resume;
            }
            if let Some(attempts) = connect_options.max_sdp_attempts {
                state.sdp_max_attempts = attempts;
            }
        }
        self.dispatcher
            .dispatch(ReactorEvent::StatusChanged(ReactorStatus::Connecting));

        match self.connect_inner(connect_options).await {
            Ok(()) => Ok(()),
            Err(error) => {
                self.emit_error(error.details(Some("connect")));
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
                let session_id = created.session_id.clone();
                state.session_info = Some(created);
                session_id
            }
        };
        self.dispatcher
            .dispatch(ReactorEvent::SessionIdChanged(Some(session_id.clone())));
        self.set_status(ReactorStatus::Waiting);

        let preset_tracks = self.options.preset_tracks.clone();
        if let Some(preset) = preset_tracks {
            // Parallel path: the caller already knows the model's tracks, so
            // build the SDP offer while the session-ready poll is still in
            // flight instead of waiting for it to report capabilities first.
            let signaling = self.signaling(&session_id);
            let (session_result, prepared_result) =
                futures::future::join(self.coordinator.poll_session_ready(&session_id), async {
                    let ice_servers = signaling.ice_servers().await?;
                    self.peer.prepare(&ice_servers, &preset).await
                })
                .await;
            let session = session_result?;
            let capabilities = session
                .capabilities
                .clone()
                .ok_or_else(|| CoreError::Decode("ready session missing capabilities".into()))?;

            if !tracks_match(&capabilities.tracks, &preset) {
                // The offer was built from the wrong track list — discard it
                // without ever sending it, rather than negotiating a session
                // the caller didn't ask for.
                if prepared_result.is_ok() {
                    let _ = self.peer.close().await;
                }
                return Err(CoreError::PresetTracksMismatch {
                    expected: preset,
                    actual: capabilities.tracks,
                });
            }
            let prepared = prepared_result?;

            {
                let mut state = self.state.lock().unwrap();
                state.tracks = capabilities.tracks.clone();
                state.capabilities = Some(capabilities.clone());
                state.session_info = Some(session);
            }
            self.dispatcher
                .dispatch(ReactorEvent::CapabilitiesReceived(capabilities));

            self.finish_transport(&session_id, false, connect_options.connection_id, prepared)
                .await?;
        } else {
            let session = self.coordinator.poll_session_ready(&session_id).await?;
            let capabilities = session
                .capabilities
                .clone()
                .ok_or_else(|| CoreError::Decode("ready session missing capabilities".into()))?;
            {
                let mut state = self.state.lock().unwrap();
                state.tracks = capabilities.tracks.clone();
                state.capabilities = Some(capabilities.clone());
                state.session_info = Some(session);
            }
            self.dispatcher
                .dispatch(ReactorEvent::CapabilitiesReceived(capabilities));

            self.establish_transport(&session_id, false, connect_options.connection_id)
                .await?;
        }

        self.set_status(ReactorStatus::Ready);
        if self.state.lock().unwrap().auto_resume_tracks {
            self.auto_resume_recv_tracks().await;
        }
        Ok(())
    }

    /// Reconnect using the existing session — tearing down the live connection
    /// first if there is one, without terminating the session server-side (the
    /// whole point of calling this instead of `disconnect()` then `connect()`).
    ///
    /// `max_sdp_attempts`, if given, overrides the SDP-answer poll attempt
    /// limit for this reconnect (and sticks for whatever follows, same as
    /// [`ConnectOptions::max_sdp_attempts`]) — otherwise the last `connect()`'s
    /// value carries over unchanged.
    ///
    /// Errors if there is no session to reconnect to at all — nothing has ever
    /// connected, or a previous `disconnect()` already terminated it.
    pub async fn reconnect(&self, max_sdp_attempts: Option<u32>) -> Result<(), CoreError> {
        let currently_ready = self.state.lock().unwrap().status == ReactorStatus::Ready;
        if currently_ready {
            // recoverable=true: keep the session this reconnect is about to reuse.
            // Unlike disconnect(), reconnect() is never the caller asking to end it.
            self.disconnect(true).await?;
        }

        let session_id = {
            let mut state = self.state.lock().unwrap();
            let sid = state
                .session_id
                .clone()
                .ok_or_else(|| CoreError::InvalidState("reconnect() without a session".into()))?;
            if let Some(attempts) = max_sdp_attempts {
                state.sdp_max_attempts = attempts;
            }
            // Reset transport state and bump epoch atomically with the guard check.
            state.closing = false;
            state.teardown_cause = None;
            state.peer_connected = false;
            state.data_open = false;
            state.control_open = false;
            state.ice_buffer.clear();
            state.ice_gathering_complete = false;
            state.ice_final_sent = false;
            state.ice_ready = false;
            state.status = ReactorStatus::Connecting;
            state.heartbeat_epoch = state.heartbeat_epoch.wrapping_add(1);
            sid
        };
        self.dispatcher
            .dispatch(ReactorEvent::StatusChanged(ReactorStatus::Connecting));
        self.set_status(ReactorStatus::Waiting);

        match self.establish_transport(&session_id, true, None).await {
            Ok(()) => {
                self.set_status(ReactorStatus::Ready);
                if self.state.lock().unwrap().auto_resume_tracks {
                    self.auto_resume_recv_tracks().await;
                }
                Ok(())
            }
            Err(error) => {
                self.emit_error(error.details(Some("reconnect")));
                self.teardown(true, true).await;
                Err(error)
            }
        }
    }

    /// Tear down the connection. `recoverable` is an internal knob — every public
    /// entry point but `reconnect()` calls this with `false`: a caller-initiated
    /// `disconnect()` terminates the session server-side (when this client created
    /// it; adopted sessions are the creator's to terminate, not this client's), no
    /// exceptions. `reconnect()` is the one caller that wants the session kept.
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
        let prepared = self.peer.prepare(&ice_servers, &tracks).await?;
        self.finish_transport(session_id, reconnect, adopt_connection_id, prepared)
            .await
    }

    /// Registers the connection and exchanges the SDP offer/answer for a peer
    /// connection already built by [`Self::establish_transport`]'s sequential
    /// path, or by `connect_inner`'s `preset_tracks` fast path, which builds
    /// it concurrently with the session-ready poll instead of calling this
    /// function's caller.
    async fn finish_transport(
        &self,
        session_id: &str,
        reconnect: bool,
        adopt_connection_id: Option<u32>,
        prepared: PreparedOffer,
    ) -> Result<(), CoreError> {
        let signaling = self.signaling(session_id);

        let gate_receiver = {
            let mut state = self.state.lock().unwrap();
            let (tx, rx) = oneshot::channel();
            state.ready_gate = Some(tx);
            Self::check_ready_locked(&mut state);
            rx
        };

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
        {
            let state = self.state.lock().unwrap();
            if state.closing {
                return Err(Self::closing_error(&state));
            }
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
        self.data.fail_all("disconnected");

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
                state.session_info = None;
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
            PeerEvent::ControlChannelMessage(raw) => self.on_control_message(&raw),
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
                    let error = self.emit_error(ErrorDetails::new(
                        codes::DISCONNECTED,
                        format!("peer connection state: {connection_state:?}"),
                        true,
                    ));
                    self.state.lock().unwrap().teardown_cause = Some(error);
                    self.teardown(true, true).await;
                }
            }
            PeerConnectionState::New | PeerConnectionState::Connecting => {}
        }
    }

    fn on_data_message(&self, raw: &[u8]) {
        let Some(handled) = self.data.handle_message(raw) else {
            return;
        };
        match handled.payload {
            None => {
                // A bodyless command acknowledgement — already delivered to
                // an awaiting send_command() call as `Ok(None)` if one was
                // pending; there is no message content to publish either way.
            }
            Some(data_server_message::Payload::Message(model_message)) => {
                let value = json!({
                    "type": model_message.r#type,
                    "data": model_message.data.map(struct_to_value).unwrap_or(Value::Null),
                });
                self.dispatcher.dispatch(ReactorEvent::Message(value));
            }
            Some(data_server_message::Payload::Error(error)) => {
                if handled.correlated {
                    // Already delivered to the awaiting send_command() call
                    // as a correlated CoreError::CommandRequest — raising it
                    // globally too would double-report the same failure.
                    return;
                }
                // No pending request matched this reply's request_id (e.g. it
                // arrived after a timeout already cancelled the correlation).
                // There is no awaiting caller left to deliver it to, so this
                // is the only signal callers get that it failed.
                self.emit_error(ErrorDetails::new(error.code, error.message, true));
            }
        }
    }

    /// Handle a decoded control-channel push. [`ControlCorrelator::handle_message`]
    /// already resolved any pending request this correlates to; this only
    /// covers responses/notifications the core additionally surfaces as a
    /// [`ReactorEvent::RuntimeMessage`] for host/app listeners — clip
    /// results (also returned directly from `request_clip`/
    /// `request_recording`) and unprompted pushes like moderation.
    fn on_control_message(&self, raw: &[u8]) {
        let Some(payload) = self.control.handle_message(raw) else {
            return;
        };
        match payload {
            ServerPayload::ClipReady(ready) => {
                let clip = clip_from_ready(ready, self.coordinator.api_url());
                self.dispatch_runtime_message(
                    message_type::CLIP_READY,
                    serde_json::to_value(&clip).unwrap_or(Value::Null),
                );
            }
            ServerPayload::ClipFailed(failed) => {
                self.dispatch_runtime_message(
                    message_type::CLIP_FAILED,
                    json!({ "reason": failed.reason }),
                );
            }
            ServerPayload::Moderation(m) => {
                self.dispatch_runtime_message(
                    message_type::MODERATION,
                    json!({
                        "action": m.action,
                        "input_kind": m.input_kind,
                        "command": m.command,
                        "categories": m.categories,
                        "message": m.message,
                    }),
                );
            }
            ServerPayload::ModelSchema(_)
            | ServerPayload::PublishTrack(_)
            | ServerPayload::Error(_) => {}
        }
    }

    fn dispatch_runtime_message(&self, kind: &str, data: Value) {
        self.dispatcher.dispatch(ReactorEvent::RuntimeMessage(
            json!({ "type": kind, "data": data }),
        ));
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

    /// Send an application command and wait for its correlated reply
    /// (`reactor_wire.v1` `MessageKind::Request`/`Response`). `None` means
    /// the handler ran and acknowledged the command but returned no message.
    pub async fn send_command(
        &self,
        command: &str,
        data: Value,
        uploads: Option<BTreeMap<String, FileRef>>,
    ) -> Result<Option<Value>, CoreError> {
        self.ensure_ready()?;
        let payload = build_command_payload(command, data, uploads)?;
        let response = self
            .data_request(command, payload, self.options.control_request_timeout)
            .await?;
        match response {
            None => Ok(None),
            Some(data_server_message::Payload::Message(model_message)) => Ok(Some(json!({
                "type": model_message.r#type,
                "data": model_message.data.map(struct_to_value).unwrap_or(Value::Null),
            }))),
            Some(data_server_message::Payload::Error(e)) => Err(CoreError::CommandRequest {
                command: command.to_string(),
                code: e.code,
                message: e.message,
            }),
        }
    }

    async fn data_request(
        &self,
        command: &str,
        payload: data_client_message::Payload,
        request_timeout: Duration,
    ) -> Result<Option<data_server_message::Payload>, CoreError> {
        let pending = self.data.begin(payload);
        let max_bytes = self.peer.max_message_bytes();
        if pending.payload.len() > max_bytes {
            self.data.cancel(&pending.request_id);
            return Err(CoreError::MessageTooLarge {
                size: pending.payload.len(),
                max: max_bytes,
            });
        }
        if let Err(error) = self.peer.send_data(&pending.payload, true) {
            self.data.cancel(&pending.request_id);
            self.emit_error(error.details(Some("send_command")));
            return Err(error);
        }
        match timeout(&self.platform, request_timeout, command, pending.receiver).await {
            Ok(Ok(result)) => result,
            Ok(Err(_cancelled)) => Err(CoreError::Aborted),
            Err(timeout_error) => {
                self.data.cancel(&pending.request_id);
                Err(timeout_error)
            }
        }
    }

    pub fn ping(&self) -> Result<(), CoreError> {
        self.peer
            .send_control(&ControlCorrelator::notification(ClientPayload::Ping(
                Ping {},
            )))
    }

    /// Request the model's command schema, delivered as an OpenAPI document.
    pub async fn request_schema(&self) -> Result<Value, CoreError> {
        self.ensure_ready()?;
        self.control_request(
            "request_schema",
            ClientPayload::RequestSchema(RequestSchema {}),
            self.options.control_request_timeout,
        )
        .await
        .and_then(|payload| match payload {
            ServerPayload::ModelSchema(schema) => {
                Ok(schema.openapi.map(struct_to_value).unwrap_or(Value::Null))
            }
            ServerPayload::Error(e) => Err(CoreError::ControlRequest {
                method: "request_schema".to_string(),
                code: e.code,
                message: e.message,
            }),
            _ => Err(CoreError::decode(
                "unexpected control response for request_schema",
            )),
        })
    }

    // ------------------------------------------------------------------
    // Track control
    // ------------------------------------------------------------------

    pub async fn publish_track(&self, name: &str) -> Result<(), CoreError> {
        self.ensure_ready()?;
        let payload = ClientPayload::PublishTrack(PublishTrack {
            name: name.to_string(),
        });
        self.control_request(
            "publish_track",
            payload,
            self.options.control_request_timeout,
        )
        .await
        .and_then(|payload| match payload {
            ServerPayload::PublishTrack(_) => Ok(()),
            ServerPayload::Error(e) => Err(CoreError::ControlRequest {
                method: "publish_track".to_string(),
                code: e.code,
                message: e.message,
            }),
            _ => Err(CoreError::decode(
                "unexpected control response for publish_track",
            )),
        })
        .inspect_err(|error| {
            self.emit_error(error.details(Some("publish_track")));
        })
    }

    pub fn unpublish_track(&self, name: &str) -> Result<(), CoreError> {
        self.ensure_ready()?;
        let payload =
            ControlCorrelator::notification(ClientPayload::UnpublishTrack(UnpublishTrack {
                name: name.to_string(),
            }));
        self.peer.send_control(&payload)
    }

    pub fn push_video_frame(&self, track_name: &str, data: &[u8], width: u32, height: u32) {
        self.peer.push_video_frame(track_name, data, width, height);
    }

    /// Push a frame tagged with `user_data`, which reaches the far end as the
    /// frame's metadata when both peers negotiated support for it.
    pub fn push_video_frame_with_metadata(
        &self,
        track_name: &str,
        data: &[u8],
        width: u32,
        height: u32,
        user_data: &[u8],
    ) {
        self.peer
            .push_video_frame_with_metadata(track_name, data, width, height, user_data);
    }

    /// Push a tagged frame stamped with `capture_time_us` (microseconds), the
    /// timestamp the far end reads as the frame's own rather than the one the
    /// transport would assign on push. Pass the same value for every track of one
    /// capture and they arrive as one moment; `user_data` may be empty.
    pub fn push_video_frame_with_metadata_at(
        &self,
        track_name: &str,
        data: &[u8],
        width: u32,
        height: u32,
        user_data: &[u8],
        capture_time_us: i64,
    ) {
        self.peer.push_video_frame_with_metadata_at(
            track_name,
            data,
            width,
            height,
            user_data,
            capture_time_us,
        );
    }

    pub fn push_audio_frame(&self, track_name: &str, data: &[i16]) {
        self.peer.push_audio_frame(track_name, data);
    }

    pub async fn pause_track(&self, name: &str) -> Result<(), CoreError> {
        self.ensure_ready()?;
        self.peer.set_track_direction(name, false).await?;
        let payload = ControlCorrelator::notification(ClientPayload::PauseTrack(PauseTrack {
            name: name.to_string(),
        }));
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
        let payload = ControlCorrelator::notification(ClientPayload::ResumeTrack(ResumeTrack {
            name: name.to_string(),
        }));
        self.peer.send_control(&payload)?;
        self.state.lock().unwrap().paused_tracks.remove(name);
        Ok(())
    }

    pub fn paused_tracks(&self) -> HashSet<String> {
        self.state.lock().unwrap().paused_tracks.clone()
    }

    /// The tracks the runtime declared for this session.
    ///
    /// Empty until the session is accepted and its capabilities arrive, and
    /// emptied again on disconnect — so a binding can read this instead of having
    /// to catch the capabilities event and hold onto it.
    pub fn tracks(&self) -> Vec<TrackCapability> {
        self.state.lock().unwrap().tracks.clone()
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

    /// Send a control-channel request and wait for its correlated response,
    /// returning the raw decoded `reactor_wire.v1` server payload for the
    /// caller to interpret (the possible variants differ per request kind).
    async fn control_request(
        &self,
        method: &'static str,
        payload: ClientPayload,
        request_timeout: Duration,
    ) -> Result<ServerPayload, CoreError> {
        let pending = self.control.begin(payload);
        if let Err(error) = self.peer.send_control(&pending.payload) {
            self.control.cancel(&pending.request_id);
            return Err(error);
        }
        match timeout(&self.platform, request_timeout, method, pending.receiver).await {
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
                code: codes::BAD_REQUEST.to_string(),
                message: "duration_seconds must be a positive finite number".to_string(),
            });
        }
        self.dispatch_clip_request(
            "requestClip",
            ClientPayload::RequestClip(RequestClip { duration_seconds }),
        )
        .await
    }

    pub async fn request_recording(&self) -> Result<Clip, CoreError> {
        self.dispatch_clip_request(
            "requestRecording",
            ClientPayload::RequestRecording(RequestRecording {}),
        )
        .await
    }

    async fn dispatch_clip_request(
        &self,
        method: &'static str,
        payload: ClientPayload,
    ) -> Result<Clip, CoreError> {
        if self.status() != ReactorStatus::Ready {
            return Err(CoreError::Recording {
                code: codes::INVALID_STATE.to_string(),
                message: format!("cannot request clip while status is {}", self.status()),
            });
        }
        let response = self
            .control_request(method, payload, self.options.clip_request_timeout)
            .await;
        match response {
            Ok(ServerPayload::ClipReady(ready)) => {
                Ok(clip_from_ready(ready, self.coordinator.api_url()))
            }
            Ok(ServerPayload::ClipFailed(failed)) => Err(CoreError::Recording {
                code: clip_failed_code(&failed.reason).to_string(),
                message: failed.reason,
            }),
            Ok(ServerPayload::Error(e)) => Err(CoreError::Recording {
                code: e.code,
                message: e.message,
            }),
            Ok(_) => Err(CoreError::decode(format!(
                "unexpected control response for {method}"
            ))),
            // A generic timeout still needs to surface as the documented
            // Recording/REQUEST_TIMEOUT code callers already match on.
            Err(CoreError::Timeout(_)) => Err(CoreError::Recording {
                code: codes::REQUEST_TIMEOUT.to_string(),
                message: "clip request timed out".to_string(),
            }),
            Err(error) => Err(error),
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
        self.ensure_ready()?;
        let session_id = self
            .session_id()
            .ok_or_else(|| CoreError::InvalidState("upload_file() without a session".into()))?;
        if bytes.is_empty() {
            return Err(CoreError::InvalidState(
                "upload_file() called with an empty file".into(),
            ));
        }
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
            let payload =
                ControlCorrelator::notification(ClientPayload::FileUploaded(FileUploaded {
                    upload_id: file_ref.upload_id.clone(),
                    name: file_ref.name.clone(),
                    mime_type: file_ref.mime_type.clone(),
                    size: file_ref.size as i64,
                }));
            let _ = self.peer.send_control(&payload);
        }
        Ok(file_ref)
    }

    // ------------------------------------------------------------------
    // Keep-alive
    // ------------------------------------------------------------------

    /// Run a periodic ping loop for the lifetime of the current connection.
    ///
    /// Callers (e.g. the FFI layer) spawn this as a background task after
    /// `connect()` or `reconnect()` returns `Ok`.  It exits automatically
    /// when the connection closes or a new `connect`/`reconnect` starts.
    ///
    /// Disabled when `ReactorOptions::heartbeat_interval` is zero.
    pub async fn run_heartbeat(&self) {
        let interval = self.options.heartbeat_interval;
        if interval.is_zero() {
            return;
        }
        let my_epoch = self.state.lock().unwrap().heartbeat_epoch;
        loop {
            let (current_epoch, ready) = {
                let state = self.state.lock().unwrap();
                let ready = !state.closing && state.status == ReactorStatus::Ready;
                (state.heartbeat_epoch, ready)
            };
            if current_epoch != my_epoch || !ready {
                break;
            }
            // Ping immediately on each pass, before sleeping — a runtime's
            // liveness timeout (e.g. 20s) can be shorter than `interval`
            // (default 30s), so waiting a full interval before the first
            // ping risks the runtime closing the connection first.
            if let Err(e) = self.ping() {
                log::warn!("[reactor] heartbeat ping failed: {e}");
            }
            self.platform.sleep(interval).await;
        }
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
            PollConfig {
                max_attempts: self.state.lock().unwrap().sdp_max_attempts,
                ..self.options.sdp_poll
            },
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

    /// The error to return when `finish_transport` finds `state.closing` already
    /// set before a call into the peer transport — a concurrent teardown beat
    /// it there. Uses `teardown_cause` rather than `last_error`: `last_error`
    /// persists across attempts, so a caller-initiated `disconnect()` racing in
    /// (which sets `closing` with no error of its own) could otherwise surface
    /// a stale, unrelated failure from an earlier connect/reconnect as if it
    /// were the cause of this one. `teardown_cause` is reset alongside
    /// `closing` at the start of every `connect()`/`reconnect()` and is only
    /// ever set by `on_peer_connection_state`'s own teardown, so it is either
    /// this negotiation's real cause or nothing.
    fn closing_error(state: &State) -> CoreError {
        match &state.teardown_cause {
            Some(error) => CoreError::Peer(error.details.message.clone()),
            None => CoreError::Peer("connection closed during negotiation".into()),
        }
    }

    /// Publish a failure on the event stream.
    ///
    /// Takes the details rather than picking a code, so the event and the failed
    /// call it came from report the same one. The call sites that have a
    /// `CoreError` pass `error.details(Some(operation))`; the two that do not —
    /// a transport that dropped on its own, and a code the platform sent
    /// unprompted — build theirs with `ErrorDetails::new`.
    ///
    /// Returns the `ReactorError` it published, so a caller that also needs to
    /// stash it elsewhere (`on_peer_connection_state` → `teardown_cause`)
    /// doesn't have to rebuild it and risk the two falling out of sync.
    fn emit_error(&self, details: ErrorDetails) -> ReactorError {
        let error = ReactorError {
            details,
            timestamp_ms: self.platform.now_ms(),
        };
        self.state.lock().unwrap().last_error = Some(error.clone());
        self.dispatcher.dispatch(ReactorEvent::Error(error.clone()));
        error
    }
}

// ── Unit tests ────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    use std::sync::Arc;

    use futures::StreamExt;

    use crate::http::{AuthProvider, HttpClient, HttpRequest, HttpResponse, Method};
    use crate::peer::{PeerTransport, PreparedOffer};
    use crate::protocol::session::TrackCapability;
    use crate::protocol::webrtc::IceServer;
    use crate::runtime::Platform;
    use crate::state::ReactorStatus;
    use crate::{BoxFut, SharedAuth, SharedHttp, SharedPeer, SharedPlatform};

    // ── Minimal mocks ─────────────────────────────────────────────────────────

    #[derive(Default)]
    struct TestPlatform;

    impl Platform for TestPlatform {
        fn sleep(&self, d: Duration) -> BoxFut<'static, ()> {
            Box::pin(tokio::time::sleep(d))
        }
        fn now_ms(&self) -> f64 {
            0.0
        }
    }

    struct NoAuth;

    #[async_trait::async_trait]
    impl AuthProvider for NoAuth {
        async fn jwt(&self) -> Result<Option<String>, CoreError> {
            Ok(None)
        }
    }

    /// HTTP mock that never resolves — simulates a slow coordinator.
    struct PendingHttp;

    #[async_trait::async_trait]
    impl HttpClient for PendingHttp {
        async fn request(&self, _req: HttpRequest) -> Result<HttpResponse, CoreError> {
            std::future::pending::<()>().await;
            unreachable!()
        }
    }

    /// Records every request it sees, answers a DELETE (session termination) with
    /// success, and fails anything else fast — a reconnect's later steps (ICE
    /// servers, signaling) do not need to actually succeed for these tests, only
    /// to not hang, so whether `terminate_session` was reached is observable
    /// without driving a whole connection to `Ready`.
    #[derive(Default)]
    struct RecordingHttp {
        requests: std::sync::Mutex<Vec<(Method, String)>>,
    }

    impl RecordingHttp {
        fn delete_count(&self) -> usize {
            self.requests
                .lock()
                .unwrap()
                .iter()
                .filter(|(m, _)| *m == Method::Delete)
                .count()
        }

        fn saw_any_request(&self) -> bool {
            !self.requests.lock().unwrap().is_empty()
        }
    }

    #[async_trait::async_trait]
    impl HttpClient for RecordingHttp {
        async fn request(&self, req: HttpRequest) -> Result<HttpResponse, CoreError> {
            self.requests
                .lock()
                .unwrap()
                .push((req.method, req.url.clone()));
            match req.method {
                Method::Delete => Ok(HttpResponse {
                    status: 204,
                    headers: vec![],
                    body: vec![],
                }),
                _ => Err(CoreError::Http("RecordingHttp: no route".into())),
            }
        }
    }

    struct NullPeer;

    #[async_trait::async_trait]
    impl PeerTransport for NullPeer {
        async fn prepare(
            &self,
            _: &[IceServer],
            _: &[TrackCapability],
        ) -> Result<PreparedOffer, CoreError> {
            Ok(PreparedOffer {
                sdp_offer: String::new(),
                track_mapping: vec![],
            })
        }
        async fn set_remote_description(&self, _: &str) -> Result<(), CoreError> {
            Ok(())
        }
        fn send_data(&self, _: &[u8], _: bool) -> Result<(), CoreError> {
            Ok(())
        }
        fn send_control(&self, _: &[u8]) -> Result<(), CoreError> {
            Ok(())
        }
        async fn set_track_direction(&self, _: &str, _: bool) -> Result<(), CoreError> {
            Ok(())
        }
        async fn close(&self) -> Result<(), CoreError> {
            Ok(())
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    fn make_reactor() -> Arc<Reactor> {
        make_reactor_opts(ReactorOptions::new("http://localhost", "test-model"))
    }

    fn make_reactor_heartbeat(interval: Duration) -> Arc<Reactor> {
        let mut opts = ReactorOptions::new("http://localhost", "test-model");
        opts.heartbeat_interval = interval;
        make_reactor_opts(opts)
    }

    fn make_reactor_opts(opts: ReactorOptions) -> Arc<Reactor> {
        Arc::new(Reactor::new(
            ReactorDeps {
                http: Arc::new(PendingHttp) as SharedHttp,
                auth: Arc::new(NoAuth) as SharedAuth,
                platform: Arc::new(TestPlatform) as SharedPlatform,
                peer: Arc::new(NullPeer) as SharedPeer,
            },
            opts,
        ))
    }

    fn make_reactor_with_http(http: Arc<RecordingHttp>) -> Arc<Reactor> {
        Arc::new(Reactor::new(
            ReactorDeps {
                http: http as SharedHttp,
                auth: Arc::new(NoAuth) as SharedAuth,
                platform: Arc::new(TestPlatform) as SharedPlatform,
                peer: Arc::new(NullPeer) as SharedPeer,
            },
            ReactorOptions::new("http://localhost", "test-model"),
        ))
    }

    /// Wait for the first `StatusChanged` event that matches `expected`.
    async fn wait_for_status(
        events: &mut futures::channel::mpsc::UnboundedReceiver<ReactorEvent>,
        expected: ReactorStatus,
    ) {
        let got = tokio::time::timeout(Duration::from_millis(500), async {
            while let Some(ev) = events.next().await {
                if let ReactorEvent::StatusChanged(s) = ev {
                    if s == expected {
                        return true;
                    }
                }
            }
            false
        })
        .await;
        assert!(
            matches!(got, Ok(true)),
            "timed out waiting for StatusChanged({expected:?})"
        );
    }

    // ── Race condition: connect() guard is atomic ─────────────────────────────

    /// The guard check (`status == Disconnected`) and the status update
    /// (`status = Connecting`) happen in the **same** lock acquisition.
    /// This test verifies that a second concurrent `connect()` is rejected
    /// because the first one already atomically set the status.
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn concurrent_connect_second_call_rejected() {
        let reactor = make_reactor();
        let mut events = reactor.subscribe();

        let r = reactor.clone();
        let h = tokio::spawn(async move {
            let _ = r.connect(ConnectOptions::default()).await;
        });

        // Wait until the Connecting event is dispatched (fired synchronously
        // after the status lock is released, before the first HTTP await).
        wait_for_status(&mut events, ReactorStatus::Connecting).await;

        let result = reactor.connect(ConnectOptions::default()).await;
        assert!(
            matches!(result, Err(CoreError::InvalidState(_))),
            "concurrent connect() must be rejected, got {result:?}"
        );

        h.abort();
    }

    /// connect() while already Waiting (mid-connect) must be rejected.
    #[tokio::test]
    async fn connect_while_waiting_is_rejected() {
        let reactor = make_reactor();
        reactor.state.lock().unwrap().status = ReactorStatus::Waiting;
        let result = reactor.connect(ConnectOptions::default()).await;
        assert!(matches!(result, Err(CoreError::InvalidState(_))));
    }

    /// connect() while Ready must be rejected.
    #[tokio::test]
    async fn connect_while_ready_is_rejected() {
        let reactor = make_reactor();
        reactor.state.lock().unwrap().status = ReactorStatus::Ready;
        let result = reactor.connect(ConnectOptions::default()).await;
        assert!(matches!(result, Err(CoreError::InvalidState(_))));
    }

    // ── disconnect() / reconnect() session lifecycle ────────────────────────────

    /// A plain disconnect() ends the session server-side when this client created
    /// it — not recoverable, unlike reconnect()'s internal teardown.
    #[tokio::test]
    async fn disconnect_terminates_a_session_this_client_created() {
        let http = Arc::new(RecordingHttp::default());
        let reactor = make_reactor_with_http(http.clone());
        {
            let mut state = reactor.state.lock().unwrap();
            state.session_id = Some("s1".to_string());
            state.created_session = true;
        }

        reactor.disconnect(false).await.unwrap();

        assert_eq!(http.delete_count(), 1);
    }

    /// An adopted session (connect(session_id=...)) is the creator's to
    /// terminate, not this client's — disconnect() must not attempt to.
    #[tokio::test]
    async fn disconnect_does_not_terminate_an_adopted_session() {
        let http = Arc::new(RecordingHttp::default());
        let reactor = make_reactor_with_http(http.clone());
        {
            let mut state = reactor.state.lock().unwrap();
            state.session_id = Some("s1".to_string());
            state.created_session = false;
        }

        reactor.disconnect(false).await.unwrap();

        assert_eq!(http.delete_count(), 0);
    }

    /// reconnect() from `ready` tears down the live connection first — but must
    /// not end the session server-side in the process, or there would be nothing
    /// left to reconnect to.
    #[tokio::test]
    async fn reconnect_from_ready_does_not_terminate_the_session() {
        let http = Arc::new(RecordingHttp::default());
        let reactor = make_reactor_with_http(http.clone());
        {
            let mut state = reactor.state.lock().unwrap();
            state.session_id = Some("s1".to_string());
            state.created_session = true;
            state.status = ReactorStatus::Ready;
        }

        // establish_transport can't actually succeed against RecordingHttp (no ICE
        // servers route) — irrelevant here, the assertion is about what happened
        // before that, not whether the reconnect attempt itself completes. It does
        // have to actually be reached, though: the old "reject while ready" guard
        // would also leave delete_count() at 0, for the wrong reason.
        let _ = reactor.reconnect(None).await;

        assert_eq!(http.delete_count(), 0);
        assert!(
            http.saw_any_request(),
            "reconnect() must tear down and proceed past `ready`, not reject outright"
        );
    }

    /// reconnect() with no session at all — never connected, or a previous
    /// disconnect() already ended it — has nothing to reconnect to.
    #[tokio::test]
    async fn reconnect_without_a_session_errors() {
        let reactor = make_reactor();
        let result = reactor.reconnect(None).await;
        assert!(matches!(result, Err(CoreError::InvalidState(_))));
    }

    /// A caller can override the SDP-poll attempt limit for a specific
    /// reconnect, same as `ConnectOptions::max_sdp_attempts` does for connect.
    #[tokio::test]
    async fn reconnect_overrides_sdp_max_attempts() {
        let http = Arc::new(RecordingHttp::default());
        let reactor = make_reactor_with_http(http.clone());
        {
            let mut state = reactor.state.lock().unwrap();
            state.session_id = Some("s1".to_string());
            state.created_session = false;
        }

        // RecordingHttp has no ICE-servers route, so the reconnect fails
        // immediately — after the override was taken, which is what matters.
        let _ = reactor.reconnect(Some(3)).await;

        assert_eq!(reactor.state.lock().unwrap().sdp_max_attempts, 3);
    }

    // ── Heartbeat ─────────────────────────────────────────────────────────────

    /// run_heartbeat() exits after one interval when status is Disconnected.
    #[tokio::test]
    async fn heartbeat_exits_when_disconnected() {
        let reactor = make_reactor_heartbeat(Duration::from_millis(20));

        let completed =
            tokio::time::timeout(Duration::from_millis(300), reactor.run_heartbeat()).await;

        assert!(
            completed.is_ok(),
            "heartbeat should exit within one interval when not Ready"
        );
    }

    /// A stale heartbeat exits when the epoch advances (new connect/reconnect).
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn heartbeat_exits_when_epoch_changes() {
        let reactor = make_reactor_heartbeat(Duration::from_millis(40));

        // Force Ready so the heartbeat would otherwise keep looping.
        reactor.state.lock().unwrap().status = ReactorStatus::Ready;

        let r = reactor.clone();
        let hb = tokio::spawn(async move { r.run_heartbeat().await });

        // Give the heartbeat a moment to start its first sleep.
        tokio::time::sleep(Duration::from_millis(5)).await;

        // Bump epoch — simulates a new connect() or reconnect().
        {
            let mut s = reactor.state.lock().unwrap();
            s.heartbeat_epoch = s.heartbeat_epoch.wrapping_add(1);
        }

        // Heartbeat wakes up after ~40 ms, sees the epoch mismatch, and exits.
        let result = tokio::time::timeout(Duration::from_millis(300), hb).await;
        assert!(result.is_ok(), "heartbeat should stop after epoch change");
    }

    // ── send_command ─────────────────────────────────────────────────

    use crate::protocol::wire::struct_convert::value_to_struct;
    use crate::protocol::wire::v1::common::MessageKind;
    use crate::protocol::wire::v1::data::DataServerMessage;
    use crate::protocol::wire::v1::model::ModelMessage;
    use prost::Message as _;

    fn encode_data_response(request_id: &str, payload: data_server_message::Payload) -> Vec<u8> {
        DataServerMessage {
            request_id: request_id.to_string(),
            kind: MessageKind::Response as i32,
            payload: Some(payload),
        }
        .encode_to_vec()
    }

    /// send_command() resolves once the correlated reply arrives —
    /// the DataCorrelator's first-ever request_id is deterministic ("data_1")
    /// on a freshly constructed Reactor, so the test can address it directly.
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn send_command_resolves_the_correlated_reply() {
        let reactor = make_reactor();
        reactor.state.lock().unwrap().status = ReactorStatus::Ready;

        let r = reactor.clone();
        let call = tokio::spawn(async move { r.send_command("get_state", json!({}), None).await });

        // Give the call a moment to register with the DataCorrelator.
        tokio::time::sleep(Duration::from_millis(5)).await;

        let bytes = encode_data_response(
            "data_1",
            data_server_message::Payload::Message(ModelMessage {
                r#type: "get_state_reply".into(),
                data: value_to_struct(json!({"brightness": 1.0})),
            }),
        );
        reactor.on_data_message(&bytes);

        let result = tokio::time::timeout(Duration::from_millis(300), call)
            .await
            .expect("send_command should resolve")
            .unwrap()
            .unwrap();
        assert_eq!(
            result,
            Some(json!({"type": "get_state_reply", "data": {"brightness": 1.0}}))
        );
    }

    /// A bodyless ack (a handler that returned no message) resolves
    /// `send_command()` to `Ok(None)` instead of hanging until the timeout.
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn send_command_resolves_a_bodyless_ack_to_none() {
        let reactor = make_reactor();
        reactor.state.lock().unwrap().status = ReactorStatus::Ready;

        let r = reactor.clone();
        let call = tokio::spawn(async move { r.send_command("set_paused", json!({}), None).await });

        tokio::time::sleep(Duration::from_millis(5)).await;

        let bytes = DataServerMessage {
            request_id: "data_1".to_string(),
            kind: MessageKind::Response as i32,
            payload: None,
        }
        .encode_to_vec();
        reactor.on_data_message(&bytes);

        let result = tokio::time::timeout(Duration::from_millis(300), call)
            .await
            .expect("send_command should resolve")
            .unwrap()
            .unwrap();
        assert_eq!(result, None);
    }

    /// A correlated `Error` reply surfaces as `CoreError::CommandRequest`,
    /// not just a generic error event.
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn send_command_surfaces_a_correlated_error() {
        let reactor = make_reactor();
        reactor.state.lock().unwrap().status = ReactorStatus::Ready;

        let r = reactor.clone();
        let call =
            tokio::spawn(async move { r.send_command("bad_command", json!({}), None).await });

        tokio::time::sleep(Duration::from_millis(5)).await;

        let bytes = encode_data_response(
            "data_1",
            data_server_message::Payload::Error(crate::protocol::wire::v1::common::Error {
                code: "BAD_COMMAND".into(),
                message: "unknown command".into(),
            }),
        );
        reactor.on_data_message(&bytes);

        let result = tokio::time::timeout(Duration::from_millis(300), call)
            .await
            .expect("send_command should resolve")
            .unwrap();
        match result {
            Err(CoreError::CommandRequest {
                command,
                code,
                message,
            }) => {
                assert_eq!(command, "bad_command");
                assert_eq!(code, "BAD_COMMAND");
                assert_eq!(message, "unknown command");
            }
            other => panic!("expected CommandRequest error, got {other:?}"),
        }
        // A correlated error must not *also* fire as a global error — the
        // caller already has it via the awaited Result.
        assert!(
            reactor.last_error().is_none(),
            "correlated error should not also surface as a global error"
        );
    }

    /// An *uncorrelated* error (empty/unknown request_id — e.g. arriving
    /// after a timeout already cancelled the correlation) has no awaiting
    /// caller to deliver it to, so it must still surface as a global error.
    #[test]
    fn uncorrelated_error_still_surfaces_globally() {
        let reactor = make_reactor();

        let bytes = encode_data_response(
            "",
            data_server_message::Payload::Error(crate::protocol::wire::v1::common::Error {
                code: "BAD_COMMAND".into(),
                message: "unknown command".into(),
            }),
        );
        reactor.on_data_message(&bytes);

        let error = reactor
            .last_error()
            .expect("uncorrelated error should surface globally");
        assert_eq!(error.details.code, "BAD_COMMAND");
    }

    /// A timeout cancels the pending correlation so a late reply is not
    /// delivered to a dropped receiver.
    #[tokio::test]
    async fn send_command_times_out() {
        let mut opts = ReactorOptions::new("http://localhost", "test-model");
        opts.control_request_timeout = Duration::from_millis(20);
        let reactor = make_reactor_opts(opts);
        reactor.state.lock().unwrap().status = ReactorStatus::Ready;

        let result = reactor.send_command("get_state", json!({}), None).await;
        assert!(matches!(result, Err(CoreError::Timeout(_))));
    }

    // ── Uploads ─────────────────────────────────────────────────────────────

    /// An empty file is rejected before any network call — the previous
    /// (aiortc-based) SDK validated this in Python; this restores the check at
    /// the layer every FFI upload entry point (path- and bytes-based) shares,
    /// rather than duplicating it per binding. `PendingHttp` backs this reactor
    /// and never resolves, so a request escaping past the check would hang the
    /// test rather than fail it cleanly.
    #[tokio::test]
    async fn upload_file_rejects_an_empty_file() {
        let reactor = make_reactor();
        {
            let mut state = reactor.state.lock().unwrap();
            state.session_id = Some("sess-1".into());
            state.status = ReactorStatus::Ready;
        }

        let result = tokio::time::timeout(
            Duration::from_millis(200),
            reactor.upload_file("f.bin", "application/octet-stream", vec![]),
        )
        .await
        .expect("should reject the empty file without touching the network");

        assert!(matches!(result, Err(CoreError::InvalidState(msg)) if msg.contains("empty")));
    }

    /// Without a session, `upload_file()` fails on that before ever looking at
    /// the bytes.
    #[tokio::test]
    async fn upload_file_requires_a_ready_session() {
        let reactor = make_reactor();

        let result = reactor
            .upload_file("f.bin", "application/octet-stream", vec![1, 2, 3])
            .await;

        assert!(matches!(result, Err(CoreError::InvalidState(msg)) if msg.contains("ready")));
    }
    // ── Session info + per-connect options ─────────────────────────────────────

    /// Plays a connect as far as the ready session, then stops answering: the
    /// ICE-servers request never resolves, so the connect neither completes nor
    /// tears down and the state it built up stays readable.
    struct ScriptedHttp;

    #[async_trait::async_trait]
    impl HttpClient for ScriptedHttp {
        async fn request(&self, req: HttpRequest) -> Result<HttpResponse, CoreError> {
            let json = |body: &str| {
                Ok(HttpResponse {
                    status: 200,
                    headers: vec![],
                    body: body.as_bytes().to_vec(),
                })
            };
            if req.url.ends_with("/ice_servers") {
                std::future::pending::<()>().await;
                unreachable!()
            }
            if req.method == Method::Post && req.url.ends_with("/sessions") {
                return json(r#"{"session_id": "sess_1", "state": "CREATED"}"#);
            }
            if req.method == Method::Get && req.url.ends_with("/sessions/sess_1") {
                return json(
                    r#"{
                        "session_id": "sess_1",
                        "state": "ACTIVE",
                        "cluster": "test-cluster",
                        "selected_transport": {"protocol": "webrtc", "version": "1.0"},
                        "capabilities": {
                            "protocol_version": "1.0",
                            "tracks": [{"name": "output", "kind": "video", "direction": "recvonly"}]
                        }
                    }"#,
                );
            }
            Err(CoreError::Http(format!(
                "ScriptedHttp: no route for {}",
                req.url
            )))
        }
    }

    /// A binding that wants the session's cluster, model or server version has
    /// the whole resource `connect()` already fetched, rather than having to
    /// re-request it.
    #[tokio::test]
    async fn the_session_resource_is_readable_once_capabilities_arrive() {
        let reactor = Arc::new(Reactor::new(
            ReactorDeps {
                http: Arc::new(ScriptedHttp) as SharedHttp,
                auth: Arc::new(NoAuth) as SharedAuth,
                platform: Arc::new(TestPlatform) as SharedPlatform,
                peer: Arc::new(NullPeer) as SharedPeer,
            },
            ReactorOptions::new("http://localhost", "test-model"),
        ));
        let mut events = reactor.subscribe();

        let r = reactor.clone();
        let connecting = tokio::spawn(async move { r.connect(ConnectOptions::default()).await });

        // Wait for the capabilities event: the same moment the session resource
        // is recorded, and before the connect stalls on ICE servers.
        tokio::time::timeout(Duration::from_secs(1), async {
            while let Some(event) = events.next().await {
                if matches!(event, ReactorEvent::CapabilitiesReceived(_)) {
                    return;
                }
            }
            panic!("capabilities were never published");
        })
        .await
        .expect("capabilities should arrive");

        let session = reactor.session_info().expect("session resource recorded");
        assert_eq!(session.session_id, "sess_1");
        assert_eq!(session.cluster.as_deref(), Some("test-cluster"));

        connecting.abort();
    }

    // ── preset_tracks fast path ─────────────────────────────────────────────

    /// Like `ScriptedHttp`, but `/ice_servers` resolves immediately (empty
    /// list) so the `preset_tracks` path's concurrent `peer.prepare()` can
    /// actually complete, and `/connections` never resolves so a connect that
    /// reaches registration parks there instead of completing or erroring.
    struct PresetTracksHttp {
        real_tracks_json: &'static str,
        requests: std::sync::Mutex<Vec<(Method, String)>>,
    }

    impl PresetTracksHttp {
        fn new(real_tracks_json: &'static str) -> Self {
            Self {
                real_tracks_json,
                requests: Default::default(),
            }
        }

        fn saw(&self, method: Method, url_suffix: &str) -> bool {
            self.requests
                .lock()
                .unwrap()
                .iter()
                .any(|(m, u)| *m == method && u.ends_with(url_suffix))
        }
    }

    #[async_trait::async_trait]
    impl HttpClient for PresetTracksHttp {
        async fn request(&self, req: HttpRequest) -> Result<HttpResponse, CoreError> {
            self.requests
                .lock()
                .unwrap()
                .push((req.method, req.url.clone()));
            let json = |body: String| {
                Ok(HttpResponse {
                    status: 200,
                    headers: vec![],
                    body: body.into_bytes(),
                })
            };
            if req.url.ends_with("/ice_servers") {
                return json(r#"{"ice_servers": []}"#.to_string());
            }
            if req.method == Method::Post && req.url.ends_with("/sessions") {
                return json(r#"{"session_id": "sess_1", "state": "CREATED"}"#.to_string());
            }
            if req.method == Method::Get && req.url.ends_with("/sessions/sess_1") {
                return json(format!(
                    r#"{{
                        "session_id": "sess_1",
                        "state": "ACTIVE",
                        "selected_transport": {{"protocol": "webrtc", "version": "1.0"}},
                        "capabilities": {{"protocol_version": "1.0", "tracks": {}}}
                    }}"#,
                    self.real_tracks_json
                ));
            }
            if req.method == Method::Post && req.url.ends_with("/connections") {
                std::future::pending::<()>().await;
                unreachable!()
            }
            Err(CoreError::Http(format!(
                "PresetTracksHttp: no route for {:?} {}",
                req.method, req.url
            )))
        }
    }

    /// Records what it was asked to prepare, and whether it was later closed
    /// without ever being sent.
    #[derive(Default)]
    struct SpyPeer {
        prepared_tracks: std::sync::Mutex<Option<Vec<TrackCapability>>>,
        closed: std::sync::atomic::AtomicBool,
    }

    #[async_trait::async_trait]
    impl PeerTransport for SpyPeer {
        async fn prepare(
            &self,
            _: &[IceServer],
            tracks: &[TrackCapability],
        ) -> Result<PreparedOffer, CoreError> {
            *self.prepared_tracks.lock().unwrap() = Some(tracks.to_vec());
            Ok(PreparedOffer {
                sdp_offer: "offer".into(),
                track_mapping: vec![],
            })
        }
        async fn set_remote_description(&self, _: &str) -> Result<(), CoreError> {
            Ok(())
        }
        fn send_data(&self, _: &[u8], _: bool) -> Result<(), CoreError> {
            Ok(())
        }
        fn send_control(&self, _: &[u8]) -> Result<(), CoreError> {
            Ok(())
        }
        async fn set_track_direction(&self, _: &str, _: bool) -> Result<(), CoreError> {
            Ok(())
        }
        async fn close(&self) -> Result<(), CoreError> {
            self.closed.store(true, std::sync::atomic::Ordering::SeqCst);
            Ok(())
        }
    }

    /// Peer transport for the REA-5659 regression test below: tracks whether
    /// `set_remote_description` was ever reached.
    #[derive(Default)]
    struct RacePeer {
        set_remote_description_called: std::sync::atomic::AtomicBool,
        closed: std::sync::atomic::AtomicBool,
    }

    #[async_trait::async_trait]
    impl PeerTransport for RacePeer {
        async fn prepare(
            &self,
            _: &[IceServer],
            _: &[TrackCapability],
        ) -> Result<PreparedOffer, CoreError> {
            Ok(PreparedOffer {
                sdp_offer: "offer".into(),
                track_mapping: vec![],
            })
        }
        async fn set_remote_description(&self, _: &str) -> Result<(), CoreError> {
            self.set_remote_description_called
                .store(true, std::sync::atomic::Ordering::SeqCst);
            Ok(())
        }
        fn send_data(&self, _: &[u8], _: bool) -> Result<(), CoreError> {
            Ok(())
        }
        fn send_control(&self, _: &[u8]) -> Result<(), CoreError> {
            Ok(())
        }
        async fn set_track_direction(&self, _: &str, _: bool) -> Result<(), CoreError> {
            Ok(())
        }
        async fn close(&self) -> Result<(), CoreError> {
            self.closed.store(true, std::sync::atomic::Ordering::SeqCst);
            Ok(())
        }
    }

    /// What `RaceHttp`'s `GET .../sdp_params` handler (`poll_sdp_answer`) does
    /// before returning the answer, to reproduce a teardown racing ahead of
    /// `finish_transport` deterministically — no real concurrency needed, since
    /// the teardown has already run by the time `finish_transport` gets the
    /// answer back and reaches its `state.closing` guard.
    enum RaceAction {
        /// The same event a broken TURN relay produces.
        ConnectionFailed,
        /// A caller-initiated `disconnect()` racing in — sets `closing` but,
        /// unlike `ConnectionFailed`, records no cause of its own.
        Disconnect,
    }

    /// Mocks the connection-registration + SDP round trip `finish_transport` drives.
    struct RaceHttp {
        action: RaceAction,
        reactor: std::sync::OnceLock<std::sync::Weak<Reactor>>,
    }

    impl RaceHttp {
        fn new(action: RaceAction) -> Arc<Self> {
            Arc::new(Self {
                action,
                reactor: std::sync::OnceLock::new(),
            })
        }

        fn bind(&self, reactor: &Arc<Reactor>) {
            let _ = self.reactor.set(Arc::downgrade(reactor));
        }
    }

    #[async_trait::async_trait]
    impl HttpClient for RaceHttp {
        async fn request(&self, req: HttpRequest) -> Result<HttpResponse, CoreError> {
            let json = |body: String| {
                Ok(HttpResponse {
                    status: 200,
                    headers: vec![],
                    body: body.into_bytes(),
                })
            };
            if req.method == Method::Post && req.url.ends_with("/connections") {
                return json(r#"{"connection_id": 1}"#.to_string());
            }
            if req.method == Method::Post && req.url.ends_with("/sdp_params") {
                return json("{}".to_string());
            }
            if req.method == Method::Get && req.url.ends_with("/sdp_params") {
                if let Some(reactor) = self.reactor.get().and_then(std::sync::Weak::upgrade) {
                    match self.action {
                        RaceAction::ConnectionFailed => {
                            reactor
                                .on_peer_connection_state(PeerConnectionState::Failed)
                                .await;
                        }
                        RaceAction::Disconnect => {
                            let _ = reactor.disconnect(true).await;
                        }
                    }
                }
                return json(r#"{"sdp_answer": "answer"}"#.to_string());
            }
            Err(CoreError::Http(format!(
                "RaceHttp: no route for {:?} {}",
                req.method, req.url
            )))
        }
    }

    /// REA-5659: a connection-state-driven teardown racing ahead of
    /// `finish_transport`'s own `set_remote_description` call used to surface
    /// the wasm transport's generic `InvalidState("peer transport not
    /// prepared")` — the teardown wiped its state first — instead of the real
    /// reason the connection died. `finish_transport` now checks `state.closing`
    /// right after the SDP answer comes back and, if a teardown beat it there,
    /// returns the reason already recorded in `teardown_cause` instead of
    /// calling into a transport it knows is gone.
    #[tokio::test]
    async fn finish_transport_surfaces_the_real_reason_when_a_teardown_races_ahead_of_set_remote_description(
    ) {
        let http = RaceHttp::new(RaceAction::ConnectionFailed);
        let peer = Arc::new(RacePeer::default());
        let opts = ReactorOptions::new("http://localhost", "test-model");
        let reactor = Arc::new(Reactor::new(
            ReactorDeps {
                http: http.clone() as SharedHttp,
                auth: Arc::new(NoAuth) as SharedAuth,
                platform: Arc::new(TestPlatform) as SharedPlatform,
                peer: peer.clone() as SharedPeer,
            },
            opts,
        ));
        http.bind(&reactor);
        // Matches real `connect_inner`'s status by the time it reaches
        // `establish_transport`/`finish_transport` — `on_peer_connection_state`
        // treats a `Disconnected` reactor as already torn down and skips
        // reporting/teardown, which would defeat this test's premise.
        reactor.state.lock().unwrap().status = ReactorStatus::Waiting;

        let result = tokio::time::timeout(
            Duration::from_secs(1),
            reactor.finish_transport(
                "sess_1",
                false,
                None,
                PreparedOffer {
                    sdp_offer: "offer".into(),
                    track_mapping: vec![],
                },
            ),
        )
        .await
        .expect("should fail fast, not hang");

        match result {
            Err(CoreError::Peer(message)) => {
                assert!(
                    message.contains("Failed"),
                    "expected the real connection-state reason, got: {message}"
                );
            }
            other => panic!("expected CoreError::Peer with the real reason, got: {other:?}"),
        }
        assert!(
            !peer
                .set_remote_description_called
                .load(std::sync::atomic::Ordering::SeqCst),
            "a torn-down transport must not be called into"
        );
    }

    /// REA-5659 follow-up (flagged in code review): `last_error` persists
    /// across connect attempts, so using it directly for `closing_error` risked
    /// attributing a stale, unrelated earlier failure to the current
    /// negotiation whenever `closing` was set by something other than
    /// `on_peer_connection_state` — a plain `disconnect()` racing in during SDP
    /// polling sets `closing` but records no cause of its own. This must fall
    /// back to the generic message, not the leftover `last_error`.
    #[tokio::test]
    async fn finish_transport_does_not_attribute_a_stale_last_error_to_an_unrelated_disconnect() {
        let http = RaceHttp::new(RaceAction::Disconnect);
        let peer = Arc::new(RacePeer::default());
        let opts = ReactorOptions::new("http://localhost", "test-model");
        let reactor = Arc::new(Reactor::new(
            ReactorDeps {
                http: http.clone() as SharedHttp,
                auth: Arc::new(NoAuth) as SharedAuth,
                platform: Arc::new(TestPlatform) as SharedPlatform,
                peer: peer.clone() as SharedPeer,
            },
            opts,
        ));
        http.bind(&reactor);
        reactor.state.lock().unwrap().status = ReactorStatus::Waiting;
        // Simulate a failure left over from an earlier, unrelated attempt —
        // `last_error` is not reset just because a new `finish_transport` is
        // in flight (only `connect()`/`reconnect()` reset it, at the top of a
        // fresh attempt).
        reactor.emit_error(ErrorDetails::new(
            codes::DISCONNECTED,
            "unrelated previous failure".to_string(),
            true,
        ));

        let result = tokio::time::timeout(
            Duration::from_secs(1),
            reactor.finish_transport(
                "sess_1",
                false,
                None,
                PreparedOffer {
                    sdp_offer: "offer".into(),
                    track_mapping: vec![],
                },
            ),
        )
        .await
        .expect("should fail fast, not hang");

        match result {
            Err(CoreError::Peer(message)) => {
                assert_eq!(
                    message, "connection closed during negotiation",
                    "must not surface the stale last_error from an unrelated earlier attempt"
                );
            }
            other => panic!("expected CoreError::Peer, got: {other:?}"),
        }
    }

    fn output_track(name: &str) -> TrackCapability {
        TrackCapability {
            name: name.into(),
            kind: crate::protocol::session::TrackKind::Video,
            direction: TrackDirection::Recvonly,
        }
    }

    /// A duplicate name in the preset must not let it stand in for a real
    /// track it doesn't actually name — `[a, a]` is not `[a, b]`, even though
    /// naive "every preset item is present in the real list" logic would
    /// think so (both `a`s independently find a match, `b` goes unchecked).
    #[test]
    fn tracks_match_rejects_a_duplicate_preset_standing_in_for_a_missing_track() {
        let preset = vec![output_track("a"), output_track("a")];
        let real = vec![output_track("a"), output_track("b")];
        assert!(!tracks_match(&preset, &real));
        assert!(!tracks_match(&real, &preset));
    }

    #[test]
    fn tracks_match_accepts_a_genuine_duplicate_on_both_sides() {
        let a = vec![output_track("a"), output_track("a")];
        let b = vec![output_track("a"), output_track("a")];
        assert!(tracks_match(&a, &b));
    }

    /// A preset that disagrees with the coordinator's real tracks fails fast,
    /// with the never-sent offer discarded, instead of registering a
    /// connection for tracks the caller didn't ask for.
    #[tokio::test]
    async fn preset_tracks_mismatch_fails_fast_without_registering_a_connection() {
        let http = Arc::new(PresetTracksHttp::new(
            r#"[{"name": "output", "kind": "video", "direction": "recvonly"}]"#,
        ));
        let peer = Arc::new(SpyPeer::default());
        let mut opts = ReactorOptions::new("http://localhost", "test-model");
        opts.preset_tracks = Some(vec![output_track("wrong")]);
        let reactor = Arc::new(Reactor::new(
            ReactorDeps {
                http: http.clone() as SharedHttp,
                auth: Arc::new(NoAuth) as SharedAuth,
                platform: Arc::new(TestPlatform) as SharedPlatform,
                peer: peer.clone() as SharedPeer,
            },
            opts,
        ));

        let result = tokio::time::timeout(
            Duration::from_secs(1),
            reactor.connect(ConnectOptions::default()),
        )
        .await
        .expect("a mismatch should fail fast rather than hang");

        assert!(matches!(
            result,
            Err(CoreError::PresetTracksMismatch { .. })
        ));
        assert!(
            peer.closed.load(std::sync::atomic::Ordering::SeqCst),
            "the never-sent offer's peer connection should be closed"
        );
        assert!(
            !http.saw(Method::Post, "/connections"),
            "a mismatched preset must never reach connection registration"
        );
    }

    /// A preset that agrees with the coordinator's real tracks (regardless of
    /// list order) proceeds straight to registration using the offer already
    /// built concurrently with the session-ready poll.
    #[tokio::test]
    async fn preset_tracks_matching_reality_proceeds_with_the_concurrently_built_offer() {
        let http = Arc::new(PresetTracksHttp::new(
            r#"[{"name": "b", "kind": "audio", "direction": "sendonly"},
                {"name": "a", "kind": "video", "direction": "recvonly"}]"#,
        ));
        let peer = Arc::new(SpyPeer::default());
        let mut opts = ReactorOptions::new("http://localhost", "test-model");
        // Same tracks, deliberately listed in the other order.
        opts.preset_tracks = Some(vec![
            output_track("a"),
            TrackCapability {
                name: "b".into(),
                kind: crate::protocol::session::TrackKind::Audio,
                direction: TrackDirection::Sendonly,
            },
        ]);
        let reactor = Arc::new(Reactor::new(
            ReactorDeps {
                http: http.clone() as SharedHttp,
                auth: Arc::new(NoAuth) as SharedAuth,
                platform: Arc::new(TestPlatform) as SharedPlatform,
                peer: peer.clone() as SharedPeer,
            },
            opts,
        ));

        let r = reactor.clone();
        let connecting = tokio::spawn(async move { r.connect(ConnectOptions::default()).await });

        tokio::time::timeout(Duration::from_secs(1), async {
            while !http.saw(Method::Post, "/connections") {
                tokio::time::sleep(Duration::from_millis(5)).await;
            }
        })
        .await
        .expect("a matching preset should reach connection registration");

        assert_eq!(
            peer.prepared_tracks.lock().unwrap().as_ref().map(Vec::len),
            Some(2)
        );

        connecting.abort();
    }

    /// Disconnecting ends the session, so what it looked like is no longer the
    /// answer to "what session are we on".
    #[tokio::test]
    async fn the_session_resource_is_cleared_on_disconnect() {
        let reactor = make_reactor();
        {
            let mut state = reactor.state.lock().unwrap();
            state.session_id = Some("sess_1".into());
            state.session_info = Some(SessionResponse {
                session_id: "sess_1".into(),
                state: crate::protocol::session::SessionState::Active,
                model: None,
                cluster: None,
                server_info: None,
                selected_transport: None,
                capabilities: None,
                extra: Default::default(),
            });
        }

        reactor.disconnect(false).await.unwrap();

        assert!(reactor.session_info().is_none());
    }

    /// The JS SDK takes both of these per connect, so a client cannot be asked
    /// to decide them once at construction.
    #[tokio::test]
    async fn connect_options_override_the_client_defaults() {
        let http = Arc::new(RecordingHttp::default());
        let reactor = make_reactor_with_http(http.clone());
        assert!(reactor.state.lock().unwrap().auto_resume_tracks);

        // RecordingHttp has no route for session creation, so the connect fails
        // immediately — after the overrides were taken, which is what matters.
        let _ = reactor
            .connect(ConnectOptions {
                auto_resume_tracks: Some(false),
                max_sdp_attempts: Some(3),
                ..Default::default()
            })
            .await;

        let state = reactor.state.lock().unwrap();
        assert!(!state.auto_resume_tracks);
        assert_eq!(state.sdp_max_attempts, 3);
    }

    /// A client that connected with its output tracks left paused must not have
    /// them resumed behind its back by the next connect or reconnect, so an
    /// override sticks until another one replaces it.
    #[tokio::test]
    async fn a_connect_option_sticks_until_it_is_overridden_again() {
        let http = Arc::new(RecordingHttp::default());
        let reactor = make_reactor_with_http(http.clone());

        let _ = reactor
            .connect(ConnectOptions {
                auto_resume_tracks: Some(false),
                ..Default::default()
            })
            .await;
        let _ = reactor.connect(ConnectOptions::default()).await;

        assert!(!reactor.state.lock().unwrap().auto_resume_tracks);

        let _ = reactor
            .connect(ConnectOptions {
                auto_resume_tracks: Some(true),
                ..Default::default()
            })
            .await;

        assert!(reactor.state.lock().unwrap().auto_resume_tracks);
    }
}
