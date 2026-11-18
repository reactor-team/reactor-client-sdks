//! Tests of the stepped session in [`crate::reactor`]: the generation mode a
//! connect asks for, and stepped input frames, requests and results.

use super::*;

/// A coordinator whose ready session echoes `echo` as its `generation`
/// (or nothing), with one output and one input track. ICE servers resolve
/// empty and connection registration parks, so a connect that passes the
/// mode check stops there.
struct GenerationHttp {
    echo: Option<&'static str>,
    /// Whether connection registration and the SDP exchange complete;
    /// otherwise registration parks.
    completes: bool,
    /// Whether the ready session declares tracks; otherwise its capabilities
    /// leave the `tracks` key out, as the coordinator does for a model with
    /// none.
    tracks: bool,
    /// The SDP answer the runtime gives once the exchange completes.
    answer: String,
    requests: std::sync::Mutex<Vec<(Method, String)>>,
    /// Every SDP offer the client sent, in order.
    offers: std::sync::Mutex<Vec<String>>,
}

impl GenerationHttp {
    fn new(echo: Option<&'static str>) -> Self {
        Self {
            echo,
            completes: false,
            tracks: true,
            answer: "answer".into(),
            requests: Default::default(),
            offers: Default::default(),
        }
    }

    fn completing(echo: Option<&'static str>) -> Self {
        Self {
            completes: true,
            ..Self::new(echo)
        }
    }

    fn trackless(echo: Option<&'static str>) -> Self {
        Self {
            tracks: false,
            ..Self::completing(echo)
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
impl HttpClient for GenerationHttp {
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
            let echo = self
                .echo
                .map(|mode| format!(r#""generation": "{mode}","#))
                .unwrap_or_default();
            let tracks = if self.tracks {
                r#", "tracks": [
                    {"name": "output", "kind": "video", "direction": "recvonly"},
                    {"name": "input", "kind": "video", "direction": "sendonly"}
                ]"#
            } else {
                ""
            };
            return json(format!(
                r#"{{
                    "session_id": "sess_1",
                    "state": "ACTIVE",
                    {echo}
                    "selected_transport": {{"protocol": "webrtc", "version": "1.0"}},
                    "capabilities": {{"protocol_version": "1.0"{tracks}}}
                }}"#
            ));
        }
        if req.method == Method::Post && req.url.ends_with("/connections") {
            if !self.completes {
                std::future::pending::<()>().await;
            }
            return json(r#"{"connection_id": 1}"#.to_string());
        }
        if self.completes && req.url.ends_with("/sdp_params") {
            return match req.method {
                Method::Get => json(json!({ "sdp_answer": self.answer }).to_string()),
                _ => {
                    let body: Value = serde_json::from_slice(req.body.as_deref().unwrap()).unwrap();
                    self.offers
                        .lock()
                        .unwrap()
                        .push(body["sdp_offer"].as_str().unwrap().to_string());
                    json(String::new())
                }
            };
        }
        Err(CoreError::Http(format!(
            "GenerationHttp: no route for {:?} {}",
            req.method, req.url
        )))
    }
}

fn make_generation_reactor(
    http: Arc<GenerationHttp>,
    peer: Arc<SpyPeer>,
    generation: GenerationMode,
) -> Arc<Reactor> {
    let mut opts = ReactorOptions::new("http://localhost", "test-model");
    opts.generation = generation;
    Arc::new(Reactor::new(
        ReactorDeps {
            http: http as SharedHttp,
            auth: Arc::new(NoAuth) as SharedAuth,
            platform: Arc::new(TestPlatform) as SharedPlatform,
            peer: peer as SharedPeer,
        },
        opts,
    ))
}

/// Connect until the session reaches connection registration, returning
/// the parked connect for the caller to abort.
async fn connect_until_registration(
    reactor: &Arc<Reactor>,
    http: &GenerationHttp,
) -> tokio::task::JoinHandle<Result<(), CoreError>> {
    let r = reactor.clone();
    let connecting = tokio::spawn(async move { r.connect(ConnectOptions::default()).await });
    tokio::time::timeout(Duration::from_secs(1), async {
        while !http.saw(Method::Post, "/connections") {
            tokio::time::sleep(Duration::from_millis(5)).await;
        }
    })
    .await
    .expect("connect should reach connection registration");
    connecting
}

/// A runtime without stepped generation ignores the request and echoes no
/// mode. Connecting anyway would leave the client waiting on steps from a
/// streaming session, so it fails, typed, before any transport exists.
#[tokio::test]
async fn a_stepped_connect_fails_when_the_runtime_does_not_echo_the_mode() {
    let http = Arc::new(GenerationHttp::new(None));
    let peer = Arc::new(SpyPeer::default());
    let reactor = make_generation_reactor(http.clone(), peer.clone(), GenerationMode::Stepped);

    let result = tokio::time::timeout(
        Duration::from_secs(1),
        reactor.connect(ConnectOptions::default()),
    )
    .await
    .expect("a missing echo should fail fast");

    match result {
        Err(error @ CoreError::GenerationModeMismatch { .. }) => {
            assert_eq!(error.code(), codes::GENERATION_MODE_MISMATCH);
            assert!(!error.recoverable());
            assert!(matches!(
                error,
                CoreError::GenerationModeMismatch {
                    requested: GenerationMode::Stepped,
                    echoed: None,
                }
            ));
        }
        other => panic!("expected GenerationModeMismatch, got {other:?}"),
    }
    assert!(peer.prepared_tracks.lock().unwrap().is_none());
    assert!(!http.saw(Method::Post, "/connections"));
    assert_eq!(reactor.status(), ReactorStatus::Disconnected);
}

#[tokio::test]
async fn a_stepped_connect_proceeds_when_the_runtime_echoes_the_mode() {
    let http = Arc::new(GenerationHttp::new(Some("stepped")));
    let peer = Arc::new(SpyPeer::default());
    let reactor = make_generation_reactor(http.clone(), peer, GenerationMode::Stepped);
    connect_until_registration(&reactor, &http).await.abort();
}

/// Opens both channels once the answer is applied, over a data channel
/// chunked or not, and records the tracks it prepared and the data it sent.
struct ChannelsPeer {
    chunked: bool,
    reactor: std::sync::Mutex<Option<std::sync::Weak<Reactor>>>,
    prepared_tracks: std::sync::Mutex<Option<Vec<TrackCapability>>>,
    sent_data: std::sync::Mutex<Vec<Vec<u8>>>,
    /// The offer `prepare` returns.
    offer: PreparedOffer,
}

impl ChannelsPeer {
    fn new(chunked: bool) -> Self {
        Self {
            chunked,
            reactor: std::sync::Mutex::new(None),
            prepared_tracks: std::sync::Mutex::new(None),
            sent_data: std::sync::Mutex::new(vec![]),
            offer: PreparedOffer {
                sdp_offer: "offer".into(),
                track_mapping: vec![],
            },
        }
    }

    fn sent(&self) -> Vec<DataClientMessage> {
        self.sent_data
            .lock()
            .unwrap()
            .iter()
            .map(|raw| DataClientMessage::decode(raw.as_slice()).unwrap())
            .collect()
    }
}

#[async_trait::async_trait]
impl PeerTransport for ChannelsPeer {
    async fn prepare(
        &self,
        _: &[IceServer],
        tracks: &[TrackCapability],
    ) -> Result<PreparedOffer, CoreError> {
        *self.prepared_tracks.lock().unwrap() = Some(tracks.to_vec());
        Ok(self.offer.clone())
    }
    async fn set_remote_description(&self, _: &str) -> Result<(), CoreError> {
        let reactor = self.reactor.lock().unwrap().clone();
        if let Some(reactor) = reactor.and_then(|w| w.upgrade()) {
            for event in [
                PeerEvent::ConnectionStateChanged(PeerConnectionState::Connected),
                PeerEvent::DataChannelOpen,
                PeerEvent::ControlChannelOpen,
            ] {
                reactor.handle_peer_event(event).await;
            }
        }
        Ok(())
    }
    fn send_data(&self, payload: &[u8], _: bool) -> Result<(), CoreError> {
        self.sent_data.lock().unwrap().push(payload.to_vec());
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
    fn data_channel_chunked(&self) -> bool {
        self.chunked
    }
}

async fn connect_over(
    generation: GenerationMode,
    chunked: bool,
) -> (Arc<Reactor>, Result<(), CoreError>) {
    let echo = match generation {
        GenerationMode::Stepped => Some("stepped"),
        GenerationMode::Streamed => None,
    };
    let http = Arc::new(GenerationHttp::completing(echo));
    let peer = Arc::new(ChannelsPeer::new(chunked));
    connect_with(http, peer, generation).await
}

async fn connect_with(
    http: Arc<GenerationHttp>,
    peer: Arc<ChannelsPeer>,
    generation: GenerationMode,
) -> (Arc<Reactor>, Result<(), CoreError>) {
    connect_with_codecs(http, peer, generation, LocalStepCodecs::default()).await
}

/// [`connect_with`], on a side that carries step frames in `local`.
async fn connect_with_codecs(
    http: Arc<GenerationHttp>,
    peer: Arc<ChannelsPeer>,
    generation: GenerationMode,
    local: LocalStepCodecs,
) -> (Arc<Reactor>, Result<(), CoreError>) {
    let mut opts = ReactorOptions::new("http://localhost", "test-model");
    opts.generation = generation;
    let reactor = Arc::new(Reactor::new(
        ReactorDeps {
            http: http as SharedHttp,
            auth: Arc::new(NoAuth) as SharedAuth,
            platform: Arc::new(TestPlatform) as SharedPlatform,
            peer: peer.clone() as SharedPeer,
        },
        opts,
    ));
    *peer.reactor.lock().unwrap() = Some(Arc::downgrade(&reactor));
    reactor.set_local_step_codecs(local);
    let result = tokio::time::timeout(
        Duration::from_secs(1),
        reactor.connect(ConnectOptions::default()),
    )
    .await
    .expect("connect() must not hang");
    (reactor, result)
}

/// A step's inputs and result each ride one data-channel message, and
/// without chunking one camera frame outgrows it: a stepped session whose
/// channel did not negotiate chunking fails at connect, typed, not at its
/// first step.
#[tokio::test]
async fn a_stepped_connect_fails_when_the_data_channel_is_not_chunked() {
    let (reactor, result) = connect_over(GenerationMode::Stepped, false).await;
    let error = result.expect_err("an unchunked stepped connect must fail");
    assert!(
        matches!(error, CoreError::ChunkingRequired),
        "got {error:?}"
    );
    assert_eq!(error.code(), codes::DATA_CHANNEL_CHUNKING_REQUIRED);
    assert!(!error.recoverable());
    assert_eq!(reactor.status(), ReactorStatus::Disconnected);
}

#[tokio::test]
async fn a_stepped_connect_over_a_chunked_data_channel_is_ready() {
    let (reactor, result) = connect_over(GenerationMode::Stepped, true).await;
    result.unwrap();
    assert_eq!(reactor.status(), ReactorStatus::Ready);
}

/// A model with no tracks is described without the `tracks` key. Its
/// stepped session still connects, over the data channels alone, and its
/// steps complete with the data the model returned.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_stepped_session_without_tracks_connects_and_completes_a_step() {
    let http = Arc::new(GenerationHttp::trackless(Some("stepped")));
    let peer = Arc::new(ChannelsPeer::new(true));
    let (reactor, result) = connect_with(http, peer.clone(), GenerationMode::Stepped).await;
    result.unwrap();
    assert_eq!(reactor.status(), ReactorStatus::Ready);
    assert!(reactor.tracks().is_empty());
    assert_eq!(
        peer.prepared_tracks.lock().unwrap().as_deref(),
        Some(&[][..])
    );

    let r = reactor.clone();
    let stepping = tokio::spawn(async move { r.step(Value::Null, vec![]).await });
    let sent = tokio::time::timeout(Duration::from_secs(1), async {
        loop {
            let request = peer.sent().into_iter().find(|message| {
                matches!(
                    message.payload,
                    Some(data_client_message::Payload::RequestStep(_))
                )
            });
            if let Some(request) = request {
                return request;
            }
            tokio::time::sleep(Duration::from_millis(2)).await;
        }
    })
    .await
    .expect("step should send its request");

