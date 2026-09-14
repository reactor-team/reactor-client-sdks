//! Local WebRTC loopback through the public C push, core and native transport.
use super::*;
use crate::{
    callbacks::{CallbackGate, HostThread, Overflow},
    http::ReqwestHttpClient,
    reactor_push_audio_frame, HostJob, HostThreads, ReactorHandle,
};
use reactor_core::{
    http::StaticAuth,
    reactor::{Reactor, ReactorDeps, ReactorOptions},
    runtime::TokioPlatform,
};
use std::collections::VecDeque;
use std::f64::consts::PI;
use std::ffi::CString;
use std::sync::atomic::AtomicBool;
use std::time::{Duration, Instant};

#[derive(Default)]
struct Received {
    ice: Mutex<VecDeque<reactor_webrtc::IceCandidate>>,
    connected: AtomicBool,
    tracks: Mutex<Vec<RemoteTrack>>,
    // Channel zero at the decoder's actual rate; this is not the input rate.
    frames: Mutex<Vec<(u32, Vec<i16>)>>,
}

fn observer(received: &Arc<Received>) -> PeerConnectionObserver {
    PeerConnectionObserver::new()
        .on_ice_candidate({
            let r = received.clone();
            move |candidate| r.ice.lock().unwrap().push_back(candidate)
        })
        .on_connection_state_change({
            let r = received.clone();
            move |state| {
                r.connected
                    .store(state == PeerConnectionState::Connected, Ordering::SeqCst);
            }
        })
        .on_track({
            let r = received.clone();
            move |track| {
                if let RemoteTrack::Audio(audio) = &track {
                    let sink = r.clone();
                    audio.on_frame(move |frame| {
                        sink.frames.lock().unwrap().push((
                            frame.sample_rate,
                            frame
                                .pcm
                                .iter()
                                .step_by(frame.channels as usize)
                                .copied()
                                .collect(),
                        ));
                    });
                }
                r.tracks.lock().unwrap().push(track);
            }
        })
}

fn handle_for(peer: Arc<ReactorWebRtcPeerTransport>) -> ReactorHandle {
    let gate = Arc::new(CallbackGate::new());
    ReactorHandle {
        reactor: Arc::new(Reactor::new(
            ReactorDeps {
                http: Arc::new(ReqwestHttpClient::new(false)),
                auth: Arc::new(StaticAuth(None)),
                platform: Arc::new(TokioPlatform),
                peer,
            },
            ReactorOptions::new("http://localhost", "audio-test"),
        )),
        gate: gate.clone(),
        tasks: Arc::new(Mutex::new(Vec::new())),
        hosts: HostThreads {
            control: HostThread::spawn(
                "audio-test-host",
                None,
                Overflow::DropNewest,
                gate,
                |job: HostJob| job(),
            ),
            video: None,
            audio: None,
        },
    }
}

fn energy(pcm: &[i16], rate: u32, hz: f64) -> f64 {
    let coefficient = 2.0 * (2.0 * PI * hz / rate as f64).cos();
    let (mut a, mut b) = (0.0, 0.0);
    for &sample in pcm {
        let next = sample as f64 + coefficient * a - b;
        b = a;
        a = next;
    }
    (a * a + b * b - coefficient * a * b).max(0.0)
}

#[test]
fn ffi_rejects_invalid_formats_before_reading_the_pcm_buffer() {
    let (tx, _rx) = futures::channel::mpsc::unbounded();
    let peer = Arc::new(ReactorWebRtcPeerTransport::with_adm_mode(
        tx,
        AdmMode::Synthetic,
    ));
    let mut handle = handle_for(peer);
    let name = CString::new("mic").unwrap();
    let pcm = [0i16];
    for (rate, channels) in [(0, 1), (96_000, 1), (48_000, 0), (48_000, u32::MAX)] {
        // Invalid format metadata must be rejected before using its claimed
        // length. The backing buffer deliberately contains only one sample.
        unsafe {
            reactor_push_audio_frame(
                &mut handle,
                name.as_ptr(),
                pcm.as_ptr(),
                u32::MAX,
                rate,
                channels,
            );
        }
    }
}

#[test]
fn ffi_audio_preserves_pitch_and_duration_across_capture_formats() {
    for rate in [8_000, 16_000, 24_000, 32_000, 44_100, 48_000] {
        for channels in [1, 2] {
            loopback(rate, channels);
        }
    }
}

