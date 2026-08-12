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
#[async_trait::async_trait]
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

    /// Push interleaved i16 PCM audio into the named sendonly track.
    /// Default implementation is a no-op — override in native transports.
    fn push_audio_frame(&self, _track_name: &str, _data: &[i16]) {}
}