    feed(
        &reactor,
        &sent.request_id,
        MessageKind::Response,
        data_server_message::Payload::StepResult(StepResult {
            step: 1,
            tracks: vec![],
            elapsed_seconds: 0.1,
            files: vec![],
            error: None,
            failure_code: StepFailureCode::Unspecified as i32,
            code: None,
            entry: None,
            data: Some(br#"{"actions":[[0.5,-0.25]]}"#.to_vec()),
        }),
    )
    .await;
    let output = stepping.await.unwrap().unwrap();
    assert_eq!(output.step, 1);
    assert!(output.tracks.is_empty());
    assert!(output.error.is_none());
    assert_eq!(
        output.data.as_deref(),
        Some(&br#"{"actions":[[0.5,-0.25]]}"#[..])
    );
}

/// Streamed sessions send small messages and do not need chunking.
#[tokio::test]
async fn a_streamed_connect_does_not_need_chunking() {
    let (reactor, result) = connect_over(GenerationMode::Streamed, false).await;
    result.unwrap();
    assert_eq!(reactor.status(), ReactorStatus::Ready);
}

/// Streamed is what every runtime did before the mode existed, so silence
/// is fine; an echo of a different mode is not.
#[tokio::test]
async fn a_streamed_connect_accepts_silence_but_not_a_stepped_session() {
    let http = Arc::new(GenerationHttp::new(None));
    let reactor = make_generation_reactor(
        http.clone(),
        Arc::new(SpyPeer::default()),
        GenerationMode::Streamed,
    );
    connect_until_registration(&reactor, &http).await.abort();

    let http = Arc::new(GenerationHttp::new(Some("stepped")));
    let reactor = make_generation_reactor(
        http.clone(),
        Arc::new(SpyPeer::default()),
        GenerationMode::Streamed,
    );
    let result = reactor.connect(ConnectOptions::default()).await;
    assert!(matches!(
        result,
        Err(CoreError::GenerationModeMismatch {
            requested: GenerationMode::Streamed,
            echoed: Some(_),
        })
    ));
}

// ── Stepped generation: step() ──────────────────────────────────────────

use crate::protocol::wire::v1::data::DataClientMessage;
use crate::protocol::wire::v1::platform::TrackKind as WireTrackKind;
use crate::protocol::wire::v1::step::{
    Frame, FrameEncoding, StepFailureCode, StepFileRef, StepResult, TrackFrames,
};

/// Records every data-channel send and advertises `max` as its limit.
struct DataPeer {
    sent_data: Mutex<Vec<Vec<u8>>>,
    sent_control: Mutex<Vec<Vec<u8>>>,
    /// The message limit, which a test may change mid-session.
    max: std::sync::atomic::AtomicUsize,
    /// Tracks pushed to as media (the streamed path), in push order.
    pushed: Mutex<Vec<String>>,
    /// Control sends refused, by their index among every control send.
    failing_control: Mutex<Vec<usize>>,
    control_sends: std::sync::atomic::AtomicUsize,
}

impl DataPeer {
    fn new(max: usize) -> Self {
        Self {
            sent_data: Mutex::new(vec![]),
            sent_control: Mutex::new(vec![]),
            max: max.into(),
            pushed: Mutex::new(vec![]),
            failing_control: Mutex::new(vec![]),
            control_sends: std::sync::atomic::AtomicUsize::new(0),
        }
    }

    /// The control payloads sent, as `pause:<track>` and `resume:<track>`.
    fn track_controls(&self) -> Vec<String> {
        use crate::protocol::wire::v1::control::control_client_message::Payload;
        use crate::protocol::wire::v1::control::ControlClientMessage;
        self.sent_control
            .lock()
            .unwrap()
            .iter()
            .filter_map(|raw| {
                match ControlClientMessage::decode(raw.as_slice())
                    .unwrap()
                    .payload
                {
                    Some(Payload::PauseTrack(t)) => Some(format!("pause:{}", t.name)),
                    Some(Payload::ResumeTrack(t)) => Some(format!("resume:{}", t.name)),
                    _ => None,
                }
            })
            .collect()
    }

    fn sent(&self) -> Vec<DataClientMessage> {
        self.sent_data
            .lock()
            .unwrap()
            .iter()
            .map(|raw| DataClientMessage::decode(raw.as_slice()).unwrap())
            .collect()
    }
}

#[async_trait::async_trait]
impl PeerTransport for DataPeer {
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
    fn send_data(&self, payload: &[u8], _: bool) -> Result<(), CoreError> {
        self.sent_data.lock().unwrap().push(payload.to_vec());
        Ok(())
    }
    fn send_control(&self, payload: &[u8]) -> Result<(), CoreError> {
        let index = self
            .control_sends
            .fetch_add(1, std::sync::atomic::Ordering::SeqCst);
        if self.failing_control.lock().unwrap().contains(&index) {
            return Err(CoreError::Peer("control channel refused".into()));
        }
        self.sent_control.lock().unwrap().push(payload.to_vec());
        Ok(())
    }
    /// Refused, as the real peers refuse a track they have no transceiver
    /// for: this one prepares none.
    async fn set_track_direction(&self, name: &str, _: bool) -> Result<(), CoreError> {
        Err(CoreError::Peer(format!("unknown track: {name}")))
    }
    async fn close(&self) -> Result<(), CoreError> {
        Ok(())
    }
    fn max_message_bytes(&self) -> usize {
        self.max.load(std::sync::atomic::Ordering::SeqCst)
    }
    fn push_video_frame(&self, track_name: &str, _: &[u8], _: u32, _: u32) {
        self.pushed.lock().unwrap().push(track_name.to_string());
    }
    fn push_audio_frame(&self, track_name: &str, _: &[i16], _: u32, _: u32) {
        self.pushed.lock().unwrap().push(track_name.to_string());
    }
}

/// A ready reactor in `generation` mode on a [`DataPeer`].
fn make_ready_reactor(peer: Arc<DataPeer>, generation: GenerationMode) -> Arc<Reactor> {
    let mut opts = ReactorOptions::new("http://localhost", "test-model");
    opts.generation = generation;
    let reactor = Arc::new(Reactor::new(
        ReactorDeps {
            http: Arc::new(PendingHttp) as SharedHttp,
            auth: Arc::new(NoAuth) as SharedAuth,
            platform: Arc::new(TestPlatform) as SharedPlatform,
            peer: peer as SharedPeer,
        },
        opts,
    ));
    reactor.state.lock().unwrap().status = ReactorStatus::Ready;
    reactor
}

fn raw_frame(byte: u8) -> Frame {
    Frame {
        encoding: FrameEncoding::Raw as i32,
        dtype: "uint8".into(),
        shape: vec![1, 1, 3],
        data: vec![byte; 3],
        metadata: None,
        capture_time_us: None,
        keyframe: false,
    }
}

/// Spawn a step and wait until its request is on the wire.
async fn start_step(
    reactor: &Arc<Reactor>,
    peer: &DataPeer,
    state: Value,
    commands: Vec<StepCommand>,
) -> (
    tokio::task::JoinHandle<Result<StepOutput, CoreError>>,
    DataClientMessage,
) {
    let before = peer.sent().len();
    let r = reactor.clone();
    let stepping = tokio::spawn(async move { r.step(state, commands).await });
    let sent = tokio::time::timeout(Duration::from_secs(1), async {
        loop {
            let sent = peer.sent();
            if sent.len() > before {
                return sent.last().unwrap().clone();
            }
            tokio::time::sleep(Duration::from_millis(2)).await;
        }
    })
    .await
    .expect("step should send its request");
    (stepping, sent)
}

async fn feed(
    reactor: &Reactor,
    request_id: &str,
    kind: MessageKind,
    payload: data_server_message::Payload,
) {
    let raw = DataServerMessage {
        request_id: request_id.to_string(),
        kind: kind as i32,
        payload: Some(payload),
    }
    .encode_to_vec();
    reactor
        .handle_peer_event(PeerEvent::DataChannelMessage(raw))
        .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_step_sends_one_request_with_state_commands_and_buffered_inputs() {
    let peer = Arc::new(DataPeer::new(crate::protocol::DEFAULT_MAX_MESSAGE_BYTES));
    let reactor = make_ready_reactor(peer.clone(), GenerationMode::Stepped);
    reactor
        .step_inputs
        .push("cam", WireTrackKind::Video, raw_frame(1), usize::MAX);
    reactor
        .step_inputs
        .push("mic", WireTrackKind::Audio, raw_frame(2), usize::MAX);
    reactor
        .step_inputs
        .push("cam", WireTrackKind::Video, raw_frame(3), usize::MAX);

    let (stepping, sent) = start_step(
        &reactor,
        &peer,
        json!({"prompt": "a cat"}),
        vec![StepCommand::new("set_seed", json!({"seed": 7}))],
    )
    .await;

    assert_eq!(sent.kind, MessageKind::Request as i32);
    let Some(data_client_message::Payload::RequestStep(request)) = sent.payload else {
        panic!("expected a RequestStep, got {:?}", sent.payload);
    };
    assert_eq!(
        struct_to_value(request.state.unwrap()),
        json!({"prompt": "a cat"})
    );
    assert_eq!(request.commands.len(), 1);
    assert_eq!(request.commands[0].r#type, "set_seed");
    assert_eq!(
        request
            .media_input
            .iter()
            .map(|t| (
                t.track.as_str(),
                t.frames.iter().map(|f| f.data[0]).collect::<Vec<_>>()
            ))
            .collect::<Vec<_>>(),
        [("cam", vec![1, 3]), ("mic", vec![2])]
    );
    assert!(
        reactor.step_inputs.is_empty(),
        "a sent step owns its inputs; the buffer starts over"
    );

    feed(
        &reactor,
        &sent.request_id,
        MessageKind::Response,
        data_server_message::Payload::StepResult(StepResult {
            step: 1,
            tracks: vec![TrackFrames {
                track: "out".into(),
                kind: WireTrackKind::Video as i32,
                frames: vec![raw_frame(9)],
                rate: 0.0,
            }],
            elapsed_seconds: 1.5,
            files: vec![],
            error: None,
            failure_code: StepFailureCode::Unspecified as i32,
            code: None,
            entry: None,
            data: None,
        }),
    )
    .await;
    let output = stepping.await.unwrap().unwrap();
    assert_eq!(output.step, 1);
    assert_eq!(output.elapsed, 1.5);
    assert_eq!(output.tracks[0].track, "out");
    assert_eq!(output.tracks[0].frames[0].data, [9, 9, 9]);
    assert_eq!(output.tracks[0].frames[0].shape, [1, 1, 3]);
}

/// The result is the step's one answer: it resolves the step with its
/// file references, and nothing about the step reaches the event stream.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_step_resolves_on_its_result_alone_with_its_files() {
    let peer = Arc::new(DataPeer::new(crate::protocol::DEFAULT_MAX_MESSAGE_BYTES));
    let reactor = make_ready_reactor(peer.clone(), GenerationMode::Stepped);
    let mut events = reactor.subscribe();
    let (stepping, sent) = start_step(&reactor, &peer, Value::Null, vec![]).await;

    feed(
        &reactor,
        &sent.request_id,
        MessageKind::Response,
        data_server_message::Payload::StepResult(StepResult {
            step: 4,
            tracks: vec![],
            elapsed_seconds: 0.5,
            files: vec![StepFileRef {
                name: "depth.npy".into(),
                content_type: "application/octet-stream".into(),
                size: 4096,
            }],
            error: None,
            failure_code: StepFailureCode::Unspecified as i32,
            code: None,
            entry: None,
            data: None,
        }),
    )
    .await;
    let output = stepping.await.unwrap().unwrap();
    assert_eq!(output.step, 4);
    assert_eq!(output.elapsed, 0.5);
    assert!(output.error.is_none());
    assert_eq!(
        output.files,
        [crate::step::StepFile {
            name: "depth.npy".into(),
            content_type: "application/octet-stream".into(),
            size: 4096,
        }]
    );
    while let Ok(event) = events.try_recv() {
        assert!(
            !matches!(event, ReactorEvent::Message(_) | ReactorEvent::Error(_)),
            "a step's result was also published as an event: {event:?}"
        );
    }
}

/// A step that ran and reported a failure is answered with a result, not
/// an error: the call resolves, and the reason rides on the output.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_step_that_ran_and_failed_resolves_with_its_reason() {
    let peer = Arc::new(DataPeer::new(crate::protocol::DEFAULT_MAX_MESSAGE_BYTES));
    let reactor = make_ready_reactor(peer.clone(), GenerationMode::Stepped);
    let (stepping, sent) = start_step(&reactor, &peer, Value::Null, vec![]).await;

    feed(
        &reactor,
        &sent.request_id,
        MessageKind::Response,
        data_server_message::Payload::StepResult(StepResult {
            step: 2,
            tracks: vec![],
            elapsed_seconds: 0.2,
            files: vec![],
            error: Some("generate() raised".into()),
            failure_code: StepFailureCode::Failed as i32,
            code: None,
            entry: None,
            data: None,
        }),
    )
    .await;
    let output = stepping.await.unwrap().unwrap();
    assert_eq!(output.step, 2);
    assert_eq!(output.error.as_deref(), Some("generate() raised"));
}

/// Every step outcome arrives as a StepResult; its failure_code picks the
/// variant, and its `error` is the message. None of them is an event.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn step_failure_codes_map_to_their_own_variants() {
    type IsExpected = fn(&CoreError) -> bool;
    let cases: [(StepFailureCode, &str, IsExpected); 3] = [
        (
            StepFailureCode::Refused,
            codes::STEP_REFUSED,
            |e| matches!(e, CoreError::StepRefused { message, .. } if message == "no"),
        ),
        (
            StepFailureCode::NotReady,
            codes::STEP_NOT_READY,
            |e| matches!(e, CoreError::StepNotReady { message } if message == "no"),
        ),
        (
            StepFailureCode::ResultTooLarge,
            codes::STEP_RESULT_TOO_LARGE,
            |e| matches!(e, CoreError::StepResultTooLarge { message } if message == "no"),
        ),
    ];
    for (failure_code, code, expected) in cases {
        let peer = Arc::new(DataPeer::new(crate::protocol::DEFAULT_MAX_MESSAGE_BYTES));
        let reactor = make_ready_reactor(peer.clone(), GenerationMode::Stepped);
        let mut events = reactor.subscribe();
        let (stepping, sent) = start_step(&reactor, &peer, Value::Null, vec![]).await;
        let ran = failure_code == StepFailureCode::ResultTooLarge;
        feed(
            &reactor,
            &sent.request_id,
            MessageKind::Response,
            data_server_message::Payload::StepResult(StepResult {
                step: if ran { 3 } else { 0 },
                tracks: vec![],
                elapsed_seconds: if ran { 0.4 } else { 0.0 },
                files: vec![],
                error: Some("no".into()),
                failure_code: failure_code as i32,
                code: None,
                entry: None,
                data: None,
            }),
        )
        .await;
        let error = stepping.await.unwrap().unwrap_err();
        assert!(expected(&error), "{failure_code:?}: got {error:?}");
        assert_eq!(error.code(), code);
        while let Ok(event) = events.try_recv() {
            assert!(
                !matches!(event, ReactorEvent::Error(_)),
                "a correlated step failure is the caller's, not an event"
            );
        }
    }
}

/// No step reply is an `Error` any more; one that arrives anyway fails
/// the step with its code passed through, rather than hanging it.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn an_error_reply_to_a_step_passes_its_code_through() {
    let peer = Arc::new(DataPeer::new(crate::protocol::DEFAULT_MAX_MESSAGE_BYTES));
    let reactor = make_ready_reactor(peer.clone(), GenerationMode::Stepped);
    let (stepping, sent) = start_step(&reactor, &peer, Value::Null, vec![]).await;
    feed(
        &reactor,
        &sent.request_id,
        MessageKind::Response,
        data_server_message::Payload::Error(crate::protocol::wire::v1::common::Error {
            code: "model_crashed".into(),
            message: "no".into(),
            track: None,
        }),
    )
    .await;
    let error = stepping.await.unwrap().unwrap_err();
    assert!(
        matches!(&error, CoreError::StepFailed { code, .. } if code == "model_crashed"),
        "got {error:?}"
    );
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_step_result_in_an_unknown_encoding_fails_typed() {
    let peer = Arc::new(DataPeer::new(crate::protocol::DEFAULT_MAX_MESSAGE_BYTES));
    let reactor = make_ready_reactor(peer.clone(), GenerationMode::Stepped);
    let (stepping, sent) = start_step(&reactor, &peer, Value::Null, vec![]).await;
    feed(
        &reactor,
        &sent.request_id,
        MessageKind::Response,
        data_server_message::Payload::StepResult(StepResult {
            step: 1,
            tracks: vec![TrackFrames {
                track: "out".into(),
                kind: WireTrackKind::Video as i32,
                frames: vec![Frame {
                    encoding: 42,
                    ..raw_frame(0)
                }],
                rate: 0.0,
            }],
            elapsed_seconds: 0.0,
            files: vec![],
            error: None,
            failure_code: StepFailureCode::Unspecified as i32,
            code: None,
            entry: None,
            data: None,
        }),
    )
    .await;
    assert!(matches!(
        stepping.await.unwrap(),
        Err(CoreError::UnsupportedFrameEncoding { encoding: 42, .. })
    ));
}

/// Over the channel's limit, nothing is sent, and the inputs that made the
/// request too large are dropped rather than kept to fail the next step.
#[tokio::test]
async fn an_oversized_step_is_refused_before_sending_and_drops_its_inputs() {
    let peer = Arc::new(DataPeer::new(64));
    let reactor = make_ready_reactor(peer.clone(), GenerationMode::Stepped);
    reactor.step_inputs.push(
        "cam",
        WireTrackKind::Video,
        Frame {
            data: vec![0; 1024],
            ..raw_frame(0)
        },
        usize::MAX,
    );

    let result = reactor.step(Value::Null, vec![]).await;
    assert!(matches!(
        result,
        Err(CoreError::MessageTooLarge { max: 64, .. })
    ));
    assert!(peer.sent().is_empty());
    assert!(reactor.step_inputs.is_empty());
}

/// A camera pushing faster than steps run keeps at most one message's worth
/// of frames, the newest ones, counted as they encode: the step that takes
/// them is sent, not refused as too large.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn stepped_pushes_keep_at_most_one_message_of_frames() {
    let max = 1024;
    let peer = Arc::new(DataPeer::new(max));
    let reactor = make_ready_reactor(peer.clone(), GenerationMode::Stepped);
    for value in 1..=200u8 {
        reactor.push_video_frame("cam", &[value, value, value, 255], 1, 1);
    }
    let buffered = reactor.step_inputs.len();
    assert!((2..200).contains(&buffered), "{buffered} frames buffered");

