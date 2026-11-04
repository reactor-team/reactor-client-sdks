//! Data-channel chunking over a local loopback: the native transport offers,
//! and a reactor-webrtc peer stands in for the runtime that answers.
use super::*;
use std::collections::VecDeque;
use std::sync::atomic::AtomicBool;
use std::sync::mpsc;
use std::time::{Duration, Instant};

const MIB: usize = 1024 * 1024;

#[derive(Default)]
struct Side {
    ice: Mutex<VecDeque<reactor_webrtc::IceCandidate>>,
    connected: AtomicBool,
    channels: Mutex<Vec<DataChannel>>,
}

fn observer(side: &Arc<Side>) -> PeerConnectionObserver {
    PeerConnectionObserver::new()
        .on_ice_candidate({
            let s = side.clone();
            move |candidate| s.ice.lock().unwrap().push_back(candidate)
        })
        .on_connection_state_change({
            let s = side.clone();
            move |state| {
                s.connected
                    .store(state == PeerConnectionState::Connected, Ordering::SeqCst);
            }
        })
        .on_data_channel({
            let s = side.clone();
            move |channel| s.channels.lock().unwrap().push(channel)
        })
}

/// Forward queued candidates, releasing the queue's lock before each
/// `add_ice_candidate`: that call waits on the signaling thread, which may be
/// waiting for the same lock to queue the next candidate.
fn trickle(from: &Side, to: &PeerConnection) {
    loop {
        let next = from.ice.lock().unwrap().pop_front();
        let Some(candidate) = next else { break };
        let _ = to.add_ice_candidate(&candidate);
    }
}

/// A transport connected to a stand-in runtime.
struct Loopback {
    client: Arc<ReactorWebRtcPeerTransport>,
    /// The offer the client sent, with the limit its chunking declares.
    offer: String,
    at_runtime: mpsc::Receiver<Vec<u8>>,
    at_client: mpsc::Receiver<Vec<u8>>,
    // The runtime's end, kept alive for the test.
    runtime_data: DataChannel,
    _runtime: PeerConnection,
}

/// The transport, connected to a runtime built with or without chunking, each
/// side at its default limit.
fn connect(runtime_chunks: bool) -> Loopback {
    connect_with(
        runtime_chunks.then(DcChunking::default),
        DEFAULT_MAX_MESSAGE_SIZE,
    )
}

/// The transport, declaring `client_max` bytes, connected to a runtime built
/// with `runtime` chunking, or none. The transport's own `prepare` needs a
/// coordinator, so the test installs the connection and its data channel into
/// the transport's state directly, as `prepare` would.
fn connect_with(runtime: Option<DcChunking>, client_max: u64) -> Loopback {
    let (tx, _rx) = futures::channel::mpsc::unbounded();
    let client = Arc::new(ReactorWebRtcPeerTransport::with_max_message_size(
        tx,
        AdmMode::Synthetic,
        client_max,
    ));
    let mut runtime_factory = PeerConnectionFactory::builder().with_adm(AdmMode::Synthetic);
    if let Some(chunking) = runtime {
        runtime_factory = runtime_factory.with_dc_chunking(chunking);
    }
    let runtime_factory = runtime_factory.build().unwrap();

    let at_client = Arc::new(Side::default());
    let at_runtime = Arc::new(Side::default());
    let pc1 = Arc::new(
        client
            .factory
            .create_peer_connection(&offer_config(&[]), observer(&at_client))
            .unwrap(),
    );
    let pc2 = runtime_factory
        .create_peer_connection(&RtcConfiguration::default(), observer(&at_runtime))
        .unwrap();
    let data = pc1.create_data_channel("data").unwrap();

    let offer = pc1.create_offer().unwrap();
    pc1.set_local_description(&offer).unwrap();
    pc2.set_remote_description(&offer).unwrap();
    let answer = pc2.create_answer().unwrap();
    pc2.set_local_description(&answer).unwrap();
    pc1.set_remote_description(&answer).unwrap();

    let deadline = Instant::now() + Duration::from_secs(20);
    loop {
        trickle(&at_client, &pc2);
        trickle(&at_runtime, &pc1);
        let runtime_open = at_runtime
            .channels
            .lock()
            .unwrap()
            .first()
            .is_some_and(|c| c.state() == DataChannelState::Open);
        if at_client.connected.load(Ordering::SeqCst)
            && data.state() == DataChannelState::Open
            && runtime_open
        {
            break;
        }
        assert!(Instant::now() < deadline, "local ICE did not connect");
        std::thread::sleep(Duration::from_millis(10));
    }

    let (got_tx, got_rx) = mpsc::channel();
    let runtime_data = at_runtime.channels.lock().unwrap().pop().unwrap();
    runtime_data.on_message(move |bytes, _binary| {
        let _ = got_tx.send(bytes.to_vec());
    });
    let (client_tx, client_rx) = mpsc::channel();
    data.on_message(move |bytes, _binary| {
        let _ = client_tx.send(bytes.to_vec());
    });

    {
        let mut s = client.state.lock().unwrap();
        s.pc = Some(pc1);
        s.data_channel = Some(data);
    }
    Loopback {
        client,
        offer: offer.sdp,
        at_runtime: got_rx,
        at_client: client_rx,
        runtime_data,
        _runtime: pc2,
    }
}

