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
    CandidatePairState, CandidatePairStats, IceCandidateType, InboundRtpStats, OutboundRtpStats,
    PeerConnectionState as CorePeerConnectionState, PeerEvent, PeerTransport, PreparedOffer,
    RelayProtocol, StreamKind, TransportStats,
};
use reactor_core::protocol::session::{TrackCapability, TrackDirection, TrackKind};
use reactor_core::protocol::webrtc::{IceCandidate, IceServer, TrackMappingEntry};

use reactor_webrtc::{
    AdmMode, AudioTrack, ContinualGatheringPolicy, DataChannel, DataChannelState,
    IceCandidatePairState, IceCandidateType as RwIceCandidateType, IceGatheringState,
    IceServer as RwIceServer, MediaKind, PeerConnection, PeerConnectionFactory,
    PeerConnectionObserver, PeerConnectionState, RelayProtocol as RwRelayProtocol, RemoteTrack,
    RtcConfiguration, SdpType, SessionDescription, StatsReport, StreamKind as RwStreamKind, Track,
    Transceiver, TransceiverDirection, VideoFrame, VideoTrack,
};

fn peer_err(e: impl std::fmt::Display) -> CoreError {
    CoreError::Peer(e.to_string())
}

/// Send on a channel, checking `state()` first rather than letting a closed
/// channel's `send()` come back as `DataChannel::send()`'s own generic
/// "data channel send failed" — indistinguishable from a real transport
/// failure. A caller racing a concurrent teardown (e.g. the heartbeat) hits
/// this constantly, and reporting it as `CoreError::InvalidState` (rather
/// than `CoreError::Peer`) is what lets `run_heartbeat()` recognize it as
/// the expected outcome of that race instead of a genuine failure.
///
/// Unlike the wasm binding's equivalent check, this isn't race-free —
/// `DataChannel` is `Send + Sync` and can close on another thread between
/// this check and `send()` below. That residual window isn't a regression:
/// it just falls back to the same generic `CoreError::Peer` `send()` already
/// returned before this existed, so the check can only narrow the race, not
/// widen it.
fn send_on_channel(channel: &DataChannel, payload: &[u8], binary: bool) -> Result<(), CoreError> {
    if channel.state() != DataChannelState::Open {
        return Err(CoreError::InvalidState(format!(
            "data channel not open (state: {:?})",
            channel.state()
        )));
    }
    channel.send(payload, binary).map_err(peer_err)
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

fn map_stream_kind(k: RwStreamKind) -> StreamKind {
    match k {
        RwStreamKind::Unknown => StreamKind::Unknown,
        RwStreamKind::Audio => StreamKind::Audio,
        RwStreamKind::Video => StreamKind::Video,
    }
}

fn map_candidate_type(t: RwIceCandidateType) -> IceCandidateType {
    match t {
        RwIceCandidateType::Unknown => IceCandidateType::Unknown,
        RwIceCandidateType::Host => IceCandidateType::Host,
        RwIceCandidateType::Srflx => IceCandidateType::Srflx,
        RwIceCandidateType::Prflx => IceCandidateType::Prflx,
        RwIceCandidateType::Relay => IceCandidateType::Relay,
    }
}

fn map_relay_protocol(p: RwRelayProtocol) -> RelayProtocol {
    match p {
        RwRelayProtocol::NotRelayed => RelayProtocol::NotRelayed,
        RwRelayProtocol::Udp => RelayProtocol::Udp,
        RwRelayProtocol::Tcp => RelayProtocol::Tcp,
        RwRelayProtocol::Tls => RelayProtocol::Tls,
    }
}

fn map_pair_state(s: IceCandidatePairState) -> CandidatePairState {
    match s {
        IceCandidatePairState::Waiting => CandidatePairState::Waiting,
        IceCandidatePairState::InProgress => CandidatePairState::InProgress,
        IceCandidatePairState::Failed => CandidatePairState::Failed,
        IceCandidatePairState::Succeeded => CandidatePairState::Succeeded,
        IceCandidatePairState::Cancelled => CandidatePairState::Cancelled,
    }
}

/// Translate the engine's report into the core's vocabulary.
///
/// Field for field — the core's structs were shaped from this report, so there is
/// nothing to compute here and nothing to drop. Only the enums need mapping,
/// because the core cannot name `reactor-webrtc`'s: it does not depend on it, and
/// must not, since the wasm build has no libwebrtc at all.
fn map_stats(report: StatsReport) -> TransportStats {
    TransportStats {
        inbound: report
            .inbound_rtp
            .into_iter()
            .map(|s| InboundRtpStats {
                ssrc: s.ssrc,
                kind: map_stream_kind(s.kind),
                packets_received: s.packets_received,
                bytes_received: s.bytes_received,
                jitter_s: s.jitter_s,
                packets_lost: s.packets_lost,
                nack_count: s.nack_count,
                total_decode_time_s: s.total_decode_time_s,
                frames_per_second: s.frames_per_second,
                frames_decoded: s.frames_decoded,
                frames_dropped: s.frames_dropped,
                frame_width: s.frame_width,
                frame_height: s.frame_height,
            })
            .collect(),
        outbound: report
            .outbound_rtp
            .into_iter()
            .map(|s| OutboundRtpStats {
                ssrc: s.ssrc,
                kind: map_stream_kind(s.kind),
                packets_sent: s.packets_sent,
                bytes_sent: s.bytes_sent,
                target_bitrate_bps: s.target_bitrate_bps,
                round_trip_time_s: s.round_trip_time_s,
                total_round_trip_time_s: s.total_round_trip_time_s,
                fraction_lost: s.fraction_lost,
                packets_lost: s.packets_lost,
                retransmitted_packets_sent: s.retransmitted_packets_sent,
                frames_per_second: s.frames_per_second,
                frames_sent: s.frames_sent,
                frame_width: s.frame_width,
                frame_height: s.frame_height,
            })
            .collect(),
        candidate_pairs: report
            .candidate_pairs
            .into_iter()
            .map(|p| CandidatePairStats {
                current_round_trip_time_s: p.current_round_trip_time_s,
                total_round_trip_time_s: p.total_round_trip_time_s,
                priority: p.priority,
                state: map_pair_state(p.state),
                nominated: p.nominated,
                writable: p.writable,
                available_outgoing_bitrate_bps: p.available_outgoing_bitrate_bps,
                available_incoming_bitrate_bps: p.available_incoming_bitrate_bps,
                bytes_sent: p.bytes_sent,
                bytes_received: p.bytes_received,
                packets_sent: p.packets_sent,
                packets_received: p.packets_received,
                local_candidate_type: map_candidate_type(p.local_candidate_type),
                local_relay_protocol: map_relay_protocol(p.local_relay_protocol),
            })
            .collect(),
    }
}

/// Sink for a decoded remote video frame: the track it arrived on, BGRA pixels,
/// width, height, `frame_id`, capture timestamp in µs, and the sender's
/// `user_data` (empty when the frame carried no metadata trailer).
///
/// The track name leads because it is what makes the frame attributable. A model
/// may declare several recvonly video tracks, and every one of them decodes into
/// this same sink — without the name the host receives an interleaved stream it
/// has no way to separate.
type FrameCallback = Arc<dyn Fn(&str, &[u8], u32, u32, u64, u64, &[u8]) + Send + Sync + 'static>;

/// Sink for a decoded remote audio frame: the track it arrived on, interleaved
/// i16 PCM, sample rate in Hz, channel count.
type AudioCallback = Arc<dyn Fn(&str, &[i16], u32, u32) + Send + Sync + 'static>;

/// A local sendonly track, media-typed.
///
/// 0.13 split `Track` into `VideoTrack`/`AudioTrack`, and the push helpers live
/// on the typed halves — so the map keeps the variant rather than flattening
/// back to `Track` and losing the ability to push. Mirrors `RemoteTrack`, which
/// the receive side already gets handed.
enum LocalTrack {
    Video(VideoTrack),
    Audio(AudioTrack),
}

impl LocalTrack {
    /// The untyped handle, for `set_transceiver_track` and anything else that
    /// does not care which kind it has.
    fn as_track(&self) -> &Track {
        match self {
            Self::Video(t) => t,
            Self::Audio(t) => t,
        }
    }

    /// The video half, or `None` for an audio track — a video push aimed at an
    /// audio slot is a caller mistake, and silently doing nothing is what the
    /// warning below exists to avoid.
    fn video(&self) -> Option<&VideoTrack> {
        match self {
            Self::Video(t) => Some(t),
            Self::Audio(_) => None,
        }
    }
}

/// Bitrate bounds a caller asked for, in bits per second. `None` means "leave
/// this bound at the libwebrtc default".
#[derive(Default, Clone, Copy)]
struct BitrateBounds {
    min_bps: Option<i32>,
    start_bps: Option<i32>,
    max_bps: Option<i32>,
}

#[derive(Default)]
struct PeerState {
    pc: Option<Arc<PeerConnection>>,
    data_channel: Option<DataChannel>,
    control_channel: Option<DataChannel>,
    track_names: Vec<String>,
    track_directions: Vec<TrackDirection>,
    transceivers: Vec<Arc<Transceiver>>,
    local_tracks: HashMap<String, LocalTrack>,
    recv_tracks: Arc<Mutex<Vec<RemoteTrack>>>,
}

/// Bitrate bounds outlive the peer connection they were applied to.
///
/// `prepare` runs again on every reconnect and builds a fresh peer connection
/// and a fresh set of transceivers, which come up on libwebrtc's defaults. A
/// caller who raised a ceiling before connecting — the only point at which
/// `start_bps` can still do its job — means it for the session, not for the
/// first attempt at it, so these live outside `PeerState`, which `close` takes
/// wholesale.
#[derive(Default)]
struct BitrateConfig {
    connection: Mutex<BitrateBounds>,
    per_track: Mutex<HashMap<String, BitrateBounds>>,
}

impl BitrateConfig {
    /// Apply the bounds, and remember them only if that succeeded.
    ///
    /// The order is the whole point, and it is why this is a function rather than
    /// two statements: libwebrtc is the only thing that can say whether a
    /// combination is valid, so committing first leaves a rejected pair saved. The
    /// call returns an error, the caller believes nothing happened, and the next
    /// reconnect replays the bad values over bounds that were working — reported
    /// only as a log line, since `prepare` cannot fail a connect over a ceiling.
    fn commit_connection(
        &self,
        bounds: BitrateBounds,
        apply: impl FnOnce() -> Result<(), CoreError>,
    ) -> Result<(), CoreError> {
        apply()?;
        *self.connection.lock().unwrap() = bounds;
        Ok(())
    }

    /// As [`BitrateConfig::commit_connection`], for one track's sender.
    fn commit_track(
        &self,
        track_name: &str,
        bounds: BitrateBounds,
        apply: impl FnOnce() -> Result<(), CoreError>,
    ) -> Result<(), CoreError> {
        apply()?;
        self.per_track
            .lock()
            .unwrap()
            .insert(track_name.to_string(), bounds);
        Ok(())
    }
}

pub struct ReactorWebRtcPeerTransport {
    event_tx: UnboundedSender<PeerEvent>,
    factory: PeerConnectionFactory,
    state: Arc<Mutex<PeerState>>,
    bitrate: BitrateConfig,
    frame_cb: Option<FrameCallback>,
    audio_cb: Option<AudioCallback>,
}

impl ReactorWebRtcPeerTransport {
    pub fn new(event_tx: UnboundedSender<PeerEvent>) -> Self {
        Self::with_adm_mode(event_tx, default_adm_mode())
    }

    pub fn with_adm_mode(event_tx: UnboundedSender<PeerEvent>, mode: AdmMode) -> Self {
        info!("[peer] audio device module: {mode:?}");
        let factory = PeerConnectionFactory::builder()
            .with_adm(mode)
            .build()
            .expect("create PeerConnectionFactory");
        Self {
            event_tx,
            factory,
            state: Arc::new(Mutex::new(PeerState::default())),
            bitrate: BitrateConfig::default(),
            frame_cb: None,
            audio_cb: None,
        }
    }

    pub fn with_frame_callback(
        mut self,
        cb: impl Fn(&str, &[u8], u32, u32, u64, u64, &[u8]) + Send + Sync + 'static,
    ) -> Self {
        self.frame_cb = Some(Arc::new(cb));
        self
    }

    pub fn with_audio_callback(
        mut self,
        cb: impl Fn(&str, &[i16], u32, u32) + Send + Sync + 'static,
    ) -> Self {
        self.audio_cb = Some(Arc::new(cb));
        self
    }

    /// Apply the remembered bounds to a freshly built peer connection and its
    /// transceivers, before `prepare` publishes them as the live state.
    ///
    /// Best-effort by design: this runs inside connection setup, and a rejected
    /// ceiling is a quality problem, not a reason to fail the connect. Each
    /// failure is logged rather than swallowed, so a bound that did not take is
    /// visible without being fatal. A caller who wants the error handled calls
    /// `set_bitrate`/`set_track_bitrate` on a live connection, which returns it.
    fn apply_bitrate(
        &self,
        pc: &PeerConnection,
        names: &[String],
        transceivers: &[Arc<Transceiver>],
    ) {
        let bounds = *self.bitrate.connection.lock().unwrap();
        if bounds
            .min_bps
            .or(bounds.start_bps)
            .or(bounds.max_bps)
            .is_some()
        {
            if let Err(e) = pc.set_bitrate(bounds.min_bps, bounds.start_bps, bounds.max_bps) {
                warn!("[peer] connection bitrate bounds rejected: {e}");
            }
        }

        let per_track = self.bitrate.per_track.lock().unwrap();
        for (name, b) in per_track.iter() {
            let Some(i) = names.iter().position(|n| n == name) else {
                warn!("[peer] bitrate bounds set for unknown track '{name}' — ignored");
                continue;
            };
            if let Err(e) = transceivers[i].set_send_bitrate(b.min_bps, b.max_bps) {
                warn!("[peer] bitrate bounds for track '{name}' rejected: {e}");
            }
        }
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

        let recv_tracks: Arc<Mutex<Vec<RemoteTrack>>> = Arc::new(Mutex::new(Vec::new()));
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
            obs = obs.on_track(move |track| {
                let kind = track.kind();
                let idx = track_idx.fetch_add(1, Ordering::SeqCst);
                let (name, mid) = name_mids
                    .lock()
                    .unwrap()
                    .get(idx)
                    .map(|(n, m)| (Some(n.clone()), m.clone()))
                    .unwrap_or((None, None));
                info!("[peer] track received  kind={kind:?}  name={name:?}  mid={mid:?}");
                let _ = tx.unbounded_send(PeerEvent::TrackReceived {
                    name: name.clone(),
                    mid: mid.clone(),
                });
                // Empty when the transceiver could not be matched against the
                // negotiated mapping. The frames are still delivered — an
                // unattributable frame beats a dropped one — and the host sees a
                // track name it will not match to any declared track.
                let track_name = name.unwrap_or_default();
                // Matching on the track itself rather than on a kind read off it:
                // the variant is what carries the typed handle, so there is no
                // arm where the sink and the kind can disagree.
                match &track {
                    RemoteTrack::Video(video) => {
                        if let Some(cb) = frame_cb.clone() {
                            // No transform to attach: reactor-webrtc negotiates
                            // frame-metadata support in the SDP and installs the strip
                            // step itself, so VideoFrame::metadata is populated whenever
                            // the sender included a trailer and the peer agreed.
                            video.on_frame(move |f| {
                                let (frame_id, ts, ud) = f
                                    .metadata
                                    .as_ref()
                                    .map(|m| {
                                        (m.frame_id, m.capture_time_us, m.user_data.as_slice())
                                    })
                                    .unwrap_or((0, 0, &[]));
                                cb(&track_name, f.bgra, f.width, f.height, frame_id, ts, ud);
                            });
                        }
                    }
                    RemoteTrack::Audio(audio) => {
                        if let Some(cb) = audio_cb.clone() {
                            audio.on_frame(move |f| {
                                cb(&track_name, f.pcm, f.sample_rate, f.channels)
                            });
                        }
                    }
                }
                recv.lock().unwrap().push(track);
            });
            obs
        };

        let pc = self
            .factory
            .create_peer_connection(&config, observer)
            .map_err(peer_err)?;

        let mut transceivers: Vec<Arc<Transceiver>> = Vec::with_capacity(tracks.len());
        let mut track_names: Vec<String> = Vec::with_capacity(tracks.len());
        let mut track_directions: Vec<TrackDirection> = Vec::with_capacity(tracks.len());
        let mut local_tracks: HashMap<String, LocalTrack> = HashMap::new();

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
                    TrackKind::Video => LocalTrack::Video(
                        self.factory
                            .create_video_track(&track.name)
                            .map_err(peer_err)?,
                    ),
                    TrackKind::Audio => LocalTrack::Audio(
                        self.factory
                            .create_audio_track(&track.name)
                            .map_err(peer_err)?,
                    ),
                };
                tc.set_track(local.as_track()).map_err(peer_err)?;
                info!(
                    "[peer] attached sendonly {:?} track '{}'",
                    track.kind, track.name
                );
                local_tracks.insert(track.name.clone(), local);
            }

            transceivers.push(Arc::new(tc));
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
        data_ch.on_message(move |data, _binary| {
            let _ = tx.unbounded_send(PeerEvent::DataChannelMessage(data.to_vec()));
        });

        let tx = self.event_tx.clone();
        control_ch.on_open(move || {
            info!("[peer] control channel open");
            let _ = tx.unbounded_send(PeerEvent::ControlChannelOpen);
        });
        let tx = self.event_tx.clone();
        control_ch.on_message(move |data, _binary| {
            let _ = tx.unbounded_send(PeerEvent::ControlChannelMessage(data.to_vec()));
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

        self.apply_bitrate(&pc, &track_names, &transceivers);

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

    fn send_data(&self, payload: &[u8], binary: bool) -> Result<(), CoreError> {
        debug!("[peer] → data: {} byte(s), binary={binary}", payload.len());
        let s = self.state.lock().unwrap();
        let dc = s
            .data_channel
            .as_ref()
            .ok_or_else(|| CoreError::Peer("data channel not ready".into()))?;
        send_on_channel(dc, payload, binary)
    }

    fn send_control(&self, payload: &[u8]) -> Result<(), CoreError> {
        debug!("[peer] → control: {} byte(s)", payload.len());
        let s = self.state.lock().unwrap();
        let dc = s
            .control_channel
            .as_ref()
            .ok_or_else(|| CoreError::Peer("control channel not ready".into()))?;
        send_on_channel(dc, payload, true)
    }

    async fn set_track_direction(&self, track_name: &str, _active: bool) -> Result<(), CoreError> {
        let s = self.state.lock().unwrap();
        if !s.track_names.iter().any(|n| n == track_name) {
            return Err(CoreError::Peer(format!("unknown track: {track_name}")));
        }
        Ok(())
    }

    async fn set_bitrate(
        &self,
        min_bps: Option<i32>,
        start_bps: Option<i32>,
        max_bps: Option<i32>,
    ) -> Result<(), CoreError> {
        // Nothing to apply to before the first prepare is not an error: there is
        // no peer yet to validate against, and prepare picks the bounds up. Once
        // remembered they survive reconnects, which rebuild the peer connection on
        // libwebrtc's defaults.
        let pc = self.state.lock().unwrap().pc.clone();
        let bounds = BitrateBounds {
            min_bps,
            start_bps,
            max_bps,
        };
        self.bitrate.commit_connection(bounds, || match pc {
            Some(pc) => pc
                .set_bitrate(min_bps, start_bps, max_bps)
                .map_err(peer_err),
            None => Ok(()),
        })
    }

    async fn set_track_bitrate(
        &self,
        track_name: &str,
        min_bps: Option<i32>,
        max_bps: Option<i32>,
    ) -> Result<(), CoreError> {
        // The transceiver comes out from under the lock before the call:
        // SetParameters dispatches onto a libwebrtc thread and waits for it, and
        // that thread can be in a frame sink whose handler wants this same mutex
        // — the shape close() documents at length. Hence the Arc.
        let tc = {
            let s = self.state.lock().unwrap();
            match s.track_names.iter().position(|n| n == track_name) {
                Some(i) => Some(Arc::clone(&s.transceivers[i])),
                // Before the first prepare there are no transceivers to look the
                // name up in, so an unknown name cannot be told from an early one.
                // The core already refuses a name the session did not declare once
                // the declaration has arrived; prepare warns about the rest.
                None if s.track_names.is_empty() => None,
                None => {
                    return Err(CoreError::Peer(format!("unknown track: {track_name}")));
                }
            }
        };
        let bounds = BitrateBounds {
            min_bps,
            start_bps: None,
            max_bps,
        };
        self.bitrate.commit_track(track_name, bounds, || match tc {
            Some(tc) => tc.set_send_bitrate(min_bps, max_bps).map_err(peer_err),
            None => Ok(()),
        })
    }

    async fn get_stats(&self) -> Result<TransportStats, CoreError> {
        // The Arc comes out from under the lock before the call, for the reason
        // set_track_bitrate documents: the engine dispatches this onto a libwebrtc
        // thread and waits for the report, and that thread can be inside a frame
        // sink whose host handler wants this same mutex.
        let pc = self.state.lock().unwrap().pc.clone();
        let Some(pc) = pc else {
            return Err(CoreError::InvalidState(
                "no peer connection to read statistics from".into(),
            ));
        };

        // `get_stats` blocks the calling thread until the engine answers, up to
        // its own ten-second timeout. On a tokio worker that parks the worker,
        // and unlike the bitrate setters — called once, by hand — this one is
        // built to be polled, so every sample would cost a worker for as long as
        // libwebrtc took to answer.
        tokio::task::spawn_blocking(move || pc.get_stats().map(map_stats).map_err(peer_err))
            .await
            // A panic inside the blocking call, or a runtime shutting down under
            // it. Neither leaves a report, and neither is this transport's own
            // failure to describe any more precisely than by saying so.
            .map_err(|e| CoreError::Peer(format!("statistics collection failed: {e}")))?
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
        let Some(track) = s.local_tracks.get(track_name).and_then(LocalTrack::video) else {
            warn!("[peer] push_video_frame: no video source for track '{track_name}'");
            return;
        };
        let _ = track.push_frame(VideoFrame::new(data, width, height));
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
        let Some(track) = s.local_tracks.get(track_name).and_then(LocalTrack::video) else {
            warn!(
                "[peer] push_video_frame_with_metadata: no video source for track '{track_name}'"
            );
            return;
        };
        // Dropped by reactor-webrtc unless the peer declared that it strips the
        // trailer, so tagging a frame is safe whatever the far end supports.
        let _ = track.push_frame_with_metadata(VideoFrame::new(data, width, height), user_data);
    }

    fn push_video_frame_with_metadata_at(
        &self,
        track_name: &str,
        data: &[u8],
        width: u32,
        height: u32,
        user_data: &[u8],
        capture_time_us: i64,
    ) {
        let s = self.state.lock().unwrap();
        let Some(track) = s.local_tracks.get(track_name).and_then(LocalTrack::video) else {
            warn!(
                "[peer] push_video_frame_with_metadata_at: no video source for track \
                 '{track_name}'"
            );
            return;
        };
        // reactor-webrtc keys the trailer by capture millisecond, per track — so
        // the same capture time across several tracks is exactly the intended use
        // (one tick, several views), not a collision.
        let _ = track.push_frame_with_metadata_at(
            VideoFrame::new(data, width, height),
            user_data,
            capture_time_us,
        );
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

    /// Bounds the engine rejected must not be remembered.
    ///
    /// Committing first is the tempting order, and it is wrong in a way that only
    /// shows up later: the call reports the error, so the caller believes nothing
    /// happened, and then the next reconnect replays the rejected pair over bounds
    /// that were working — reported as a log line, since `prepare` will not fail a
    /// connect over a ceiling.
    #[test]
    fn a_rejected_bound_leaves_the_remembered_one_alone() {
        let config = BitrateConfig::default();
        let good = BitrateBounds {
            max_bps: Some(8_000_000),
            ..Default::default()
        };
        config.commit_connection(good, || Ok(())).expect("accepted");
        config
            .commit_track("cam", good, || Ok(()))
            .expect("accepted");

        let bad = BitrateBounds {
            min_bps: Some(9_000_000),
            max_bps: Some(1),
            ..Default::default()
        };
        let refuse = || Err(CoreError::Peer("min exceeds max".into()));
        assert!(config.commit_connection(bad, refuse).is_err());
        assert!(config.commit_track("cam", bad, refuse).is_err());

        assert_eq!(
            config.connection.lock().unwrap().max_bps,
            Some(8_000_000),
            "a refused connection bound overwrote the working one",
        );
        assert_eq!(
            config.per_track.lock().unwrap()["cam"].max_bps,
            Some(8_000_000),
            "a refused track bound overwrote the working one",
        );
    }

    /// Nothing to apply to is not a failure: before the first `prepare` there is no
    /// peer to validate against, and the bounds are what `prepare` will pick up.
    #[test]
    fn bounds_set_before_there_is_a_peer_are_still_remembered() {
        let config = BitrateConfig::default();
        let bounds = BitrateBounds {
            start_bps: Some(4_000_000),
            ..Default::default()
        };
        config
            .commit_connection(bounds, || Ok(()))
            .expect("no peer yet is not an error");
        assert_eq!(config.connection.lock().unwrap().start_bps, Some(4_000_000));
    }

    /// A typo must not silently open the microphone: the safe direction is the default,
    /// not the requested one.
    #[test]
    fn an_unrecognised_value_falls_back_to_synthetic() {
        assert_eq!(adm_mode_from_env(Some("platfrom")), AdmMode::Synthetic);
        assert_eq!(adm_mode_from_env(Some("")), AdmMode::Synthetic);
    }
}