    let (_stepping, sent) = start_step(&reactor, &peer, Value::Null, vec![]).await;
    assert!(sent.encoded_len() <= max, "{} > {max}", sent.encoded_len());
    let Some(data_client_message::Payload::RequestStep(request)) = sent.payload else {
        panic!("expected a step request");
    };
    let [cam] = request.media_input.try_into().unwrap();
    let newest: Vec<u8> = (201 - buffered as u8..=200).collect();
    assert_eq!(
        cam.frames.iter().map(|f| f.data[0]).collect::<Vec<_>>(),
        newest
    );
}

/// A stepped session's input track has no transceiver: pausing it sends the
/// notification and stops buffering its frames, and resuming takes them
/// again, without asking the peer for a transceiver it does not have.
#[tokio::test]
async fn a_paused_stepped_input_takes_no_frames_until_resumed() {
    let peer = Arc::new(DataPeer::new(crate::protocol::DEFAULT_MAX_MESSAGE_BYTES));
    let reactor = make_ready_reactor(peer.clone(), GenerationMode::Stepped);
    reactor.state.lock().unwrap().capabilities = Some(Capabilities {
        protocol_version: "1".into(),
        tracks: vec![
            output_track("output"),
            TrackCapability {
                name: "cam".into(),
                kind: SessionTrackKind::Video,
                direction: TrackDirection::Sendonly,
            },
        ],
        commands: None,
        emission_fps: None,
    });

    reactor.pause_track("cam").await.unwrap();
    assert!(reactor.paused_tracks().contains("cam"));
    reactor.push_video_frame("cam", &[1, 2, 3, 255], 1, 1);
    assert!(reactor.step_inputs.is_empty());

    reactor.resume_track("cam").await.unwrap();
    assert!(reactor.paused_tracks().is_empty());
    reactor.push_video_frame("cam", &[1, 2, 3, 255], 1, 1);
    assert_eq!(reactor.step_inputs.len(), 1);

    // An output track still has its transceiver to flip.
    assert!(reactor.pause_track("output").await.is_err());
}

/// An empty frame still counts against the budget, so pushing them cannot
/// grow the buffer without end.
#[test]
fn empty_stepped_pushes_stay_within_the_budget() {
    let peer = Arc::new(DataPeer::new(1024));
    let reactor = make_ready_reactor(peer.clone(), GenerationMode::Stepped);
    for _ in 0..10_000 {
        reactor.push_audio_frame("mic", &[], 48_000, 1);
    }
    assert!(reactor.step_inputs.len() < 1024);
}

/// Once the session's tracks are known, a push to a track it does not take
/// as input — unknown, an output, or of the other kind — is not buffered.
#[test]
fn stepped_pushes_to_tracks_the_session_does_not_take_are_dropped() {
    let peer = Arc::new(DataPeer::new(crate::protocol::DEFAULT_MAX_MESSAGE_BYTES));
    let reactor = make_ready_reactor(peer.clone(), GenerationMode::Stepped);
    reactor.state.lock().unwrap().capabilities = Some(Capabilities {
        protocol_version: "1".into(),
        tracks: vec![
            output_track("output"),
            TrackCapability {
                name: "cam".into(),
                kind: SessionTrackKind::Video,
                direction: TrackDirection::Sendonly,
            },
        ],
        commands: None,
        emission_fps: None,
    });
    reactor.push_video_frame("other", &[1, 2, 3, 255], 1, 1);
    reactor.push_video_frame("output", &[1, 2, 3, 255], 1, 1);
    reactor.push_audio_frame("cam", &[10, 20], 48_000, 1);
    assert!(reactor.step_inputs.is_empty());

    reactor.push_video_frame("cam", &[1, 2, 3, 255], 1, 1);
    assert_eq!(reactor.step_inputs.len(), 1);
}

#[tokio::test]
async fn step_is_refused_outside_a_ready_stepped_session() {
    let peer = Arc::new(DataPeer::new(crate::protocol::DEFAULT_MAX_MESSAGE_BYTES));
    let streamed = make_ready_reactor(peer.clone(), GenerationMode::Streamed);
    assert!(matches!(
        streamed.step(Value::Null, vec![]).await,
        Err(CoreError::InvalidState(_))
    ));

    let stepped = make_ready_reactor(peer.clone(), GenerationMode::Stepped);
    stepped.state.lock().unwrap().status = ReactorStatus::Disconnected;
    assert!(matches!(
        stepped.step(Value::Null, vec![]).await,
        Err(CoreError::InvalidState(_))
    ));
    assert!(peer.sent().is_empty());
}

/// A disconnect with a step in flight rejects it as a recoverable step
/// failure rather than as a command's.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_disconnect_fails_the_step_in_flight() {
    let peer = Arc::new(DataPeer::new(crate::protocol::DEFAULT_MAX_MESSAGE_BYTES));
    let reactor = make_ready_reactor(peer.clone(), GenerationMode::Stepped);
    let (stepping, _) = start_step(&reactor, &peer, Value::Null, vec![]).await;
    reactor.data.fail_all("disconnected");
    let error = stepping.await.unwrap().unwrap_err();
    assert!(matches!(error, CoreError::StepFailed { .. }));
    assert_eq!(error.code(), codes::DISCONNECTED);
    assert!(error.recoverable());
}

// ── Stepped generation: per-track result decoding ───────────────────────

/// A stand-in stateful decoder for H.264 output tracks: each frame decodes
/// to the number of frames its decoder decoded before it, and a frame whose
/// first byte is `0xFF` is corrupt and fails it.
struct CountingDecoder(u8);

impl crate::step::TrackDecoder for CountingDecoder {
    fn decode(&mut self, frame: Frame) -> Result<crate::step::DecodedFrame, CoreError> {
        if frame.data[0] == 0xFF {
            return Err(CoreError::decode("corrupt frame"));
        }
        self.0 += 1;
        Ok(crate::step::DecodedFrame {
            encoding: FrameEncoding::H264,
            dtype: "uint8".into(),
            shape: vec![1],
            data: vec![self.0 - 1],
            metadata: None,
            capture_time_us: None,
            keyframe: false,
        })
    }
}

