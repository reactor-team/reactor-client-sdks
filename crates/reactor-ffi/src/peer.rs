//! `ReactorWebRtcPeerTransport` — PeerTransport backed by Reactor's libwebrtc.
//!
//! ICE is trickled: `prepare()` returns as soon as the local offer is ready;
//! candidates are forwarded via `PeerEvent::IceCandidate` as they arrive, and
//! `PeerEvent::IceGatheringComplete` closes the batch.

use std::collections::HashMap;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

use async_trait::async_trait;
use futures::channel::mpsc::UnboundedSender;
use log::{debug, info, warn};

use reactor_core::error::CoreError;
use reactor_core::peer::{
    PeerConnectionState as CorePeerConnectionState, PeerEvent, PeerTransport, PreparedOffer,
};
use reactor_core::protocol::session::{TrackCapability, TrackDirection, TrackKind};
use reactor_core::protocol::webrtc::{IceCandidate, IceServer, TrackMappingEntry};

use reactor_webrtc::{
    AdmMode, ContinualGatheringPolicy, DataChannel, IceGatheringState, IceServer as RwIceServer,
    MediaKind, PeerConnection, PeerConnectionFactory, PeerConnectionObserver, PeerConnectionState,
    RtcConfiguration, SdpType, SessionDescription, Track, Transceiver, TransceiverDirection,
};

fn peer_err(e: impl std::fmt::Display) -> CoreError {
    CoreError::Peer(e.to_string())
}

/// Which audio device module to use when the host does not say.
///
/// Synthetic, on every platform — no microphone is opened and nothing is captured
/// unless the host pushes it with `reactor_push_audio_frame`.
///
/// Platform used to be the desktop default, and it was the wrong one twice over. A
/// model declaring a sendonly audio track was enough to put the microphone on the
/// wire: `prepare` attaches a local track for every such capability, and the platform
/// ADM feeds it from the real device with no involvement from the host at all. An
/// application that never mentions audio does not expect to be transmitting it, and an
/// SDK opening a capture device on its own behalf is not a defensible default whatever
/// the convenience.
///
/// It also broke the other direction: `push_audio_frame` feeds the synthetic ADM, so
/// under the platform ADM a host's PCM went nowhere while the microphone streamed in
/// its place. `examples/push_audio.py` was silently doing exactly that.
///
/// Hosts that want real capture and playout ask for it — `adm_mode = 1` on
/// `reactor_create_with_adm`, or `REACTOR_WEBRTC_ADM=platform`. That is also what
/// brings speaker playout back; under Synthetic, decoded audio arrives at `on_audio`
/// for the host to play.
fn default_adm_mode() -> AdmMode {
    adm_mode_from_env(std::env::var("REACTOR_WEBRTC_ADM").ok().as_deref())
}

/// The decision itself, split out from reading the environment so it can be tested
/// without touching process-global state.
fn adm_mode_from_env(requested: Option<&str>) -> AdmMode {
    match requested.map(|v| v.trim().to_ascii_lowercase()) {
        Some(v) if v == "synthetic" => AdmMode::Synthetic,
        Some(v) if v == "platform" => AdmMode::Platform,
        Some(other) => {
            warn!("[peer] unknown REACTOR_WEBRTC_ADM='{other}', using the default");
            AdmMode::Synthetic
        }
        None => AdmMode::Synthetic,
    }
}

fn map_state(s: PeerConnectionState) -> CorePeerConnectionState {
    match s {
        PeerConnectionState::New => CorePeerConnectionState::New,
        PeerConnectionState::Connecting => CorePeerConnectionState::Connecting,
        PeerConnectionState::Connected => CorePeerConnectionState::Connected,
        PeerConnectionState::Disconnected => CorePeerConnectionState::Disconnected,
        PeerConnectionState::Failed => CorePeerConnectionState::Failed,
        PeerConnectionState::Closed => CorePeerConnectionState::Closed,
    }
}

/// Sink for a decoded remote video frame: BGRA pixels, width, height, `frame_id`,
/// capture timestamp in µs, and the sender's `user_data` (empty when the frame
/// carried no metadata trailer).
type FrameCallback = Arc<dyn Fn(&[u8], u32, u32, u64, u64, &[u8]) + Send + Sync + 'static>;

