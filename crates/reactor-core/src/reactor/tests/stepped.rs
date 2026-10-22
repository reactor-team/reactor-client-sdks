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
    requests: std::sync::Mutex<Vec<(Method, String)>>,
}

impl GenerationHttp {
    fn new(echo: Option<&'static str>) -> Self {
        Self {
            echo,
            completes: false,
            requests: Default::default(),
        }
    }

    fn completing(echo: Option<&'static str>) -> Self {
        Self {
            completes: true,
            ..Self::new(echo)
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
            return json(format!(
                r#"{{
                    "session_id": "sess_1",
                    "state": "ACTIVE",
                    {echo}
                    "selected_transport": {{"protocol": "webrtc", "version": "1.0"}},
                    "capabilities": {{"protocol_version": "1.0", "tracks": [
                        {{"name": "output", "kind": "video", "direction": "recvonly"}},
                        {{"name": "input", "kind": "video", "direction": "sendonly"}}
                    ]}}
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
                Method::Get => json(r#"{"sdp_answer": "answer"}"#.to_string()),
                _ => json(String::new()),
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
/// chunked or not.
struct ChannelsPeer {
    chunked: bool,
    reactor: std::sync::Mutex<Option<std::sync::Weak<Reactor>>>,
}

impl ChannelsPeer {
    fn new(chunked: bool) -> Self {
        Self {
            chunked,
            reactor: std::sync::Mutex::new(None),
        }
    }
}

#[async_trait::async_trait]
impl PeerTransport for ChannelsPeer {
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
    max: usize,
    /// Tracks pushed to as media (the streamed path), in push order.
    pushed: Mutex<Vec<String>>,
}

impl DataPeer {
    fn new(max: usize) -> Self {
        Self {
            sent_data: Mutex::new(vec![]),
            max,
            pushed: Mutex::new(vec![]),
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
    fn send_control(&self, _: &[u8]) -> Result<(), CoreError> {
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
        self.max
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

// ── Stepped generation: input buffering ─────────────────────────────────

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

/// A stepped session's input tracks get no RTP transceiver; its output
/// tracks keep theirs. Streamed negotiates every track, as before.
#[tokio::test]
async fn a_stepped_connect_negotiates_no_transceiver_for_input_tracks() {
    let prepared = |generation, echo| async move {
        let http = Arc::new(GenerationHttp::new(echo));
        let peer = Arc::new(SpyPeer::default());
        let reactor = make_generation_reactor(http.clone(), peer.clone(), generation);
        connect_until_registration(&reactor, &http).await.abort();
        let tracks = peer.prepared_tracks.lock().unwrap().clone().unwrap();
        tracks.into_iter().map(|t| t.name).collect::<Vec<_>>()
    };
    assert_eq!(
        prepared(GenerationMode::Stepped, Some("stepped")).await,
        ["output"]
    );
    assert_eq!(
        prepared(GenerationMode::Streamed, None).await,
        ["output", "input"]
    );
}

#[tokio::test]
async fn a_stepped_preset_connect_negotiates_no_transceiver_for_input_tracks() {
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

#[tokio::test]
async fn disconnecting_drops_frames_buffered_for_the_session() {
    let peer = Arc::new(DataPeer::new(crate::protocol::DEFAULT_MAX_MESSAGE_BYTES));
    let reactor = make_ready_reactor(peer, GenerationMode::Stepped);
    reactor.push_video_frame("cam", &[1, 2, 3, 255], 1, 1);
    reactor.disconnect(false).await.unwrap();
    assert!(reactor.step_inputs.is_empty());
}