/// `size` bytes of a pattern that a dropped or reordered frame would break.
fn pattern(size: usize) -> Vec<u8> {
    (0..size).map(|i| (i % 251) as u8).collect()
}

#[test]
fn a_runtime_that_chunks_lifts_the_limit_and_takes_a_large_command() {
    // Bound by name: fields skipped with `..` would be dropped here, closing
    // the runtime's end.
    let Loopback {
        client,
        offer,
        at_runtime,
        at_client: _,
        runtime_data: _runtime_data,
        _runtime,
    } = connect(true);
    assert_eq!(
        client.max_message_bytes(),
        DEFAULT_MAX_MESSAGE_SIZE as usize
    );
    assert!(
        offer.contains(&format!("max-message-size={DEFAULT_MAX_MESSAGE_SIZE}")),
        "the offer declares the client's default limit"
    );

    let command = pattern(MIB);
    client
        .send_data(&command, true)
        .expect("a 1 MiB command is sent");
    let got = at_runtime
        .recv_timeout(Duration::from_secs(20))
        .expect("the runtime receives it");
    assert!(got == command, "the command arrived whole");
    futures::executor::block_on(client.close()).unwrap();
}

#[test]
fn a_runtime_without_chunking_keeps_the_plain_limit() {
    // Bound by name: fields skipped with `..` would be dropped here, closing
    // the runtime's end.
    let Loopback {
        client,
        offer: _,
        at_runtime,
        at_client: _,
        runtime_data: _runtime_data,
        _runtime,
    } = connect(false);
    assert_eq!(
        client.max_message_bytes(),
        reactor_core::protocol::DEFAULT_MAX_MESSAGE_BYTES
    );
    client
        .send_data(b"small", true)
        .expect("a small command is sent");
    let got = at_runtime
        .recv_timeout(Duration::from_secs(20))
        .expect("the runtime receives it");
    assert_eq!(got, b"small");
    futures::executor::block_on(client.close()).unwrap();
}

/// A step result past the 64 MiB default, inside the raised limit.
const LARGE: usize = 72 * MIB;
const RAISED: u64 = 96 * MIB as u64;

#[test]
fn a_step_result_past_64_mib_arrives_when_both_sides_raise_the_limit() {
    let Loopback {
        client,
        offer,
        at_runtime: _,
        at_client,
        runtime_data,
        _runtime,
    } = connect_with(Some(dc_chunking(RAISED)), RAISED);
    assert!(offer.contains(&format!("max-message-size={RAISED}")));
    assert_eq!(client.max_message_bytes(), RAISED as usize);

    let result = pattern(LARGE);
    runtime_data
        .send(&result, true)
        .expect("the runtime sends 72 MiB to a client that takes 96 MiB");
    let got = at_client
        .recv_timeout(Duration::from_secs(90))
        .expect("the client receives it");
    assert_eq!(got.len(), LARGE);
    assert!(got == result, "the step result arrived whole");
    futures::executor::block_on(client.close()).unwrap();
}

#[test]
fn a_client_that_declares_less_than_the_runtime_sets_the_limit() {
    let Loopback {
        client,
        offer: _,
        at_runtime: _,
        at_client: _,
        runtime_data,
        _runtime,
    } = connect_with(Some(dc_chunking(RAISED)), 64 * MIB as u64);

    match runtime_data.send(&pattern(LARGE), true) {
        Err(reactor_webrtc::Error::DataChannel(DcSendError::TooLarge { max, .. })) => {
            assert_eq!(max, 64 * MIB as u64);
        }
        other => panic!("expected the runtime's send to be refused as too large, got {other:?}"),
    }
    assert_eq!(runtime_data.state(), DataChannelState::Open);
    futures::executor::block_on(client.close()).unwrap();
}

/// The client's higher default changes nothing against a runtime that keeps
/// 64 MiB: the channel refuses a larger message from the client with the same
/// error the core raises for one past the client's own limit.
#[test]
fn a_runtime_at_its_default_still_bounds_the_default_client() {
    let Loopback {
        client,
        offer: _,
        at_runtime: _,
        at_client: _,
        runtime_data: _runtime_data,
        _runtime,
    } = connect(true);

    match client.send_data(&pattern(65 * MIB), true) {
        Err(CoreError::MessageTooLarge { max, .. }) => assert_eq!(max, 64 * MIB),
        other => panic!("expected MessageTooLarge, got {other:?}"),
    }
    futures::executor::block_on(client.close()).unwrap();
}

#[test]
fn the_send_buffer_holds_two_messages_and_never_less_than_the_default() {
    for (max, buffer) in [
        (MIN_MAX_MESSAGE_SIZE, 128 * MIB as u64),
        (64 * MIB as u64, 128 * MIB as u64),
        (DEFAULT_MAX_MESSAGE_SIZE, 512 * MIB as u64),
        (MAX_MAX_MESSAGE_SIZE, 2048 * MIB as u64),
    ] {
        let chunking = dc_chunking(max);
        assert_eq!(chunking.max_message_size, max);
        assert_eq!(chunking.send_buffer_limit, buffer, "for {max}");
        PeerConnectionFactory::builder()
            .with_dc_chunking(chunking)
            .build()
            .expect("reactor-webrtc takes the chunking");
    }
}