/// [`make_ready_reactor`] in stepped mode, decoding H.264 output tracks
/// with [`CountingDecoder`].
fn make_decoding_reactor(peer: Arc<DataPeer>) -> Arc<Reactor> {
    let mut opts = ReactorOptions::new("http://localhost", "test-model");
    opts.generation = GenerationMode::Stepped;
    let mut reactor = Reactor::new(
        ReactorDeps {
            http: Arc::new(PendingHttp) as SharedHttp,
            auth: Arc::new(NoAuth) as SharedAuth,
            platform: Arc::new(TestPlatform) as SharedPlatform,
            peer: peer as SharedPeer,
        },
        opts,
    );
    reactor.frame_decoders.register_track_decoder(
        FrameEncoding::H264,
        Box::new(|| Ok(Box::new(CountingDecoder(0)))),
    );
    reactor.state.lock().unwrap().status = ReactorStatus::Ready;
    Arc::new(reactor)
}

fn h264_result(step: u64, byte: u8, keyframe: bool) -> data_server_message::Payload {
    data_server_message::Payload::StepResult(StepResult {
        step,
        tracks: vec![TrackFrames {
            track: "out".into(),
            kind: WireTrackKind::Video as i32,
            frames: vec![Frame {
                encoding: FrameEncoding::H264 as i32,
                keyframe,
                data: vec![byte],
                ..raw_frame(0)
            }],
            rate: 0.0,
        }],
        ..Default::default()
    })
}

/// Results are decoded in the order they arrive, not the order their steps
/// are awaited in: the second step's result, arriving first with the
/// keyframe, starts the stream the first step's result then runs on.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn step_results_decode_in_arrival_order() {
    let peer = Arc::new(DataPeer::new(crate::protocol::DEFAULT_MAX_MESSAGE_BYTES));
    let reactor = make_decoding_reactor(peer.clone());
    let (first, first_sent) = start_step(&reactor, &peer, Value::Null, vec![]).await;
    let (second, second_sent) = start_step(&reactor, &peer, Value::Null, vec![]).await;

    feed(
        &reactor,
        &second_sent.request_id,
        MessageKind::Response,
        h264_result(1, 1, true),
    )
    .await;
    feed(
        &reactor,
        &first_sent.request_id,
        MessageKind::Response,
        h264_result(2, 2, false),
    )
    .await;

    let first = first.await.unwrap().unwrap();
    let second = second.await.unwrap().unwrap();
    assert_eq!(second.tracks[0].frames[0].data, [0]);
    assert_eq!(first.tracks[0].frames[0].data, [1]);
    assert_eq!(first.tracks[0].frames[0].encoding, FrameEncoding::H264);
}

/// A host that decodes step frames itself gets each encoded frame as it
/// arrived, keyframe mark included, and no stream state is kept here: a
/// frame that is not a keyframe passes even with none before it, and the
/// core restarts no track on its own.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn passed_through_frames_come_up_encoded_with_their_keyframe_mark() {
    let peer = Arc::new(DataPeer::new(crate::protocol::DEFAULT_MAX_MESSAGE_BYTES));
    let mut opts = ReactorOptions::new("http://localhost", "test-model");
    opts.generation = GenerationMode::Stepped;
    opts.pass_encoded_step_frames = true;
    let reactor = Reactor::new(
        ReactorDeps {
            http: Arc::new(PendingHttp) as SharedHttp,
            auth: Arc::new(NoAuth) as SharedAuth,
            platform: Arc::new(TestPlatform) as SharedPlatform,
            peer: peer.clone() as SharedPeer,
        },
        opts,
    );
    reactor.state.lock().unwrap().status = ReactorStatus::Ready;
    let reactor = Arc::new(reactor);

    for (step, keyframe) in [(1, false), (2, true)] {
        let (stepping, sent) = start_step(&reactor, &peer, Value::Null, vec![]).await;
        feed(
            &reactor,
            &sent.request_id,
            MessageKind::Response,
            h264_result(step, 0x65, keyframe),
        )
        .await;
        let output = stepping.await.unwrap().unwrap();
        let frame = &output.tracks[0].frames[0];
        assert_eq!(frame.encoding, FrameEncoding::H264);
        assert_eq!(frame.data, [0x65]);
        assert_eq!(frame.keyframe, keyframe);
    }
    assert!(
        peer.track_controls().is_empty(),
        "the host restarts its own streams"
    );
}

/// A result whose step gave up still moves its track's stream on, so the
/// next result's frames follow from it.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_result_no_step_awaits_still_decodes() {
    let peer = Arc::new(DataPeer::new(crate::protocol::DEFAULT_MAX_MESSAGE_BYTES));
    let reactor = make_decoding_reactor(peer.clone());
    feed(
        &reactor,
        "data_99",
        MessageKind::Response,
        h264_result(1, 1, true),
    )
    .await;
    let (stepping, sent) = start_step(&reactor, &peer, Value::Null, vec![]).await;
    feed(
        &reactor,
        &sent.request_id,
        MessageKind::Response,
        h264_result(2, 2, false),
    )
    .await;
    assert_eq!(
        stepping.await.unwrap().unwrap().tracks[0].frames[0].data,
        [1]
    );
}

/// A decode error loses the track's stream: that step fails, and the core
/// pauses and resumes that one track, once, so the runtime starts its next
/// frames at a keyframe. Frames before the keyframe fail without another
/// restart; the keyframe and the frames after it decode.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_decode_error_restarts_the_track_with_one_pause_and_resume() {
    let peer = Arc::new(DataPeer::new(crate::protocol::DEFAULT_MAX_MESSAGE_BYTES));
    let reactor = make_decoding_reactor(peer.clone());

    let (stepping, sent) = start_step(&reactor, &peer, Value::Null, vec![]).await;
    feed(
        &reactor,
        &sent.request_id,
        MessageKind::Response,
        h264_result(1, 1, true),
    )
    .await;
    stepping.await.unwrap().unwrap();
    assert!(peer.track_controls().is_empty());

    let (stepping, sent) = start_step(&reactor, &peer, Value::Null, vec![]).await;
    feed(
        &reactor,
        &sent.request_id,
        MessageKind::Response,
        h264_result(2, 0xFF, false),
    )
    .await;
    assert!(matches!(stepping.await.unwrap(), Err(CoreError::Decode(_))));
    assert_eq!(peer.track_controls(), ["pause:out", "resume:out"]);

    // A result already in flight, encoded before the resume.
    let (stepping, sent) = start_step(&reactor, &peer, Value::Null, vec![]).await;
    feed(
        &reactor,
        &sent.request_id,
        MessageKind::Response,
        h264_result(3, 3, false),
    )
    .await;
    assert!(matches!(stepping.await.unwrap(), Err(CoreError::Decode(_))));
    assert_eq!(peer.track_controls().len(), 2, "one restart per loss");

    let (stepping, sent) = start_step(&reactor, &peer, Value::Null, vec![]).await;
    feed(
        &reactor,
        &sent.request_id,
        MessageKind::Response,
        h264_result(4, 4, true),
    )
    .await;
    assert_eq!(
        stepping.await.unwrap().unwrap().tracks[0].frames[0].data,
        [0]
    );
    assert!(!reactor.frame_decoders.awaiting_keyframe());

    let (stepping, sent) = start_step(&reactor, &peer, Value::Null, vec![]).await;
    feed(
        &reactor,
        &sent.request_id,
        MessageKind::Response,
        h264_result(5, 5, false),
    )
    .await;
    stepping.await.unwrap().unwrap();
    assert_eq!(peer.track_controls().len(), 2);
}

/// A restart that does not all go out is retried, from its pause, on the
/// next result: the track does not stay paused, nor wait for a keyframe the
/// runtime was never asked for.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_restart_that_failed_to_send_is_retried_on_the_next_result() {
    let peer = Arc::new(DataPeer::new(crate::protocol::DEFAULT_MAX_MESSAGE_BYTES));
    // The first restart's resume is refused.
    peer.failing_control.lock().unwrap().push(1);
    let reactor = make_decoding_reactor(peer.clone());

    feed(
        &reactor,
        "data_98",
        MessageKind::Response,
        h264_result(1, 1, false),
    )
    .await;
    assert_eq!(peer.track_controls(), ["pause:out"]);

    feed(
        &reactor,
        "data_99",
        MessageKind::Response,
        h264_result(2, 2, false),
    )
    .await;
    assert_eq!(
        peer.track_controls(),
        ["pause:out", "pause:out", "resume:out"]
    );
}

/// A host that decodes its own tracks restarts one with a pause and a
/// resume of that track alone, and a paused track stays paused.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn restart_track_stream_pauses_and_resumes_that_track() {
    let peer = Arc::new(DataPeer::new(crate::protocol::DEFAULT_MAX_MESSAGE_BYTES));
    let reactor = make_decoding_reactor(peer.clone());
    reactor.restart_track_stream("out").unwrap();
    assert_eq!(peer.track_controls(), ["pause:out", "resume:out"]);

    reactor
        .state
        .lock()
        .unwrap()
        .paused_tracks
        .insert("other".into());
    reactor.restart_track_stream("other").unwrap();
    assert_eq!(peer.track_controls()[2..], ["resume:other", "pause:other"]);
    assert!(reactor.paused_tracks().contains("other"));
}

/// A track the caller paused stays paused: the core resumes it to restart
/// its stream and pauses it again, and its paused state is unchanged.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn restarting_a_paused_track_leaves_it_paused() {
    let peer = Arc::new(DataPeer::new(crate::protocol::DEFAULT_MAX_MESSAGE_BYTES));
    let reactor = make_decoding_reactor(peer.clone());
    reactor
        .state
        .lock()
        .unwrap()
        .paused_tracks
        .insert("out".into());

    feed(
        &reactor,
        "data_99",
        MessageKind::Response,
        h264_result(1, 1, false),
    )
    .await;

    assert_eq!(peer.track_controls(), ["resume:out", "pause:out"]);
    assert!(reactor.paused_tracks().contains("out"));
}

/// The decoder state lives with the data channel: a disconnect, as a
/// reconnect starts with, drops it, so the next
/// connection's stream must start again at a keyframe.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_reconnect_resets_every_tracks_decoder() {
    let peer = Arc::new(DataPeer::new(crate::protocol::DEFAULT_MAX_MESSAGE_BYTES));
    let reactor = make_decoding_reactor(peer.clone());
    feed(
        &reactor,
        "data_98",
        MessageKind::Response,
        h264_result(1, 1, true),
    )
    .await;
    // Another track's stream is lost, so it waits for a keyframe.
    feed(
        &reactor,
        "data_99",
        MessageKind::Response,
        data_server_message::Payload::StepResult(StepResult {
            tracks: vec![TrackFrames {
                track: "other".into(),
                kind: WireTrackKind::Video as i32,
                frames: vec![Frame {
                    encoding: FrameEncoding::H264 as i32,
                    ..raw_frame(2)
                }],
                rate: 0.0,
            }],
            ..Default::default()
        }),
    )
    .await;
    assert!(reactor.frame_decoders.awaiting_keyframe());

    reactor.disconnect(true).await.unwrap();
    assert!(!reactor.frame_decoders.awaiting_keyframe());

    // A keyframe of the closed connection, still queued, starts no stream
    // the next connection could run on from.
    feed(
        &reactor,
        "data_100",
        MessageKind::Response,
        h264_result(2, 2, true),
    )
    .await;
    assert!(!reactor.frame_decoders.awaiting_keyframe());

    reactor.handle_peer_event(PeerEvent::DataChannelOpen).await;
    reactor.state.lock().unwrap().status = ReactorStatus::Ready;
    let (stepping, sent) = start_step(&reactor, &peer, Value::Null, vec![]).await;
    feed(
        &reactor,
        &sent.request_id,
        MessageKind::Response,
        h264_result(2, 2, false),
    )
    .await;
    assert!(
        matches!(stepping.await.unwrap(), Err(CoreError::Decode(_))),
        "the old connection's decoder is gone"
    );
}

// ── Stepped generation: input buffering ─────────────────────────────────

