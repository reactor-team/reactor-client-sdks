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
    at_runtime: mpsc::Receiver<Vec<u8>>,
    // The runtime's end, kept alive for the test.
    _runtime_data: DataChannel,
    _runtime: PeerConnection,
}

/// The transport, connected to a runtime built with or without chunking. The
/// transport's own `prepare` needs a coordinator, so the test installs the
/// connection and its data channel into the transport's state directly, as
/// `prepare` would.
fn connect(runtime_chunks: bool) -> Loopback {
    let (tx, _rx) = futures::channel::mpsc::unbounded();
    let client = Arc::new(ReactorWebRtcPeerTransport::with_adm_mode(
        tx,
        AdmMode::Synthetic,
    ));
    let mut runtime_factory = PeerConnectionFactory::builder().with_adm(AdmMode::Synthetic);
    if runtime_chunks {
        runtime_factory = runtime_factory.with_dc_chunking(DcChunking::default());
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

    {
        let mut s = client.state.lock().unwrap();
        s.pc = Some(pc1);
        s.data_channel = Some(data);
    }
    Loopback {
        client,
        at_runtime: got_rx,
        _runtime_data: runtime_data,
        _runtime: pc2,
    }
}

#[test]
fn a_runtime_that_chunks_lifts_the_limit_and_takes_a_large_command() {
    // Bound by name: fields skipped with `..` would be dropped here, closing
    // the runtime's end.
    let Loopback {
        client,
        at_runtime,
        _runtime_data,
        _runtime,
    } = connect(true);
    assert_eq!(
        client.max_message_bytes(),
        DcChunking::default().max_message_size as usize
    );

    let command: Vec<u8> = (0..MIB).map(|i| (i % 251) as u8).collect();
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
        at_runtime,
        _runtime_data,
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