/// Sink for a decoded remote audio frame: interleaved i16 PCM, sample rate in Hz,
/// channel count.
type AudioCallback = Arc<dyn Fn(&[i16], u32, u32) + Send + Sync + 'static>;

#[derive(Default)]
struct PeerState {
    pc: Option<Arc<PeerConnection>>,
    data_channel: Option<DataChannel>,
    control_channel: Option<DataChannel>,
    track_names: Vec<String>,
    track_directions: Vec<TrackDirection>,
    transceivers: Vec<Transceiver>,
    local_tracks: HashMap<String, Track>,
    recv_tracks: Arc<Mutex<Vec<Track>>>,
}

pub struct ReactorWebRtcPeerTransport {
    event_tx: UnboundedSender<PeerEvent>,
    factory: PeerConnectionFactory,
    state: Arc<Mutex<PeerState>>,
    frame_cb: Option<FrameCallback>,
    audio_cb: Option<AudioCallback>,
}

impl ReactorWebRtcPeerTransport {
    pub fn new(event_tx: UnboundedSender<PeerEvent>) -> Self {
        Self::with_adm_mode(event_tx, default_adm_mode())
    }

    pub fn with_adm_mode(event_tx: UnboundedSender<PeerEvent>, mode: AdmMode) -> Self {
        info!("[peer] audio device module: {mode:?}");
        let factory = PeerConnectionFactory::with_adm(mode).expect("create PeerConnectionFactory");
        Self {
            event_tx,
            factory,
            state: Arc::new(Mutex::new(PeerState::default())),
            frame_cb: None,
            audio_cb: None,
        }
    }

    pub fn with_frame_callback(
        mut self,
        cb: impl Fn(&[u8], u32, u32, u64, u64, &[u8]) + Send + Sync + 'static,
    ) -> Self {
        self.frame_cb = Some(Arc::new(cb));
        self
    }

    pub fn with_audio_callback(
        mut self,
        cb: impl Fn(&[i16], u32, u32) + Send + Sync + 'static,
    ) -> Self {
        self.audio_cb = Some(Arc::new(cb));
        self
    }
}