/// Encoded frames ride the next step as they were pushed, in push order
/// beside the raw ones, under their own encoding; outside a stepped session
/// they are dropped.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn encoded_stepped_pushes_ride_the_next_step_as_they_are() {
    use crate::protocol::wire::v1::step::FrameEncoding;

    let peer = Arc::new(DataPeer::new(crate::protocol::DEFAULT_MAX_MESSAGE_BYTES));
    let reactor = make_ready_reactor(peer.clone(), GenerationMode::Stepped);
    let idr = [0, 0, 0, 1, 0x65, 1, 2];
    let p_frame = [0, 0, 0, 1, 0x41, 3];
    reactor.push_step_encoded_video_frame(
        "cam",
        FrameEncoding::H264,
        &idr,
        4,
        2,
        Some(b"k"),
        Some(7),
        true,
    );
    reactor.push_step_encoded_video_frame(
        "cam",
        FrameEncoding::H264,
        &p_frame,
        4,
        2,
        None,
        None,
        false,
    );
    reactor.push_video_frame("cam", &[1, 2, 3, 255], 1, 1);
    // Not a frame: dropped, not buffered.
    reactor.push_step_encoded_video_frame("cam", FrameEncoding::H264, &[], 4, 2, None, None, true);
    reactor.push_step_encoded_video_frame("cam", FrameEncoding::Raw, &idr, 4, 2, None, None, true);
    assert_eq!(reactor.step_inputs.len(), 3);

    let (_stepping, sent) = start_step(&reactor, &peer, Value::Null, vec![]).await;
    let Some(data_client_message::Payload::RequestStep(request)) = sent.payload else {
        panic!("expected a RequestStep");
    };
    let [cam] = request.media_input.try_into().unwrap();
    assert_eq!(cam.track, "cam");
    assert_eq!(
        cam.frames.iter().map(|f| f.encoding).collect::<Vec<_>>(),
        [
            FrameEncoding::H264 as i32,
            FrameEncoding::H264 as i32,
            FrameEncoding::Raw as i32
        ]
    );
    assert_eq!(cam.frames[0].data, idr);
    assert_eq!(cam.frames[0].shape, [2, 4, 3]);
    assert_eq!(cam.frames[0].metadata.as_deref(), Some(&b"k"[..]));
    assert_eq!(cam.frames[0].capture_time_us, Some(7));
    assert!(cam.frames[0].keyframe);
    assert_eq!(cam.frames[1].data, p_frame);
    assert!(!cam.frames[1].keyframe);
    assert_eq!(cam.frames[2].data, [3, 2, 1]);
    assert_eq!(
        cam.frames.iter().map(|f| f.keyframe).collect::<Vec<_>>(),
        [true, false, false]
    );

    let streamed = make_ready_reactor(peer.clone(), GenerationMode::Streamed);
    streamed.push_step_encoded_video_frame(
        "cam",
        FrameEncoding::H264,
        &idr,
        4,
        2,
        None,
        None,
        true,
    );
    assert!(streamed.step_inputs.is_empty());
}

/// In a stepped session the push calls buffer instead of reaching the
/// media transport, and the next step carries the buffer, per track in
/// push order, as RAW frames — then starts over empty.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn stepped_pushes_are_buffered_and_ride_the_next_step() {
    let peer = Arc::new(DataPeer::new(crate::protocol::DEFAULT_MAX_MESSAGE_BYTES));
    let reactor = make_ready_reactor(peer.clone(), GenerationMode::Stepped);
    // 1x1 BGRA pixels.
    reactor.push_video_frame("cam", &[1, 2, 3, 255], 1, 1);
    reactor.push_audio_frame("mic", &[10, 20, 11, 21], 48_000, 2);
    reactor.push_video_frame_with_metadata_at("cam", &[4, 5, 6, 255], 1, 1, b"t", 99);
    // The path a host binding takes, timing its own work.
    let generation = reactor.push_begin();
    reactor.push_video_frame_timed("cam", &[7, 8, 9, 255], 1, 1, b"", None, 2.0, generation);
    // Wrong size for 2x2: dropped, not buffered.
    reactor.push_video_frame("cam", &[0; 4], 2, 2);

    assert!(
        peer.pushed.lock().unwrap().is_empty(),
        "nothing went to RTP"
    );
    assert_eq!(reactor.step_inputs.len(), 4);

    let (stepping, sent) = start_step(&reactor, &peer, Value::Null, vec![]).await;
    let Some(data_client_message::Payload::RequestStep(request)) = sent.payload else {
        panic!("expected a RequestStep");
    };
    assert_eq!(request.media_input.len(), 2);
    let cam = &request.media_input[0];
    assert_eq!(cam.track, "cam");
    assert_eq!(cam.kind, WireTrackKind::Video as i32);
    assert_eq!(cam.frames.len(), 3);
    assert_eq!(cam.frames[0].data, [3, 2, 1]);
    assert_eq!(cam.frames[0].shape, [1, 1, 3]);
    assert_eq!(cam.frames[1].data, [6, 5, 4]);
    assert_eq!(cam.frames[1].metadata.as_deref(), Some(&b"t"[..]));
    assert_eq!(cam.frames[1].capture_time_us, Some(99));
    assert_eq!(cam.frames[2].data, [9, 8, 7]);
    assert_eq!(cam.frames[2].metadata, None);
    let mic = &request.media_input[1];
    assert_eq!(mic.track, "mic");
    assert_eq!(mic.kind, WireTrackKind::Audio as i32);
    assert_eq!(mic.frames[0].dtype, "int16");
    assert_eq!(mic.frames[0].shape, [2, 2]);
    assert!(reactor.step_inputs.is_empty());

    feed(
        &reactor,
        &sent.request_id,
        MessageKind::Response,
        data_server_message::Payload::StepResult(StepResult {
            step: 1,
            tracks: vec![],
            elapsed_seconds: 0.0,
            files: vec![],
            error: None,
            failure_code: StepFailureCode::Unspecified as i32,
            code: None,
            entry: None,
            data: None,
        }),
    )
    .await;
    stepping.await.unwrap().unwrap();

    // The next step starts from an empty buffer.
    let (stepping, sent) = start_step(&reactor, &peer, Value::Null, vec![]).await;
    let Some(data_client_message::Payload::RequestStep(request)) = sent.payload else {
        panic!("expected a RequestStep");
    };
    assert!(request.media_input.is_empty());
    stepping.abort();
}

#[test]
fn streamed_pushes_go_to_the_media_transport_as_before() {
    let peer = Arc::new(DataPeer::new(crate::protocol::DEFAULT_MAX_MESSAGE_BYTES));
    let reactor = make_ready_reactor(peer.clone(), GenerationMode::Streamed);
    reactor.push_video_frame("cam", &[1, 2, 3, 255], 1, 1);
    reactor.push_audio_frame("mic", &[1, 2], 48_000, 2);
    assert_eq!(*peer.pushed.lock().unwrap(), ["cam", "mic"]);
    assert!(reactor.step_inputs.is_empty());
}

/// A stepped session's input video tracks get a transceiver, to negotiate
/// the codec of their step frames, when encoding is on and this side handles
/// a codec for every video track: encodes one for each input and decodes one
/// for each output. Otherwise the offer falls back to raw frames and they get
/// none. Streamed negotiates every track, as before.
#[tokio::test]
async fn a_stepped_connect_negotiates_input_tracks_when_it_can_encode_them() {
    let h264 = || vec![StepCodec::H264];
    let prepared = |generation, echo, encode: Vec<StepCodec>, decode, encoding: bool| async move {
        let http = Arc::new(GenerationHttp::new(echo));
        let peer = Arc::new(SpyPeer::default());
        let reactor = make_generation_reactor(http.clone(), peer.clone(), generation);
        reactor.set_local_step_codecs(LocalStepCodecs { encode, decode });
        if !encoding {
            reactor.set_codec_preference(None, Some(vec![]));
        }
        connect_until_registration(&reactor, &http).await.abort();
        let tracks = peer.prepared_tracks.lock().unwrap().clone().unwrap();
        tracks.into_iter().map(|t| t.name).collect::<Vec<_>>()
    };
    let stepped = (GenerationMode::Stepped, Some("stepped"));
    assert_eq!(
        prepared(stepped.0, stepped.1, h264(), h264(), true).await,
        ["output", "input"]
    );
    assert_eq!(
        prepared(stepped.0, stepped.1, h264(), h264(), false).await,
        ["output"]
    );
    assert_eq!(
        prepared(stepped.0, stepped.1, vec![], h264(), true).await,
        ["output"]
    );
    // The output cannot be decoded: the offer falls back to raw frames.
    assert_eq!(
        prepared(stepped.0, stepped.1, h264(), vec![], true).await,
        ["output"]
    );
    assert_eq!(
        prepared(GenerationMode::Streamed, None, vec![], vec![], true).await,
        ["output", "input"]
    );
}

#[tokio::test]
async fn a_stepped_preset_connect_with_encoding_off_negotiates_no_input_track() {
    let http = Arc::new(GenerationHttp::new(Some("stepped")));
    let peer = Arc::new(SpyPeer::default());
    let mut opts = ReactorOptions::new("http://localhost", "test-model");
    opts.generation = GenerationMode::Stepped;
    opts.preset_tracks = Some(vec![
        output_track("output"),
        TrackCapability {
            name: "input".into(),
            kind: crate::protocol::session::TrackKind::Video,
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
    reactor.set_codec_preference(None, Some(vec![]));
    connect_until_registration(&reactor, &http).await.abort();
    let names: Vec<_> = peer
        .prepared_tracks
        .lock()
        .unwrap()
        .clone()
        .unwrap()
        .into_iter()
        .map(|t| t.name)
        .collect();
    assert_eq!(names, ["output"]);
}

/// What an engine offers for [`GenerationHttp`]'s tracks: a receive-only
/// output video section and a send-only input one, each listing VP8 then
/// H.264, and the data channel.
fn engine_offer() -> PreparedOffer {
    let section = |mid: &str, direction: &str| {
        format!(
            "m=video 9 UDP/TLS/RTP/SAVPF 96 97 100 101\r\na=mid:{mid}\r\na={direction}\r\n\
a=rtpmap:96 VP8/90000\r\na=rtpmap:97 rtx/90000\r\na=fmtp:97 apt=96\r\n\
a=rtpmap:100 H264/90000\r\na=rtpmap:101 rtx/90000\r\na=fmtp:101 apt=100\r\n"
        )
    };
    PreparedOffer {
        sdp_offer: format!(
            "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n{}{}\
m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=mid:2\r\n",
            section("0", "recvonly"),
            section("1", "sendonly"),
        ),
        track_mapping: vec![
            TrackMappingEntry {
                name: "output".into(),
                kind: SessionTrackKind::Video,
                direction: TrackDirection::Recvonly,
                mid: "0".into(),
            },
            TrackMappingEntry {
                name: "input".into(),
                kind: SessionTrackKind::Video,
                direction: TrackDirection::Sendonly,
                mid: "1".into(),
            },
        ],
    }
}

/// A side that encodes and decodes every step codec.
fn every_codec() -> LocalStepCodecs {
    LocalStepCodecs {
        encode: StepCodec::DEFAULT_ORDER.to_vec(),
        decode: StepCodec::DEFAULT_ORDER.to_vec(),
    }
}

/// A side with no step codec sends the engine's offer as it is, and every
/// track stays raw, even against a runtime that would encode them.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_side_without_step_codecs_sends_the_engine_offer_unchanged() {
    let peer = Arc::new(ChannelsPeer {
        offer: engine_offer(),
        ..ChannelsPeer::new(true)
    });
    let http = Arc::new(GenerationHttp::completing(Some("stepped")));
    let (reactor, result) = connect_with(http.clone(), peer, GenerationMode::Stepped).await;
    result.unwrap();
    assert_eq!(http.offers.lock().unwrap()[0], engine_offer().sdp_offer);
    assert!(reactor
        .step_frame_encodings()
        .values()
        .all(|e| *e == FrameEncoding::Raw));
}

/// The m-line of the section `mid` names in `sdp`.
fn m_line(sdp: &str, mid: &str) -> String {
    let at = sdp.find(&format!("a=mid:{mid}\r\n")).unwrap();
    let start = sdp[..at].rfind("m=").unwrap();
    sdp[start..].lines().next().unwrap().to_string()
}

/// The stepped offer declares encoded step frames with both tracks' sections,
/// H.264 first; the answer settles each track's encoding, which the core
/// exposes; and a changed preference reorders the next offer, a reconnect's.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_stepped_connect_negotiates_each_tracks_step_frame_encoding() {
    let answer = "v=0\r\no=- 9 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n\
a=x-reactor-encoded-step-frames\r\n\
m=video 9 UDP/TLS/RTP/SAVPF 100 101\r\na=mid:0\r\na=sendonly\r\n\
a=rtpmap:100 H264/90000\r\na=rtpmap:101 rtx/90000\r\n\
m=video 9 UDP/TLS/RTP/SAVPF 96 97\r\na=mid:1\r\na=recvonly\r\n\
a=rtpmap:96 VP8/90000\r\na=rtpmap:97 rtx/90000\r\n\
m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\na=mid:2\r\n";
    let http = Arc::new(GenerationHttp {
        answer: answer.into(),
        ..GenerationHttp::completing(Some("stepped"))
    });
    let peer = Arc::new(ChannelsPeer {
        offer: engine_offer(),
        ..ChannelsPeer::new(true)
    });
    let (reactor, result) =
        connect_with_codecs(http.clone(), peer, GenerationMode::Stepped, every_codec()).await;
    result.unwrap();

    let offer = http.offers.lock().unwrap()[0].clone();
    assert!(offer.contains("t=0 0\r\na=x-reactor-encoded-step-frames\r\nm=video"));
    assert_eq!(
        m_line(&offer, "0"),
        "m=video 9 UDP/TLS/RTP/SAVPF 100 96 97 101"
    );
    assert_eq!(
        m_line(&offer, "1"),
        "m=video 9 UDP/TLS/RTP/SAVPF 100 96 97 101"
    );

    assert_eq!(reactor.step_frame_encoding("output"), FrameEncoding::H264);
    assert_eq!(reactor.step_frame_encoding("input"), FrameEncoding::Vp8);
    assert_eq!(reactor.step_frame_encoding("unknown"), FrameEncoding::Raw);
    assert_eq!(reactor.step_frame_encodings().len(), 2);

    // The live connection keeps what it negotiated; the reconnect's offer
    // carries the new order.
    reactor.set_codec_preference(Some("input"), Some(vec![StepCodec::Vp8, StepCodec::H264]));
    let offers_before = http.offers.lock().unwrap().len();
    assert_eq!(offers_before, 1);
    tokio::time::timeout(Duration::from_secs(1), reactor.reconnect(None))
        .await
        .expect("reconnect() must not hang")
        .unwrap();
    let offer = http.offers.lock().unwrap()[1].clone();
    assert_eq!(
        m_line(&offer, "0"),
        "m=video 9 UDP/TLS/RTP/SAVPF 100 96 97 101"
    );
    assert_eq!(
        m_line(&offer, "1"),
        "m=video 9 UDP/TLS/RTP/SAVPF 96 100 97 101"
    );

    reactor.disconnect(false).await.unwrap();
    assert!(reactor.step_frame_encodings().is_empty());
}

/// A runtime that does not encode step frames answers without the
/// attribute: every track stays raw.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn an_answer_without_the_attribute_leaves_every_track_raw() {
    let peer = Arc::new(ChannelsPeer {
        offer: engine_offer(),
        ..ChannelsPeer::new(true)
    });
    let http = Arc::new(GenerationHttp::completing(Some("stepped")));
    let (reactor, result) =
        connect_with_codecs(http, peer, GenerationMode::Stepped, every_codec()).await;
    result.unwrap();
    let encodings = reactor.step_frame_encodings();
    assert_eq!(encodings.len(), 2);
    assert!(encodings.values().all(|e| *e == FrameEncoding::Raw));
}