fn loopback(rate: u32, channels: u32) {
    let (tx, _rx) = futures::channel::mpsc::unbounded();
    let sender = Arc::new(ReactorWebRtcPeerTransport::with_adm_mode(
        tx,
        AdmMode::Synthetic,
    ));
    let receiver_factory = PeerConnectionFactory::builder()
        .with_adm(AdmMode::Synthetic)
        .build()
        .unwrap();
    let sent = Arc::new(Received::default());
    let received = Arc::new(Received::default());
    let config = RtcConfiguration::default();
    let pc1 = sender
        .factory
        .create_peer_connection(&config, observer(&sent))
        .unwrap();
    let pc2 = receiver_factory
        .create_peer_connection(&config, observer(&received))
        .unwrap();
    let mic = sender.factory.create_audio_track("mic").unwrap();
    pc1.add_track(&mic).unwrap();
    sender
        .state
        .lock()
        .unwrap()
        .local_tracks
        .insert("mic".into(), LocalTrack::Audio(mic));
    let offer = pc1.create_offer().unwrap();
    pc1.set_local_description(&offer).unwrap();
    pc2.set_remote_description(&offer).unwrap();
    let answer = pc2.create_answer().unwrap();
    pc2.set_local_description(&answer).unwrap();
    pc1.set_remote_description(&answer).unwrap();
    let deadline = Instant::now() + Duration::from_secs(10);
    while !sent.connected.load(Ordering::SeqCst) || !received.connected.load(Ordering::SeqCst) {
        for (from, to) in [(&sent, &pc2), (&received, &pc1)] {
            while let Some(candidate) = from.ice.lock().unwrap().pop_front() {
                to.add_ice_candidate(&candidate).unwrap();
            }
        }
        assert!(Instant::now() < deadline, "local ICE did not connect");
        std::thread::sleep(Duration::from_millis(5));
    }

    let mut handle = handle_for(sender.clone());
    let name = CString::new("mic").unwrap();
    // 200 ms silence, 600 ms tone, 300 ms silence. 20 ms pushes exercise native
    // splitting; the unit tests cover fragments smaller than an ADM block.
    let frames_per_push = rate / 50;
    let started = Instant::now();
    for tick in 0..55 {
        let mut pcm = Vec::new();
        for n in 0..frames_per_push {
            let time = (tick * frames_per_push + n) as f64 / rate as f64;
            let sample = if (10..40).contains(&tick) {
                (8_000.0 * (2.0 * PI * 440.0 * time).sin()) as i16
            } else {
                0
            };
            // Identical stereo lanes avoid downmix cancellation.
            pcm.extend(std::iter::repeat_n(sample, channels as usize));
        }
        unsafe {
            reactor_push_audio_frame(
                &mut handle,
                name.as_ptr(),
                pcm.as_ptr(),
                frames_per_push,
                rate,
                channels,
            );
        }
        std::thread::sleep(
            (started + Duration::from_millis((tick as u64 + 1) * 20))
                .saturating_duration_since(Instant::now()),
        );
    }
    std::thread::sleep(Duration::from_millis(150));
    drop(pc1);
    drop(pc2);
    let frames = received.frames.lock().unwrap();
    let audible: Vec<_> = frames
        .iter()
        .filter(|(_, pcm)| {
            !pcm.is_empty()
                && pcm.iter().map(|x| (*x as f64).powi(2)).sum::<f64>() / pcm.len() as f64
                    > 100_000.0
        })
        .collect();
    let duration: f64 = audible
        .iter()
        .map(|(hz, pcm)| pcm.len() as f64 / *hz as f64)
        .sum();
    assert!(
        (0.45..0.8).contains(&duration),
        "{rate} Hz/{channels} ch: decoded tone lasted {duration:.3}s, expected ~0.6s"
    );
    let output_rate = audible[0].0;
    let pcm: Vec<i16> = audible
        .iter()
        .flat_map(|(_, pcm)| pcm.iter().copied())
        .collect();
    let correct = energy(&pcm, output_rate, 440.0);
    // The old hardcoded metadata sped up non-48k input and interleaved stereo
    // as mono. Check against nearby pitches too, including 44.1k mislabeled 48k.
    for wrong in [400.0, 480.0, 880.0, 1320.0] {
        assert!(
            correct > energy(&pcm, output_rate, wrong) * 8.0,
            "{rate} Hz/{channels} ch: wrong decoded pitch near {wrong} Hz"
        );
    }
    futures::executor::block_on(sender.close()).unwrap();
    drop(frames);
    received.tracks.lock().unwrap().clear();
}