#[async_trait]
impl PeerTransport for ReactorWebRtcPeerTransport {
    async fn prepare(
        &self,
        ice_servers: &[IceServer],
        tracks: &[TrackCapability],
    ) -> Result<PreparedOffer, CoreError> {
        let config = RtcConfiguration {
            ice_servers: ice_servers
                .iter()
                .map(|s| RwIceServer {
                    urls: s.uris.clone(),
                    username: s
                        .credentials
                        .as_ref()
                        .map(|c| c.username.clone())
                        .unwrap_or_default(),
                    password: s
                        .credentials
                        .as_ref()
                        .map(|c| c.password.clone())
                        .unwrap_or_default(),
                })
                .collect(),
            continual_gathering_policy: ContinualGatheringPolicy::GatherContinually,
            ..Default::default()
        };

        let recv_tracks: Arc<Mutex<Vec<Track>>> = Arc::new(Mutex::new(Vec::new()));
        let recv_name_mids: Arc<Mutex<Vec<(String, Option<String>)>>> =
            Arc::new(Mutex::new(Vec::new()));
        let recv_track_idx = Arc::new(AtomicUsize::new(0));

        let observer = {
            let tx = self.event_tx.clone();
            let mut obs = PeerConnectionObserver::new().on_connection_state_change(move |s| {
                info!("[peer] connection state → {s:?}");
                let _ = tx.unbounded_send(PeerEvent::ConnectionStateChanged(map_state(s)));
            });

            let tx = self.event_tx.clone();
            obs = obs.on_ice_gathering_change(move |state| {
                debug!("[peer] ICE gathering → {state:?}");
                if matches!(state, IceGatheringState::Complete) {
                    info!("[peer] ICE gathering complete");
                    let _ = tx.unbounded_send(PeerEvent::IceGatheringComplete);
                }
            });

            let tx = self.event_tx.clone();
            obs = obs.on_ice_candidate(move |c| {
                let ic = IceCandidate {
                    candidate: c.candidate,
                    sdp_mid: c.sdp_mid,
                    sdp_mline_index: c.sdp_mline_index,
                };
                let _ = tx.unbounded_send(PeerEvent::IceCandidate(ic));
            });

            let tx = self.event_tx.clone();
            let frame_cb = self.frame_cb.clone();
            let audio_cb = self.audio_cb.clone();
            let recv = recv_tracks.clone();
            let name_mids = recv_name_mids.clone();
            let track_idx = recv_track_idx.clone();
            obs = obs.on_track(move |kind, mut track| {
                let idx = track_idx.fetch_add(1, Ordering::SeqCst);
                let (name, mid) = name_mids
                    .lock()
                    .unwrap()
                    .get(idx)
                    .map(|(n, m)| (Some(n.clone()), m.clone()))
                    .unwrap_or((None, None));
                info!("[peer] track received  kind={kind:?}  name={name:?}  mid={mid:?}");
                let _ = tx.unbounded_send(PeerEvent::TrackReceived {
                    name,
                    mid: mid.clone(),
                });
                match kind {
                    MediaKind::Video => {
                        if let Some(cb) = frame_cb.clone() {
                            // No transform to attach: reactor-webrtc negotiates
                            // frame-metadata support in the SDP and installs the strip
                            // step itself, so VideoFrame::metadata is populated whenever
                            // the sender included a trailer and the peer agreed.
                            track.on_video_frame(move |f| {
                                let (frame_id, ts, ud) = f
                                    .metadata
                                    .as_ref()
                                    .map(|m| (m.frame_id, m.timestamp, m.user_data.as_slice()))
                                    .unwrap_or((0, 0, &[]));
                                cb(f.bgra, f.width, f.height, frame_id, ts, ud);
                            });
                        }
                    }
                    MediaKind::Audio => {
                        if let Some(cb) = audio_cb.clone() {
                            track.on_audio_frame(move |f| cb(f.pcm, f.sample_rate, f.channels));
                        }
                    }
                    MediaKind::Unknown => {}
                }
                recv.lock().unwrap().push(track);
            });
            obs
        };

        let pc = self
            .factory
            .create_peer_connection(&config, observer)
            .map_err(peer_err)?;

        let mut transceivers: Vec<Transceiver> = Vec::with_capacity(tracks.len());
        let mut track_names: Vec<String> = Vec::with_capacity(tracks.len());
        let mut track_directions: Vec<TrackDirection> = Vec::with_capacity(tracks.len());
        let mut local_tracks: HashMap<String, Track> = HashMap::new();

        for track in tracks {
            let kind = match track.kind {
                TrackKind::Video => MediaKind::Video,
                TrackKind::Audio => MediaKind::Audio,
            };
            let direction = match track.direction {
                TrackDirection::Recvonly => TransceiverDirection::RecvOnly,
                TrackDirection::Sendonly => TransceiverDirection::SendOnly,
            };
            let tc = pc.add_transceiver(kind, direction).map_err(peer_err)?;

            if track.direction == TrackDirection::Sendonly {
                let local = match track.kind {
                    TrackKind::Video => self.factory.create_video_track(&track.name),
                    TrackKind::Audio => self.factory.create_audio_track(&track.name),
                }
                .map_err(peer_err)?;
                tc.set_track(&local).map_err(peer_err)?;
                info!(
                    "[peer] attached sendonly {:?} track '{}'",
                    track.kind, track.name
                );
                local_tracks.insert(track.name.clone(), local);
            }

            transceivers.push(tc);
            track_names.push(track.name.clone());
            track_directions.push(track.direction);
        }

        let mut data_ch = pc.create_data_channel("data").map_err(peer_err)?;
        let mut control_ch = pc.create_data_channel("control").map_err(peer_err)?;

        let tx = self.event_tx.clone();
        data_ch.on_open(move || {
            info!("[peer] data channel open");
            let _ = tx.unbounded_send(PeerEvent::DataChannelOpen);
        });
        let tx = self.event_tx.clone();
        data_ch.on_message(move |data, binary| {
            if !binary {
                if let Ok(s) = std::str::from_utf8(data) {
                    let _ = tx.unbounded_send(PeerEvent::DataChannelMessage(s.to_owned()));
                }
            }
        });

        let tx = self.event_tx.clone();
        control_ch.on_open(move || {
            info!("[peer] control channel open");
            let _ = tx.unbounded_send(PeerEvent::ControlChannelOpen);
        });
        let tx = self.event_tx.clone();
        control_ch.on_message(move |data, binary| {
            if !binary {
                if let Ok(s) = std::str::from_utf8(data) {
                    let _ = tx.unbounded_send(PeerEvent::ControlChannelMessage(s.to_owned()));
                }
            }
        });

        let offer = pc.create_offer().map_err(peer_err)?;
        let sdp_offer = offer.sdp.clone();
        debug!(
            "[peer] SDP offer:\n{}",
            &sdp_offer[..sdp_offer.len().min(2048)]
        );
        pc.set_local_description(&offer).map_err(peer_err)?;

        let mut track_mapping: Vec<TrackMappingEntry> = Vec::with_capacity(tracks.len());
        for (i, tc) in transceivers.iter().enumerate() {
            let mid = tc.mid().unwrap_or_default();
            info!(
                "[peer] transceiver #{i}: name={} kind={:?} dir={:?} mid={mid:?}",
                track_names[i], tracks[i].kind, tracks[i].direction
            );
            track_mapping.push(TrackMappingEntry {
                name: track_names[i].clone(),
                kind: tracks[i].kind,
                direction: tracks[i].direction,
                mid,
            });
        }

        {
            let mut table = recv_name_mids.lock().unwrap();
            for entry in track_mapping
                .iter()
                .filter(|e| e.direction == TrackDirection::Recvonly)
            {
                table.push((entry.name.clone(), Some(entry.mid.clone())));
            }
        }

        {
            let mut s = self.state.lock().unwrap();
            s.pc = Some(Arc::new(pc));
            s.data_channel = Some(data_ch);
            s.control_channel = Some(control_ch);
            s.track_names = track_names;
            s.track_directions = track_directions;
            s.transceivers = transceivers;
            s.local_tracks = local_tracks;
            s.recv_tracks = recv_tracks;
        }

        Ok(PreparedOffer {
            sdp_offer,
            track_mapping,
        })
    }