/// A streamed session sends the engine's offer as it is.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_streamed_offer_is_sent_unchanged() {
    let peer = Arc::new(ChannelsPeer {
        offer: engine_offer(),
        ..ChannelsPeer::new(true)
    });
    let http = Arc::new(GenerationHttp::completing(None));
    let (reactor, result) = connect_with(http.clone(), peer, GenerationMode::Streamed).await;
    result.unwrap();
    assert_eq!(http.offers.lock().unwrap()[0], engine_offer().sdp_offer);
    assert!(reactor.step_frame_encodings().is_empty());
}

#[tokio::test]
async fn disconnecting_drops_frames_buffered_for_the_session() {
    let peer = Arc::new(DataPeer::new(crate::protocol::DEFAULT_MAX_MESSAGE_BYTES));
    let reactor = make_ready_reactor(peer, GenerationMode::Stepped);
    reactor.push_video_frame("cam", &[1, 2, 3, 255], 1, 1);
    reactor.disconnect(false).await.unwrap();
    assert!(reactor.step_inputs.is_empty());
}

/// A binding runs the send on another thread, after `begin_step` returns on
/// the caller's: the frames are the ones buffered when it was called, and a
/// frame pushed in between goes to the next step.
#[tokio::test]
async fn begin_step_takes_the_inputs_buffered_when_it_is_called() {
    let peer = Arc::new(DataPeer::new(usize::MAX));
    let reactor = make_ready_reactor(peer.clone(), GenerationMode::Stepped);
    reactor
        .step_inputs
        .push("cam", WireTrackKind::Video, raw_frame(1), usize::MAX);

    let request = reactor.begin_step(Value::Null, vec![]).unwrap();
    reactor
        .step_inputs
        .push("cam", WireTrackKind::Video, raw_frame(2), usize::MAX);

    let sent = peer.sent();
    let Some(data_client_message::Payload::RequestStep(step)) = &sent[0].payload else {
        panic!("expected the step written, got {:?}", sent[0].payload);
    };
    let firsts: Vec<u8> = step.media_input[0]
        .frames
        .iter()
        .map(|f| f.data[0])
        .collect();
    assert_eq!(firsts, [1]);
    assert_eq!(
        reactor.step_inputs.len(),
        1,
        "the later frame waits for the next step"
    );
    drop(reactor.send_step(request));
}

/// Against a runtime that takes `PushFrames`, the step is on the channel
/// once `begin_step` returns, while the binding has yet to send it on its
/// worker: a frame pushed then goes out behind it, for the next step.
#[tokio::test]
async fn a_push_after_begin_step_goes_out_behind_the_step() {
    let peer = Arc::new(DataPeer::new(usize::MAX));
    let reactor = make_ready_reactor(peer.clone(), GenerationMode::Stepped);
    report_runtime(&reactor, "3.11.8");

    let request = reactor.begin_step(Value::Null, vec![]).unwrap();
    reactor.push_video_frame("cam", &[7, 7, 7, 255], 1, 1);

    let sent = peer.sent();
    assert_eq!(sent.len(), 2);
    assert!(matches!(
        sent[0].payload,
        Some(data_client_message::Payload::RequestStep(_))
    ));
    assert!(matches!(
        sent[1].payload,
        Some(data_client_message::Payload::PushFrames(_))
    ));
    drop(reactor.send_step(request));
}

/// `check_step` refuses what `begin_step` would, for the session or for the
/// state and commands, and takes nothing either way: the frames buffered stay
/// for the step that follows.
#[tokio::test]
async fn check_step_refuses_as_begin_step_does_and_takes_nothing() {
    let peer = Arc::new(DataPeer::new(usize::MAX));
    let reactor = make_ready_reactor(peer.clone(), GenerationMode::Stepped);
    reactor
        .step_inputs
        .push("cam", WireTrackKind::Video, raw_frame(1), usize::MAX);
    let command = |data: Value| vec![StepCommand::new("go", data)];

    reactor
        .check_step(
            &serde_json::json!({ "prompt": "a cat" }),
            &command(Value::Null),
        )
        .unwrap();
    for (state, commands) in [
        (serde_json::json!(["prompt"]), vec![]),
        (Value::Null, command(serde_json::json!([1]))),
    ] {
        let checked = reactor.check_step(&state, &commands).unwrap_err();
        let Err(begun) = reactor.begin_step(state, commands) else {
            panic!("begin_step took a step check_step refused");
        };
        assert_eq!(checked.to_string(), begun.to_string());
    }
    assert_eq!(
        reactor.step_inputs.len(),
        1,
        "a refused step takes no frames"
    );

    reactor.state.lock().unwrap().status = ReactorStatus::Connecting;
    assert!(matches!(
        reactor.check_step(&Value::Null, &[]),
        Err(CoreError::InvalidState(_))
    ));

    let streamed = make_ready_reactor(peer, GenerationMode::Streamed);
    assert!(matches!(
        streamed.check_step(&Value::Null, &[]),
        Err(CoreError::InvalidState(_))
    ));
    assert_eq!(reactor.step_inputs.len(), 1);
}

/// Report the runtime's version as a ready session's `server_info` does, on
/// a connection whose answer repeated `a=x-reactor-encoded-step-frames`.
fn report_runtime(reactor: &Reactor, version: &str) {
    reactor.state.lock().unwrap().encoded_step_frames = true;
    reactor.state.lock().unwrap().session_info = Some(
        serde_json::from_value(json!({
            "session_id": "sess_1",
            "state": "ACTIVE",
            "server_info": {"server_version": version},
        }))
        .unwrap(),
    );
}

/// The frames a `PushFrames` carries, as `(track, first byte)` pairs.
fn pushed(message: &DataClientMessage) -> Vec<(String, u8)> {
    let Some(data_client_message::Payload::PushFrames(push)) = &message.payload else {
        panic!("expected a PushFrames, got {:?}", message.payload);
    };
    push.tracks
        .iter()
        .flat_map(|t| t.frames.iter().map(|f| (t.track.clone(), f.data[0])))
        .collect()
}

/// A runtime recent enough for `PushFrames` on a connection that did not
/// negotiate encoded step frames (encoding turned off, or an answer without
/// the attribute) need not take them: the frame waits for the next step.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_push_waits_for_the_step_without_encoded_step_frames_negotiated() {
    let peer = Arc::new(DataPeer::new(crate::protocol::DEFAULT_MAX_MESSAGE_BYTES));
    let reactor = make_ready_reactor(peer.clone(), GenerationMode::Stepped);
    report_runtime(&reactor, "3.11.8");
    reactor.state.lock().unwrap().encoded_step_frames = false;

    reactor.push_video_frame("cam", &[7, 7, 7, 255], 1, 1);

    assert!(peer.sent().is_empty());
    assert_eq!(reactor.step_inputs.len(), 1);
}

/// Against a runtime that takes `PushFrames`, a push goes out at once, as a
/// notification on the data channel, ahead of the command and the step sent
/// after it, and the step carries no frames of its own.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn a_push_goes_out_before_the_command_and_the_step_sent_after_it() {
    let peer = Arc::new(DataPeer::new(crate::protocol::DEFAULT_MAX_MESSAGE_BYTES));
    let reactor = make_ready_reactor(peer.clone(), GenerationMode::Stepped);
    report_runtime(&reactor, "3.11.8");

    reactor.push_video_frame("cam", &[7, 7, 7, 255], 1, 1);
    reactor.push_audio_frame("mic", &[9], 48_000, 1);
    assert!(reactor.step_inputs.is_empty(), "nothing waits for the step");
    let sent = peer.sent();
    assert_eq!(sent.len(), 2);
    for push in &sent {
        assert_eq!(push.kind, MessageKind::Notification as i32);
        assert!(push.request_id.is_empty());
    }
    assert_eq!(pushed(&sent[0]), [("cam".to_string(), 7)]);
    assert_eq!(pushed(&sent[1]), [("mic".to_string(), 9)]);

    let r = reactor.clone();
    let commanding = tokio::spawn(async move { r.send_command("reset", json!({}), None).await });
    tokio::time::timeout(Duration::from_secs(1), async {
        while peer.sent().len() < 3 {
            tokio::time::sleep(Duration::from_millis(2)).await;
        }
    })
    .await
    .expect("the command should be sent");
    let (_stepping, step) = start_step(&reactor, &peer, Value::Null, vec![]).await;
    commanding.abort();

    let sent = peer.sent();
    assert_eq!(sent.len(), 4);
    assert!(matches!(
        sent[2].payload,
        Some(data_client_message::Payload::Command(ref c)) if c.r#type == "reset"
    ));
    let Some(data_client_message::Payload::RequestStep(request)) = step.payload else {
        panic!("expected a RequestStep, got {:?}", step.payload);
    };
    assert!(request.media_input.is_empty());
}

