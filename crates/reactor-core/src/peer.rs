//! WebRTC engine abstraction.
//!
//! The core owns the *signaling and session logic*; the host owns the
//! *media engine* (libwebrtc natively) and implements [`PeerTransport`].
//! Engine callbacks are forwarded into the core as [`PeerEvent`]s via
//! [`crate::reactor::Reactor::handle_peer_event`].

use crate::protocol::session::TrackCapability;
use crate::protocol::webrtc::{IceCandidate, IceServer, TrackMappingEntry};

/// Mirror of `RTCPeerConnection.connectionState`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PeerConnectionState {
    New,
    Connecting,
    Connected,
    Disconnected,
    Failed,
    Closed,
}

/// Events the host's WebRTC engine must forward to the core.
#[derive(Debug, Clone)]
pub enum PeerEvent {
    ConnectionStateChanged(PeerConnectionState),
    DataChannelOpen,
    ControlChannelOpen,
    /// Binary `reactor_wire.v1` payload received on the data channel.
    DataChannelMessage(Vec<u8>),
    /// Binary `reactor_wire.v1` payload received on the control channel.
    ControlChannelMessage(Vec<u8>),
    /// A remote media track arrived. `name` if the engine resolved it,
    /// otherwise the core resolves it from the track mapping by `mid`.
    TrackReceived {
        name: Option<String>,
        mid: Option<String>,
    },
    /// Trickled local ICE candidate to relay to the server.
    IceCandidate(IceCandidate),
    /// Local ICE gathering completed.
    IceGatheringComplete,
}

/// Result of [`PeerTransport::prepare`]: a local SDP offer plus the
/// `mid` → track mapping for the signaling request.
#[derive(Debug, Clone)]
pub struct PreparedOffer {
    pub sdp_offer: String,
    pub track_mapping: Vec<TrackMappingEntry>,
}

/// Host-implemented WebRTC engine.
///
/// Expected behavior:
/// * `prepare` creates the peer connection with the given ICE servers, adds
///   one transceiver per track (matching each track's direction), creates
///   the `"data"` and `"control"` data channels, and returns the local
///   offer. It may be called again after `close` (reconnect).
/// * Media-level operations that the core cannot express (attaching a
///   camera track to a sender, rendering a remote track) stay in the host;
///   the core only drives the protocol around them.
#[cfg_attr(not(target_family = "wasm"), async_trait::async_trait)]
#[cfg_attr(target_family = "wasm", async_trait::async_trait(?Send))]
pub trait PeerTransport {
    /// Create the peer connection, transceivers and channels; return the
    /// local SDP offer and track mapping.
    async fn prepare(
        &self,
        ice_servers: &[IceServer],
        tracks: &[TrackCapability],
    ) -> Result<PreparedOffer, crate::error::CoreError>;

    /// Apply the remote SDP answer.
    async fn set_remote_description(&self, sdp_answer: &str)
        -> Result<(), crate::error::CoreError>;

    /// Send a message on the `"data"` channel. `binary` selects the
    /// WebRTC data-channel frame type: `true` for `reactor_wire.v1`
    /// protobuf payloads, `false` for the legacy JSON envelope (runtime-
    /// scoped commands not yet migrated — see [`crate::messaging::legacy`]).
    fn send_data(&self, payload: &[u8], binary: bool) -> Result<(), crate::error::CoreError>;

    /// Send a `reactor_wire.v1` binary message on the `"control"` channel.
    fn send_control(&self, payload: &[u8]) -> Result<(), crate::error::CoreError>;

    /// Activate (`true`) or deactivate (`false`) a track's transceiver —
    /// the local SDP renegotiation behind pause/resume.
    async fn set_track_direction(
        &self,
        track_name: &str,
        active: bool,
    ) -> Result<(), crate::error::CoreError>;

    /// Aggregate congestion-control bitrate bounds for the whole connection,
    /// in bits per second. `None` leaves a bound at the engine's default.
    ///
    /// This is the *connection's* budget — how much bandwidth the congestion
    /// controller may believe it has. It is not what caps any one track: see
    /// [`PeerTransport::set_track_bitrate`], which bounds a single sender and
    /// is the one that lifts WebRTC's resolution-keyed video ceiling. The two
    /// are conjunctive, so a stream runs fast only when both allow it.
    ///
    /// Defaults to refusing: a transport that silently ignored this would leave
    /// a caller believing they had raised a limit they had not.
    async fn set_bitrate(
        &self,
        _min_bps: Option<i32>,
        _start_bps: Option<i32>,
        _max_bps: Option<i32>,
    ) -> Result<(), crate::error::CoreError> {
        Err(crate::error::CoreError::Peer(
            "this transport does not support connection bitrate limits".into(),
        ))
    }

    /// Per-sender bitrate bounds for one track's transceiver, in bits per
    /// second. `None` leaves a bound at the engine's default.
    ///
    /// Without this, a video sender's ceiling is WebRTC's default for the frame
    /// size — 2500 kbps for anything above 960x540, so 720p, 1080p and 4K all
    /// cap at 2.5 Mbps regardless of how much headroom
    /// [`PeerTransport::set_bitrate`] granted the connection. Raising `max_bps`
    /// here is the only way past it.
    ///
    /// Defaults to refusing, for the same reason as above.
    async fn set_track_bitrate(
        &self,
        _track_name: &str,
        _min_bps: Option<i32>,
        _max_bps: Option<i32>,
    ) -> Result<(), crate::error::CoreError> {
        Err(crate::error::CoreError::Peer(
            "this transport does not support per-track bitrate limits".into(),
        ))
    }

    /// Tear down the peer connection and channels.
    async fn close(&self) -> Result<(), crate::error::CoreError>;

    /// Negotiated maximum data-channel message size.
    fn max_message_bytes(&self) -> usize {
        crate::protocol::DEFAULT_MAX_MESSAGE_BYTES
    }

    /// Push a raw BGRA video frame into the named sendonly track.
    /// `data` must be `width * height * 4` bytes (B, G, R, A order).
    /// Default implementation is a no-op — override in native transports.
    fn push_video_frame(&self, _track_name: &str, _data: &[u8], _width: u32, _height: u32) {}

    /// Push a frame tagged with `user_data`, to arrive on the peer as the frame's
    /// metadata.
    ///
    /// Defaults to dropping the tag and pushing the frame, so a transport that
    /// cannot carry metadata still sends media.
    fn push_video_frame_with_metadata(
        &self,
        track_name: &str,
        data: &[u8],
        width: u32,
        height: u32,
        _user_data: &[u8],
    ) {
        self.push_video_frame(track_name, data, width, height);
    }

    /// Push a tagged frame stamped with the caller's own capture time, in
    /// microseconds.
    ///
    /// The capture time is what lines frames up *across* tracks: pushed with the
    /// same value, one tick's several camera views carry one shared timestamp
    /// instead of the arrival time each of them happened to be stamped with.
    /// Tagging and stamping are independent — `user_data` may be empty.
    ///
    /// Defaults to dropping the capture time, so a transport that cannot carry
    /// one still sends the frame and its tag.
    fn push_video_frame_with_metadata_at(
        &self,
        track_name: &str,
        data: &[u8],
        width: u32,
        height: u32,
        user_data: &[u8],
        _capture_time_us: i64,
    ) {
        self.push_video_frame_with_metadata(track_name, data, width, height, user_data);
    }

    /// Push interleaved i16 PCM audio into the named sendonly track.
    /// Default implementation is a no-op — override in native transports.
    fn push_audio_frame(&self, _track_name: &str, _data: &[i16]) {}
}