    async fn set_remote_description(&self, sdp_answer: &str) -> Result<(), CoreError> {
        let pc = self
            .state
            .lock()
            .unwrap()
            .pc
            .clone()
            .ok_or_else(|| CoreError::Peer("peer connection not initialised".into()))?;
        let answer = SessionDescription {
            kind: SdpType::Answer,
            sdp: sdp_answer.to_owned(),
        };
        pc.set_remote_description(&answer).map_err(peer_err)
    }

    fn send_data(&self, payload: &str) -> Result<(), CoreError> {
        debug!("[peer] → data:    {}", &payload[..payload.len().min(200)]);
        let s = self.state.lock().unwrap();
        let dc = s
            .data_channel
            .as_ref()
            .ok_or_else(|| CoreError::Peer("data channel not ready".into()))?;
        dc.send(payload.as_bytes(), false).map_err(peer_err)
    }

    fn send_control(&self, payload: &str) -> Result<(), CoreError> {
        debug!("[peer] → control: {}", &payload[..payload.len().min(200)]);
        let s = self.state.lock().unwrap();
        let dc = s
            .control_channel
            .as_ref()
            .ok_or_else(|| CoreError::Peer("control channel not ready".into()))?;
        dc.send(payload.as_bytes(), false).map_err(peer_err)
    }

    async fn set_track_direction(&self, track_name: &str, _active: bool) -> Result<(), CoreError> {
        let s = self.state.lock().unwrap();
        if !s.track_names.iter().any(|n| n == track_name) {
            return Err(CoreError::Peer(format!("unknown track: {track_name}")));
        }
        Ok(())
    }