/// A runtime older than `PushFrames`, or one whose version is unknown, takes
/// input frames only in a step: they are buffered and ride it as its
/// `media_input`, with nothing sent at push time.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn pushes_to_an_older_runtime_ride_the_next_step() {
    for version in [Some("3.11.7"), Some("0.0.0"), None] {
        let peer = Arc::new(DataPeer::new(crate::protocol::DEFAULT_MAX_MESSAGE_BYTES));
        let reactor = make_ready_reactor(peer.clone(), GenerationMode::Stepped);
        if let Some(version) = version {
            report_runtime(&reactor, version);
        }

        reactor.push_video_frame("cam", &[7, 7, 7, 255], 1, 1);
        assert!(peer.sent().is_empty(), "{version:?}");
        assert_eq!(reactor.step_inputs.len(), 1, "{version:?}");

        let (_stepping, step) = start_step(&reactor, &peer, Value::Null, vec![]).await;
        let Some(data_client_message::Payload::RequestStep(request)) = step.payload else {
            panic!("expected a RequestStep, got {:?}", step.payload);
        };
        let [cam] = request.media_input.try_into().unwrap();
        assert_eq!(cam.track, "cam", "{version:?}");
        assert_eq!(cam.frames[0].data[0], 7, "{version:?}");
    }
}

/// Frames pushed before the session is ready wait for it, and go out ahead of
/// the first frame pushed once it is, so the track keeps its order.
#[test]
fn frames_pushed_before_the_session_is_ready_go_first() {
    let peer = Arc::new(DataPeer::new(crate::protocol::DEFAULT_MAX_MESSAGE_BYTES));
    let reactor = make_ready_reactor(peer.clone(), GenerationMode::Stepped);
    report_runtime(&reactor, "3.11.8");
    reactor.state.lock().unwrap().status = ReactorStatus::Connecting;
    reactor.push_video_frame("cam", &[1, 1, 1, 255], 1, 1);
    reactor.push_video_frame("cam", &[2, 2, 2, 255], 1, 1);
    assert!(peer.sent().is_empty());

    reactor.state.lock().unwrap().status = ReactorStatus::Ready;
    reactor.push_video_frame("cam", &[3, 3, 3, 255], 1, 1);
    let sent = peer.sent();
    assert_eq!(sent.len(), 2);
    assert_eq!(
        pushed(&sent[0]),
        [("cam".to_string(), 1), ("cam".to_string(), 2)]
    );
    assert_eq!(pushed(&sent[1]), [("cam".to_string(), 3)]);
    assert!(reactor.step_inputs.is_empty());
}

/// A push larger than the channel takes is dropped, not kept for the step,
/// and raised as an error event: no caller waits for it.
#[test]
fn a_push_over_the_channel_limit_is_dropped_and_raised() {
    let peer = Arc::new(DataPeer::new(64));
    let reactor = make_ready_reactor(peer.clone(), GenerationMode::Stepped);
    report_runtime(&reactor, "3.11.8");
    let mut events = reactor.subscribe();
    reactor.push_step_video_frame("cam", &[0; 300], 10, 10, PixelLayout::Rgb, None, None);
    assert!(peer.sent().is_empty());
    assert!(reactor.step_inputs.is_empty());
    match events.try_recv() {
        Ok(ReactorEvent::Error(error)) => {
            assert_eq!(error.details.code, crate::error::codes::MESSAGE_TOO_LARGE);
            assert_eq!(error.details.operation.as_deref(), Some("push_frames"));
        }
        other => panic!("expected an error event, got {other:?}"),
    }
}

/// An `input_decode_failed` notification is raised as an error event naming
/// its track, so the host encoding that track knows which one must start
/// over at a keyframe.
#[tokio::test]
async fn input_decode_failed_is_raised_with_its_track() {
    let peer = Arc::new(DataPeer::new(crate::protocol::DEFAULT_MAX_MESSAGE_BYTES));
    let reactor = make_ready_reactor(peer.clone(), GenerationMode::Stepped);
    let mut events = reactor.subscribe();
    let decode_failed = |track: Option<&str>| {
        data_server_message::Payload::Error(crate::protocol::wire::v1::common::Error {
            code: crate::step::INPUT_DECODE_FAILED.into(),
            message: "cannot decode".into(),
            track: track.map(Into::into),
        })
    };

    feed(
        &reactor,
        "",
        MessageKind::Notification,
        decode_failed(Some("cam")),
    )
    .await;
    match events.try_recv() {
        Ok(ReactorEvent::Error(error)) => {
            assert_eq!(error.details.code, crate::step::INPUT_DECODE_FAILED);
            assert_eq!(error.details.track.as_deref(), Some("cam"));
            let json = serde_json::to_value(&error).unwrap();
            assert_eq!(json["track"], "cam");
        }
        other => panic!("expected an error event, got {other:?}"),
    }

    feed(&reactor, "", MessageKind::Notification, decode_failed(None)).await;
    match events.try_recv() {
        Ok(ReactorEvent::Error(error)) => {
            assert_eq!(error.details.track, None);
            let json = serde_json::to_value(&error).unwrap();
            assert!(json.get("track").is_none());
        }
        other => panic!("expected an error event, got {other:?}"),
    }
}

/// A buffered encoded track whose keyframe the buffer's limit evicted cannot
/// be sent: drained by the next push once the session is ready, it is dropped
/// and raised as an error event rather than only logged.
#[test]
fn a_buffered_window_that_cannot_be_sent_is_raised() {
    let peer = Arc::new(DataPeer::new(crate::protocol::DEFAULT_MAX_MESSAGE_BYTES));
    let reactor = make_ready_reactor(peer.clone(), GenerationMode::Stepped);
    report_runtime(&reactor, "3.11.8");
    reactor.state.lock().unwrap().status = ReactorStatus::Connecting;
    let mut events = reactor.subscribe();
    // Over half a message each: the second evicts the first, its keyframe.
    let half = crate::protocol::DEFAULT_MAX_MESSAGE_BYTES / 2 + 1;
    reactor.push_step_encoded_video_frame(
        "cam",
        FrameEncoding::H264,
        &vec![1; half],
        2,
        2,
        None,
        None,
        true,
    );
    reactor.push_step_encoded_video_frame(
        "cam",
        FrameEncoding::H264,
        &vec![2; half],
        2,
        2,
        None,
        None,
        false,
    );
    while events.try_recv().is_ok() {}

    reactor.state.lock().unwrap().status = ReactorStatus::Ready;
    reactor.push_video_frame("mic", &[3, 3, 3, 255], 1, 1);
    match events.try_recv() {
        Ok(ReactorEvent::Error(error)) => {
            assert_eq!(error.details.code, crate::error::codes::STEP_REFUSED);
            assert_eq!(error.details.operation.as_deref(), Some("push_frames"));
        }
        other => panic!("expected an error event, got {other:?}"),
    }
    assert!(reactor.step_inputs.is_empty());
    let sent = peer.sent();
    assert_eq!(sent.len(), 1, "only the new push goes out");
    assert_eq!(pushed(&sent[0]), [("mic".to_string(), 3)]);
}

// ── Stepped generation: encoded step frames on reactor-webrtc's codecs ──

/// The core's own encoders and decoders, on every codec this process can run
/// both ways: a pushed frame is encoded and a result frame decoded, each
/// track one stream for the connection, starting at a keyframe.
#[cfg(feature = "webrtc-codecs")]
mod webrtc_codecs {
    use super::*;
    use crate::step::{FrameDecoders, StepCodec};
    use reactor_webrtc::codec::{VideoCodecs, VideoEncoderConfig};

    const WIDTH: u32 = 128;
    const HEIGHT: u32 = 96;

    /// A smooth RGB picture that shifts with `n`, which every codec keeps
    /// close at its default bitrate.
    fn picture(n: u8) -> Vec<u8> {
        let mut rgb = Vec::with_capacity((WIDTH * HEIGHT * 3) as usize);
        for y in 0..HEIGHT {
            for x in 0..WIDTH {
                rgb.push((x * 2) as u8 ^ n);
                rgb.push((y * 2) as u8);
                rgb.push(128u8.wrapping_add(n * 8));
            }
        }
        rgb
    }

    /// The mean absolute difference between two pictures of the same size.
    fn distance(a: &[u8], b: &[u8]) -> f64 {
        assert_eq!(a.len(), b.len());
        let sum: u64 = a
            .iter()
            .zip(b)
            .map(|(x, y)| u64::from(x.abs_diff(*y)))
            .sum();
        sum as f64 / a.len() as f64
    }

    /// The codecs this process both encodes and decodes, as the core offers
    /// them. H.264 is among them wherever the platform has a codec for it.
    fn round_trip_codecs(reactor: &Reactor) -> Vec<StepCodec> {
        let local = reactor.local_step_codecs.lock().unwrap().clone();
        local
            .encode
            .into_iter()
            .filter(|codec| local.decode.contains(codec))
            .collect()
    }

    /// A ready stepped reactor whose connection negotiated `codec` for the
    /// input track `cam` and the output track `out`, against a runtime that
    /// takes `PushFrames`.
    fn encoding_reactor(peer: Arc<DataPeer>, codec: StepCodec) -> Arc<Reactor> {
        let reactor = make_ready_reactor(peer, GenerationMode::Stepped);
        report_runtime(&reactor, "3.11.8");
        negotiate(&reactor, codec);
        reactor
    }

    fn negotiate(reactor: &Reactor, codec: StepCodec) {
        let mut state = reactor.state.lock().unwrap();
        state.status = ReactorStatus::Ready;
        state.encoded_step_frames = true;
        state.step_frame_encodings = [
            ("cam".to_string(), codec.frame_encoding()),
            ("out".to_string(), codec.frame_encoding()),
        ]
        .into();
    }

    fn push(reactor: &Reactor, n: u8) {
        reactor.push_step_video_frame(
            "cam",
            &picture(n),
            WIDTH,
            HEIGHT,
            PixelLayout::Rgb,
            Some(&[n]),
            None,
        );
    }

    /// The frames of every `PushFrames` sent since `from`.
    fn pushed_frames(peer: &DataPeer, from: usize) -> Vec<Frame> {
        peer.sent()[from..]
            .iter()
            .flat_map(|message| match &message.payload {
                Some(data_client_message::Payload::PushFrames(push)) => push
                    .tracks
                    .iter()
                    .flat_map(|t| {
                        assert_eq!(t.track, "cam");
                        t.frames.clone()
                    })
                    .collect::<Vec<_>>(),
                other => panic!("expected a PushFrames, got {other:?}"),
            })
            .collect()
    }

    fn decoders_for(codec: StepCodec) -> FrameDecoders {
        let mut decoders = FrameDecoders::default();
        decoders.register_webrtc(&VideoCodecs::new().unwrap(), &[codec]);
        decoders
    }

    /// Each pushed frame goes out encoded in the track's negotiated codec, as
    /// one stream: a keyframe first, then frames that follow on from it, which
    /// decode back to the pictures pushed, with their metadata.
    #[test]
    fn pushed_frames_go_out_encoded_as_one_stream_per_track() {
        let probe = make_ready_reactor(
            Arc::new(DataPeer::new(crate::protocol::DEFAULT_MAX_MESSAGE_BYTES)),
            GenerationMode::Stepped,
        );
        let codecs = round_trip_codecs(&probe);
        assert!(!codecs.is_empty(), "no codec runs both ways here");
        // VideoToolbox runs H.264 both ways on Apple platforms.
        #[cfg(target_vendor = "apple")]
        assert!(codecs.contains(&StepCodec::H264), "{codecs:?}");
        for codec in codecs {
            let peer = Arc::new(DataPeer::new(crate::protocol::DEFAULT_MAX_MESSAGE_BYTES));
            let reactor = encoding_reactor(peer.clone(), codec);
            for n in 0..3 {
                push(&reactor, n);
            }
            let frames = pushed_frames(&peer, 0);
            assert_eq!(frames.len(), 3, "{codec:?}");
            assert_eq!(
                frames.iter().map(|f| f.keyframe).collect::<Vec<_>>(),
                [true, false, false],
                "{codec:?}"
            );
            let decoders = decoders_for(codec);
            for (n, frame) in frames.into_iter().enumerate() {
                assert_eq!(frame.encoding, codec.frame_encoding() as i32, "{codec:?}");
                assert_eq!(frame.shape, [HEIGHT, WIDTH, 3], "{codec:?}");
                assert!(
                    frame.data.len() < picture(0).len() / 4,
                    "{codec:?}: {} bytes",
                    frame.data.len()
                );
                assert_eq!(frame.metadata.as_deref(), Some(&[n as u8][..]));
                let decoded = decoders.decode_frame("cam", frame).unwrap();
                assert_eq!(decoded.shape, [HEIGHT, WIDTH, 3], "{codec:?}");
                let error = distance(&decoded.data, &picture(n as u8));
                assert!(error < 12.0, "{codec:?} frame {n}: off by {error}");
            }
        }
    }