    async fn close(&self) -> Result<(), CoreError> {
        // Everything comes out from under the lock, and is dropped after the guard
        // is released. Never the other way around.
        //
        // Dropping a PeerConnection, Track or Transceiver dispatches onto
        // libwebrtc's signaling/worker/network threads and waits for them. One of
        // those threads can be inside a receive track's frame sink, in a host
        // callback, whose handler calls back into push_video_frame — the echo loop
        // in examples/frame_metadata_roundtrip.py does exactly that. That path
        // wants this same mutex. Holding it across the drop leaves each side
        // waiting on the other: teardown waits for the media thread to finish,
        // the media thread waits for the mutex teardown is holding.
        let taken = {
            let mut s = self.state.lock().unwrap();
            std::mem::take(&mut *s)
        };

        // `recv_tracks` lives behind its own Arc, shared with the on_track
        // observer, so the take above moved only this side's handle. Clear the
        // contents to drop the remote tracks here with everything else, rather
        // than whenever the observer closure happens to be released.
        taken.recv_tracks.lock().unwrap().clear();

        drop(taken);
        Ok(())
    }

    fn push_audio_frame(&self, track_name: &str, data: &[i16]) {
        // The guard covers the lookup only. The factory push does not need it, and
        // holding a lock across a libwebrtc call is the shape that deadlocks.
        {
            let s = self.state.lock().unwrap();
            if !s.local_tracks.contains_key(track_name) {
                warn!("[peer] push_audio_frame: no audio source for track '{track_name}'");
                return;
            }
        }
        self.factory.push_audio_frame(data, 48_000, 1);
    }

    // The two video pushes below do hold the guard across the libwebrtc call,
    // because `Track` is not `Clone` and the value is borrowed from the map. That
    // is safe: pushing a frame hands it to the track's video source and returns —
    // it joins no thread and re-enters no host callback. `close()` was the only
    // holder that waited on libwebrtc while holding this mutex.
    fn push_video_frame(&self, track_name: &str, data: &[u8], width: u32, height: u32) {
        let s = self.state.lock().unwrap();
        let Some(track) = s.local_tracks.get(track_name) else {
            warn!("[peer] push_video_frame: no video source for track '{track_name}'");
            return;
        };
        track.push_video_frame(data, width, height);
    }

    fn push_video_frame_with_metadata(
        &self,
        track_name: &str,
        data: &[u8],
        width: u32,
        height: u32,
        user_data: &[u8],
    ) {
        let s = self.state.lock().unwrap();
        let Some(track) = s.local_tracks.get(track_name) else {
            warn!(
                "[peer] push_video_frame_with_metadata: no video source for track '{track_name}'"
            );
            return;
        };
        // Dropped by reactor-webrtc unless the peer declared that it strips the
        // trailer, so tagging a frame is safe whatever the far end supports.
        track.push_video_frame_with_metadata(data, width, height, user_data);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The microphone must not open because nobody said otherwise.
    ///
    /// This was `Platform` on desktop, which meant a model declaring a sendonly audio
    /// track put live microphone audio on the wire without the host asking — `prepare`
    /// attaches a local track for the capability and the platform ADM feeds it from the
    /// real device. Nothing in the host's code had to mention audio for that to happen.
    #[test]
    fn the_default_does_not_open_a_capture_device() {
        assert_eq!(adm_mode_from_env(None), AdmMode::Synthetic);
    }

    #[test]
    fn real_capture_has_to_be_asked_for() {
        assert_eq!(adm_mode_from_env(Some("platform")), AdmMode::Platform);
    }

    #[test]
    fn synthetic_can_be_named_explicitly() {
        assert_eq!(adm_mode_from_env(Some("synthetic")), AdmMode::Synthetic);
    }

    #[test]
    fn the_value_is_case_and_whitespace_insensitive() {
        assert_eq!(adm_mode_from_env(Some("  PLATFORM \n")), AdmMode::Platform);
        assert_eq!(adm_mode_from_env(Some("Synthetic")), AdmMode::Synthetic);
    }

    /// A typo must not silently open the microphone: the safe direction is the default,
    /// not the requested one.
    #[test]
    fn an_unrecognised_value_falls_back_to_synthetic() {
        assert_eq!(adm_mode_from_env(Some("platfrom")), AdmMode::Synthetic);
        assert_eq!(adm_mode_from_env(Some("")), AdmMode::Synthetic);
    }
}