    /// An `input_decode_failed` for the track makes its next pushed frame a
    /// keyframe, and only that one.
    #[tokio::test]
    async fn input_decode_failed_makes_the_next_push_a_keyframe() {
        let peer = Arc::new(DataPeer::new(crate::protocol::DEFAULT_MAX_MESSAGE_BYTES));
        let probe = make_ready_reactor(peer.clone(), GenerationMode::Stepped);
        let codec = round_trip_codecs(&probe)[0];
        let reactor = encoding_reactor(peer.clone(), codec);
        push(&reactor, 0);
        push(&reactor, 1);
        feed(
            &reactor,
            "",
            MessageKind::Notification,
            data_server_message::Payload::Error(crate::protocol::wire::v1::common::Error {
                code: crate::step::INPUT_DECODE_FAILED.into(),
                message: "cannot decode".into(),
                track: Some("cam".into()),
            }),
        )
        .await;
        push(&reactor, 2);
        push(&reactor, 3);
        let keyframes: Vec<bool> = pushed_frames(&peer, 0).iter().map(|f| f.keyframe).collect();
        assert_eq!(keyframes, [true, false, true, false], "{codec:?}");
    }

    /// Frames encoded and not sent (here, over the channel's limit) leave a
    /// gap in the stream: the next frame sent is a keyframe.
    #[test]
    fn a_push_the_channel_did_not_take_makes_the_next_a_keyframe() {
        let peer = Arc::new(DataPeer::new(crate::protocol::DEFAULT_MAX_MESSAGE_BYTES));
        let probe = make_ready_reactor(peer.clone(), GenerationMode::Stepped);
        let codec = round_trip_codecs(&probe)[0];
        let reactor = encoding_reactor(peer.clone(), codec);
        push(&reactor, 0);
        peer.max.store(16, std::sync::atomic::Ordering::SeqCst);
        push(&reactor, 1);
        peer.max.store(
            crate::protocol::DEFAULT_MAX_MESSAGE_BYTES,
            std::sync::atomic::Ordering::SeqCst,
        );
        push(&reactor, 2);
        push(&reactor, 3);
        let keyframes: Vec<bool> = pushed_frames(&peer, 0).iter().map(|f| f.keyframe).collect();
        assert_eq!(keyframes, [true, true, false], "{codec:?}");
    }

    /// A reconnect starts every pushed stream over: the first frame on the
    /// new connection is a keyframe, from a fresh encoder, and encoded frames
    /// still buffered for the old one are dropped while raw ones stay.
    #[tokio::test]
    async fn a_reconnect_starts_each_pushed_stream_at_a_keyframe() {
        let peer = Arc::new(DataPeer::new(crate::protocol::DEFAULT_MAX_MESSAGE_BYTES));
        let probe = make_ready_reactor(peer.clone(), GenerationMode::Stepped);
        let codec = round_trip_codecs(&probe)[0];
        let reactor = encoding_reactor(peer.clone(), codec);
        push(&reactor, 0);
        push(&reactor, 1);
        // Not ready: these wait in the buffer.
        reactor.state.lock().unwrap().status = ReactorStatus::Connecting;
        push(&reactor, 2);
        reactor.push_audio_frame("mic", &[9], 48_000, 1);
        assert_eq!(reactor.step_inputs.len(), 2);

        reactor.teardown(true, false).await;
        assert_eq!(reactor.step_inputs.len(), 1, "the raw frame stays");
        let sent = peer.sent().len();
        negotiate(&reactor, codec);
        push(&reactor, 3);
        push(&reactor, 4);
        let after = &peer.sent()[sent..];
        assert_eq!(after.len(), 3, "the raw frame, then two pushes");
        let Some(data_client_message::Payload::PushFrames(raw)) = &after[0].payload else {
            panic!("expected the buffered raw frame first");
        };
        assert_eq!(raw.tracks[0].track, "mic");
        let keyframes: Vec<bool> = pushed_frames(&peer, sent + 1)
            .iter()
            .map(|f| f.keyframe)
            .collect();
        assert_eq!(keyframes, [true, false], "{codec:?}");
    }

    /// A frame the host encoded takes the track's stream over: the next raw
    /// frame pushed on it starts the core's own stream again at a keyframe.
    #[test]
    fn a_host_encoded_frame_restarts_the_track_at_a_keyframe() {
        let peer = Arc::new(DataPeer::new(crate::protocol::DEFAULT_MAX_MESSAGE_BYTES));
        let probe = make_ready_reactor(peer.clone(), GenerationMode::Stepped);
        let codec = round_trip_codecs(&probe)[0];
        let reactor = encoding_reactor(peer.clone(), codec);
        push(&reactor, 0);
        push(&reactor, 1);
        reactor.push_step_encoded_video_frame(
            "cam",
            codec.frame_encoding(),
            &[0, 0, 0, 1],
            WIDTH,
            HEIGHT,
            None,
            None,
            false,
        );
        push(&reactor, 2);
        push(&reactor, 3);
        let frames = pushed_frames(&peer, 0);
        assert_eq!(frames.len(), 5, "{codec:?}");
        assert_eq!(frames[2].data, [0, 0, 0, 1], "{codec:?}");
        let keyframes: Vec<bool> = frames.iter().map(|f| f.keyframe).collect();
        assert_eq!(keyframes, [true, false, false, true, false], "{codec:?}");
    }

    /// Frames pushed while the connection tears down, again and again, on
    /// another thread: neither side waits on the other for good. A push holds
    /// the encoders while it sends, which takes `state`; the teardown must
    /// not wait for the encoders while it holds `state`.
    #[test]
    fn pushes_racing_teardowns_never_deadlock() {
        use std::sync::atomic::{AtomicBool, Ordering};

        let peer = Arc::new(DataPeer::new(crate::protocol::DEFAULT_MAX_MESSAGE_BYTES));
        let probe = make_ready_reactor(peer.clone(), GenerationMode::Stepped);
        let codec = round_trip_codecs(&probe)[0];
        let reactor = encoding_reactor(peer, codec);
        let stop = Arc::new(AtomicBool::new(false));
        let pusher = std::thread::spawn({
            let reactor = reactor.clone();
            let stop = stop.clone();
            move || {
                let mut n = 0u8;
                while !stop.load(Ordering::SeqCst) {
                    push(&reactor, n);
                    reactor.clear_step_inputs();
                    n = n.wrapping_add(1);
                }
            }
        });
        let (done, finished) = std::sync::mpsc::channel();
        std::thread::spawn({
            let reactor = reactor.clone();
            move || {
                let runtime = tokio::runtime::Builder::new_current_thread()
                    .enable_all()
                    .build()
                    .unwrap();
                for _ in 0..200 {
                    runtime.block_on(reactor.teardown(true, false));
                    negotiate(&reactor, codec);
                }
                done.send(()).unwrap();
            }
        });
        let result = finished.recv_timeout(std::time::Duration::from_secs(60));
        stop.store(true, Ordering::SeqCst);
        result.expect("a push and a teardown deadlocked");
        pusher.join().unwrap();
    }

    /// A track the connection carries raw, or a frame the codec cannot take
    /// (an odd size), goes out raw.
    #[test]
    fn raw_tracks_and_frames_the_encoder_refuses_go_raw() {
        let peer = Arc::new(DataPeer::new(crate::protocol::DEFAULT_MAX_MESSAGE_BYTES));
        let probe = make_ready_reactor(peer.clone(), GenerationMode::Stepped);
        let codec = round_trip_codecs(&probe)[0];
        let reactor = encoding_reactor(peer.clone(), codec);
        reactor.push_step_video_frame("cam", &[1; 3 * 3 * 3], 3, 3, PixelLayout::Rgb, None, None);
        reactor
            .state
            .lock()
            .unwrap()
            .step_frame_encodings
            .insert("cam".into(), FrameEncoding::Raw);
        push(&reactor, 0);
        let frames = pushed_frames(&peer, 0);
        assert_eq!(frames.len(), 2);
        for frame in frames {
            assert_eq!(frame.encoding, FrameEncoding::Raw as i32);
        }
    }

    /// Output frames encoded as the runtime encodes them, one stream across
    /// results starting at a keyframe.
    struct RuntimeEncoder(reactor_webrtc::codec::VideoEncoder, StepCodec);

    impl RuntimeEncoder {
        fn new(codec: StepCodec) -> Self {
            let config = VideoEncoderConfig::new(codec.video_codec(), WIDTH, HEIGHT);
            Self(VideoCodecs::new().unwrap().encoder(&config).unwrap(), codec)
        }

        fn result(&mut self, step: u64, n: u8) -> data_server_message::Payload {
            let encoded = self
                .0
                .encode(
                    &picture(n),
                    WIDTH,
                    HEIGHT,
                    reactor_webrtc::PixelFormat::Rgb24,
                    i64::from(n) * 33_333,
                )
                .unwrap()
                .unwrap();
            data_server_message::Payload::StepResult(StepResult {
                step,
                tracks: vec![TrackFrames {
                    track: "out".into(),
                    kind: WireTrackKind::Video as i32,
                    frames: vec![Frame {
                        encoding: self.1.frame_encoding() as i32,
                        dtype: "uint8".into(),
                        shape: vec![HEIGHT, WIDTH, 3],
                        data: encoded.data,
                        metadata: None,
                        capture_time_us: None,
                        keyframe: encoded.key_frame,
                    }],
                    rate: 30.0,
                }],
                ..Default::default()
            })
        }
    }

    async fn step_with(
        reactor: &Arc<Reactor>,
        peer: &DataPeer,
        result: data_server_message::Payload,
    ) -> Result<StepOutput, CoreError> {
        let (stepping, sent) = start_step(reactor, peer, Value::Null, vec![]).await;
        feed(reactor, &sent.request_id, MessageKind::Response, result).await;
        stepping.await.unwrap()
    }

    /// Step results decode on the core's own decoder per output track, across
    /// results, back to the pictures the runtime encoded. A reconnect drops
    /// the decoder: the new connection's stream must start at a keyframe, and
    /// a frame that does not makes the core pause and resume the track.
    #[tokio::test(flavor = "multi_thread", worker_threads = 2)]
    async fn step_results_decode_per_track_and_restart_at_a_keyframe_on_reconnect() {
        let probe = make_ready_reactor(
            Arc::new(DataPeer::new(crate::protocol::DEFAULT_MAX_MESSAGE_BYTES)),
            GenerationMode::Stepped,
        );
        for codec in round_trip_codecs(&probe) {
            let peer = Arc::new(DataPeer::new(crate::protocol::DEFAULT_MAX_MESSAGE_BYTES));
            let reactor = encoding_reactor(peer.clone(), codec);
            let mut runtime = RuntimeEncoder::new(codec);
            for n in 0..3u8 {
                let output = step_with(&reactor, &peer, runtime.result(1 + n as u64, n))
                    .await
                    .unwrap();
                let frame = &output.tracks[0].frames[0];
                assert_eq!(frame.encoding, codec.frame_encoding(), "{codec:?}");
                assert_eq!(frame.shape, [HEIGHT, WIDTH, 3], "{codec:?}");
                let error = distance(&frame.data, &picture(n));
                assert!(error < 12.0, "{codec:?} step {n}: off by {error}");
            }

            reactor.teardown(true, false).await;
            negotiate(&reactor, codec);
            // The new connection's data channel opens, as the peer reports it.
            reactor.frame_decoders.open();
            // The runtime's encoder runs on, as if it had not seen the new
            // connection: its next frame is no keyframe, and fails here.
            assert!(peer.track_controls().is_empty(), "{codec:?}");
            let output = step_with(&reactor, &peer, runtime.result(4, 3)).await;
            assert!(matches!(output, Err(CoreError::Decode(_))), "{codec:?}");
            assert_eq!(
                peer.track_controls(),
                ["pause:out", "resume:out"],
                "{codec:?}"
            );

            // The runtime answers the resume with a keyframe, which starts the
            // stream again.
            runtime.0.request_key_frame();
            let output = step_with(&reactor, &peer, runtime.result(5, 4)).await;
            let error = distance(&output.unwrap().tracks[0].frames[0].data, &picture(4));
            assert!(error < 12.0, "{codec:?}: off by {error}");
        }
    }
}
