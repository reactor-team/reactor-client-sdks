//! [`PeerTransport`] implementation backed by the browser `RTCPeerConnection`.
//!
//! Shape of the thing:
//! * The core holds this as `Arc<dyn PeerTransport>` and drives the protocol.
//! * [`crate::ReactorClient`] also holds the concrete `Arc<WasmPeerTransport>`,
//!   so it can hand JS the browser objects the trait has no vocabulary for —
//!   the peer connection, a received `MediaStreamTrack`, a sender's track.
//! * Every WebRTC callback pushes a [`PeerEvent`] into an unbounded channel that
//!   the client's pump task drains into `Reactor::handle_peer_event`. Nothing in
//!   a JS callback awaits, so no callback can re-enter the core mid-borrow.

use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::rc::Rc;

use futures::channel::mpsc::UnboundedSender;
use wasm_bindgen::prelude::*;
use wasm_bindgen_futures::JsFuture;
use web_sys::{
    MediaStream, MediaStreamTrack, MessageEvent, RtcConfiguration, RtcDataChannel,
    RtcDataChannelType, RtcPeerConnection, RtcPeerConnectionIceEvent, RtcPeerConnectionState,
    RtcRtpTransceiver, RtcRtpTransceiverDirection, RtcRtpTransceiverInit, RtcSdpType,
    RtcSessionDescriptionInit, RtcTrackEvent,
};

use reactor_core::error::CoreError;
use reactor_core::peer::{PeerConnectionState, PeerEvent, PeerTransport, PreparedOffer};
use reactor_core::protocol::session::{TrackCapability, TrackDirection, TrackKind};
use reactor_core::protocol::webrtc::{IceCandidate, IceServer, TrackMappingEntry};
use reactor_core::protocol::DEFAULT_MAX_MESSAGE_BYTES;

use crate::http::{describe, js_err};

/// A remote track and the stream it arrived on, kept together because the JS
/// SDK's `trackReceived` event has always carried both (a `<video>` element
/// wants a stream, `getStats` wants the track).
#[derive(Clone)]
pub struct ReceivedTrack {
    pub track: MediaStreamTrack,
    pub stream: MediaStream,
}

type ReceivedTracks = Rc<RefCell<HashMap<String, ReceivedTrack>>>;

/// Live connection state, replaced wholesale by every `prepare()`.
struct PeerState {
    pc: RtcPeerConnection,
    data_channel: RtcDataChannel,
    control_channel: RtcDataChannel,
    /// track name → transceiver, for pause/resume and for attaching senders.
    transceivers: HashMap<String, RtcRtpTransceiver>,
    /// track name → the direction it was negotiated with, restored on resume.
    track_directions: HashMap<String, RtcRtpTransceiverDirection>,
    /// JS closures live as long as the peer connection that calls them.
    _closures: Vec<JsValue>,
}

pub struct WasmPeerTransport {
    state: RefCell<Option<PeerState>>,
    event_tx: UnboundedSender<PeerEvent>,
    /// mid → remote track. Behind `Rc` so the `ontrack` closure shares the map
    /// rather than a copy of it.
    received_tracks: ReceivedTracks,
    /// SCTP's negotiated `maxMessageSize`, read once the answer is applied.
    max_message_bytes: Cell<usize>,
}

impl WasmPeerTransport {
    pub fn new(event_tx: UnboundedSender<PeerEvent>) -> Self {
        Self {
            state: RefCell::new(None),
            event_tx,
            received_tracks: Rc::new(RefCell::new(HashMap::new())),
            max_message_bytes: Cell::new(DEFAULT_MAX_MESSAGE_BYTES),
        }
    }

    /// The live `RTCPeerConnection`, for `getStats()` and element attachment.
    pub fn peer_connection(&self) -> Option<RtcPeerConnection> {
        self.state.borrow().as_ref().map(|s| s.pc.clone())
    }

    pub fn received_track(&self, mid: &str) -> Option<ReceivedTrack> {
        self.received_tracks.borrow().get(mid).cloned()
    }

    /// Attach a local `MediaStreamTrack` to a sendonly track's sender.
    /// Awaited rather than fired and forgotten: `replaceTrack` rejects on a kind
    /// mismatch and on a closed sender, and a caller told its track was published
    /// while nothing is on the wire has no way to find that out.
    pub async fn replace_sender_track(
        &self,
        track_name: &str,
        track: Option<&MediaStreamTrack>,
    ) -> Result<(), CoreError> {
        // Take the promise and release the borrow before awaiting: the peer state
        // is reachable from the callbacks that run while we wait.
        let promise = {
            let state = self.state.borrow();
            let state = state.as_ref().ok_or_else(not_prepared)?;
            let transceiver = state
                .transceivers
                .get(track_name)
                .ok_or_else(|| no_transceiver(track_name))?;
            transceiver.sender().replace_track(track)
        };
        JsFuture::from(promise).await.map_err(|error| {
            CoreError::InvalidState(format!(
                "replaceTrack on '{track_name}' failed: {}",
                describe(&error)
            ))
        })?;
        Ok(())
    }

    /// Cache SCTP's negotiated `maxMessageSize` so the core can reject an
    /// oversized command before it hits the wire, where the browser would
    /// answer with a bare "operation failed". Only known once the answer is
    /// applied.
    ///
    /// Read through `Reflect`: web-sys has no `RTCSctpTransport` binding, and
    /// the number is worth having.
    fn refresh_max_message_bytes(&self) {
        let negotiated = self.state.borrow().as_ref().and_then(|state| {
            let sctp = js_sys::Reflect::get(&state.pc, &JsValue::from_str("sctp")).ok()?;
            js_sys::Reflect::get(&sctp, &JsValue::from_str("maxMessageSize"))
                .ok()?
                .as_f64()
        });
        // The value is a double, and both ends of it are meaningless as a byte
        // count: `Infinity` means "no limit", 0 means "cannot send at all".
        match negotiated {
            Some(size) if size.is_finite() && size >= 1.0 => {
                self.max_message_bytes.set(size as usize);
            }
            _ => self.max_message_bytes.set(DEFAULT_MAX_MESSAGE_BYTES),
        }
    }
}

#[async_trait::async_trait(?Send)]
impl PeerTransport for WasmPeerTransport {
    async fn prepare(
        &self,
        ice_servers: &[IceServer],
        tracks: &[TrackCapability],
    ) -> Result<PreparedOffer, CoreError> {
        // A stale connection from a previous attempt would keep decoding and
        // keep its callbacks wired to our channel; reconnect() calls prepare()
        // again, so close first.
        self.close().await?;

        let pc = RtcPeerConnection::new_with_configuration(&ice_configuration(ice_servers))
            .map_err(js_err)?;

        let mut closures: Vec<JsValue> = Vec::new();
        let mut transceivers: HashMap<String, RtcRtpTransceiver> = HashMap::new();
        let mut track_directions: HashMap<String, RtcRtpTransceiverDirection> = HashMap::new();

        // Control channel first, then data. On a runtime that predates
        // per-label channel routing, every inbound channel collapses onto one
        // handle (last write wins), and this order makes that handle the data
        // channel — where the model's own traffic rides. Newer runtimes key on
        // the label and do not care.
        let control_channel = pc.create_data_channel("control");
        let data_channel = pc.create_data_channel("data");
        // The wire is protobuf: without this, binary frames arrive as Blobs and
        // would need an async read before the core could decode them.
        control_channel.set_binary_type(RtcDataChannelType::Arraybuffer);
        data_channel.set_binary_type(RtcDataChannelType::Arraybuffer);

        // One transceiver per declared track, in declaration order — the mids
        // the browser assigns are what the track mapping is built from.
        for track in tracks {
            let kind = match track.kind {
                TrackKind::Video => "video",
                TrackKind::Audio => "audio",
            };
            let direction = match track.direction {
                TrackDirection::Recvonly => RtcRtpTransceiverDirection::Recvonly,
                TrackDirection::Sendonly => RtcRtpTransceiverDirection::Sendonly,
            };
            let init = RtcRtpTransceiverInit::new();
            init.set_direction(direction);
            let transceiver = pc.add_transceiver_with_str_and_init(kind, &init);
            transceivers.insert(track.name.clone(), transceiver);
            track_directions.insert(track.name.clone(), direction);
        }

        // ── ICE ───────────────────────────────────────────────────────────────
        {
            let tx = self.event_tx.clone();
            let callback = Closure::<dyn FnMut(_)>::new(move |event: RtcPeerConnectionIceEvent| {
                let peer_event = match event.candidate() {
                    Some(candidate) => PeerEvent::IceCandidate(IceCandidate {
                        candidate: candidate.candidate(),
                        sdp_mid: candidate.sdp_mid(),
                        sdp_mline_index: candidate.sdp_m_line_index(),
                    }),
                    // A null candidate is the end-of-gathering signal.
                    None => PeerEvent::IceGatheringComplete,
                };
                let _ = tx.unbounded_send(peer_event);
            });
            pc.set_onicecandidate(Some(callback.as_ref().unchecked_ref()));
            closures.push(callback.into_js_value());
        }

        // ── Connection state ──────────────────────────────────────────────────
        {
            let tx = self.event_tx.clone();
            let pc_for_state = pc.clone();
            let callback = Closure::<dyn FnMut()>::new(move || {
                let state = match pc_for_state.connection_state() {
                    RtcPeerConnectionState::New => PeerConnectionState::New,
                    RtcPeerConnectionState::Connecting => PeerConnectionState::Connecting,
                    RtcPeerConnectionState::Connected => PeerConnectionState::Connected,
                    RtcPeerConnectionState::Disconnected => PeerConnectionState::Disconnected,
                    RtcPeerConnectionState::Failed => PeerConnectionState::Failed,
                    RtcPeerConnectionState::Closed => PeerConnectionState::Closed,
                    // Forward-compat: a state this web-sys doesn't know is not
                    // a reason to report a connection that isn't there.
                    _ => PeerConnectionState::New,
                };
                let _ = tx.unbounded_send(PeerEvent::ConnectionStateChanged(state));
            });
            pc.set_onconnectionstatechange(Some(callback.as_ref().unchecked_ref()));
            closures.push(callback.into_js_value());
        }

        // ── Remote tracks ─────────────────────────────────────────────────────
        {
            let tx = self.event_tx.clone();
            let received = Rc::clone(&self.received_tracks);
            let callback = Closure::<dyn FnMut(_)>::new(move |event: RtcTrackEvent| {
                let track = event.track();
                let mid = event.transceiver().mid().unwrap_or_default();
                // Firefox and Chrome both populate `streams`, but the spec
                // allows an empty list; wrap the track so consumers always get
                // something they can put on a media element.
                let stream = event
                    .streams()
                    .get(0)
                    .dyn_into::<MediaStream>()
                    .ok()
                    .or_else(|| {
                        let tracks = js_sys::Array::new();
                        tracks.push(&track);
                        MediaStream::new_with_tracks(&tracks.into()).ok()
                    });
                if let Some(stream) = stream {
                    received.borrow_mut().insert(
                        mid.clone(),
                        ReceivedTrack {
                            track: track.clone(),
                            stream,
                        },
                    );
                }
                // The core resolves the track's name from the mapping by mid.
                let _ = tx.unbounded_send(PeerEvent::TrackReceived {
                    name: None,
                    mid: Some(mid),
                });
            });
            pc.set_ontrack(Some(callback.as_ref().unchecked_ref()));
            closures.push(callback.into_js_value());
        }

        // ── Channels ──────────────────────────────────────────────────────────
        wire_channel(
            &data_channel,
            &self.event_tx,
            PeerEvent::DataChannelOpen,
            PeerEvent::DataChannelMessage,
            &mut closures,
        );
        wire_channel(
            &control_channel,
            &self.event_tx,
            PeerEvent::ControlChannelOpen,
            PeerEvent::ControlChannelMessage,
            &mut closures,
        );

        // ── Offer ─────────────────────────────────────────────────────────────
        //
        // The browser's offer is sent as the browser wrote it. The JS SDK used to
        // rewrite it first — payload types remapped into [96,127], telephone-event
        // stripped, attributes reordered — to work around a GStreamer runtime that
        // mishandled H265 on payload type 45. That runtime is not what this client
        // talks to, so the workaround is not carried over: munging an offer we do
        // not need to munge is a way to break codec negotiation, not a safety net.
        let offer = JsFuture::from(pc.create_offer()).await.map_err(js_err)?;
        let sdp_offer = js_sys::Reflect::get(&offer, &JsValue::from_str("sdp"))
            .ok()
            .and_then(|value| value.as_string())
            .ok_or_else(|| CoreError::Decode("createOffer returned no sdp".into()))?;

        let local = RtcSessionDescriptionInit::new(RtcSdpType::Offer);
        local.set_sdp(&sdp_offer);
        // setLocalDescription starts ICE gathering, so the candidate callback
        // above has to be wired before this point — it is.
        JsFuture::from(pc.set_local_description(&local))
            .await
            .map_err(js_err)?;

        // mids exist only after setLocalDescription.
        let track_mapping = tracks
            .iter()
            .filter_map(|track| {
                let transceiver = transceivers.get(&track.name)?;
                Some(TrackMappingEntry {
                    name: track.name.clone(),
                    kind: track.kind,
                    direction: track.direction,
                    mid: transceiver.mid().unwrap_or_default(),
                })
            })
            .collect();

        *self.state.borrow_mut() = Some(PeerState {
            pc,
            data_channel,
            control_channel,
            transceivers,
            track_directions,
            _closures: closures,
        });

        Ok(PreparedOffer {
            sdp_offer,
            track_mapping,
        })
    }

    async fn set_remote_description(&self, sdp_answer: &str) -> Result<(), CoreError> {
        let promise = {
            let state = self.state.borrow();
            let state = state.as_ref().ok_or_else(not_prepared)?;
            let answer = RtcSessionDescriptionInit::new(RtcSdpType::Answer);
            answer.set_sdp(sdp_answer);
            state.pc.set_remote_description(&answer)
        };
        JsFuture::from(promise).await.map_err(js_err)?;
        self.refresh_max_message_bytes();
        Ok(())
    }

    fn send_data(&self, payload: &[u8], binary: bool) -> Result<(), CoreError> {
        let state = self.state.borrow();
        let state = state.as_ref().ok_or_else(not_prepared)?;
        send(&state.data_channel, payload, binary)
    }

    fn send_control(&self, payload: &[u8]) -> Result<(), CoreError> {
        let state = self.state.borrow();
        let state = state.as_ref().ok_or_else(not_prepared)?;
        send(&state.control_channel, payload, true)
    }

    async fn set_track_direction(&self, track_name: &str, active: bool) -> Result<(), CoreError> {
        // Flipping the transceiver direction is the whole of the local side of
        // pause/resume: the browser stops decoding an inactive receiver. The
        // server is told separately, by the control-channel notification the
        // core sends, so this needs no SDP round-trip.
        let state = self.state.borrow();
        let state = state.as_ref().ok_or_else(not_prepared)?;
        let transceiver = state
            .transceivers
            .get(track_name)
            .ok_or_else(|| no_transceiver(track_name))?;
        let direction = if active {
            state
                .track_directions
                .get(track_name)
                .copied()
                .unwrap_or(RtcRtpTransceiverDirection::Recvonly)
        } else {
            RtcRtpTransceiverDirection::Inactive
        };
        transceiver.set_direction(direction);
        Ok(())
    }

    async fn close(&self) -> Result<(), CoreError> {
        // Take the state out first: `RTCPeerConnection.close()` fires no further
        // events, but dropping the closures while still borrowed would not be
        // safe to reason about, and a second close() must be a no-op.
        let state = self.state.borrow_mut().take();
        if let Some(state) = state {
            state.data_channel.close();
            state.control_channel.close();
            state.pc.close();
        }
        self.received_tracks.borrow_mut().clear();
        self.max_message_bytes.set(DEFAULT_MAX_MESSAGE_BYTES);
        Ok(())
    }

    fn max_message_bytes(&self) -> usize {
        self.max_message_bytes.get()
    }
}

// ── helpers ───────────────────────────────────────────────────────────────────

/// Build `RTCConfiguration` from the coordinator's ICE servers.
///
/// `RtcIceServer` is a dictionary type, so it is assembled with `Reflect` —
/// web-sys' setters differ across versions but the JS shape does not.
fn ice_configuration(ice_servers: &[IceServer]) -> RtcConfiguration {
    let configuration = RtcConfiguration::new();
    let servers = js_sys::Array::new();
    for server in ice_servers {
        let entry = js_sys::Object::new();
        let urls = js_sys::Array::new();
        for uri in &server.uris {
            urls.push(&JsValue::from_str(uri));
        }
        let _ = js_sys::Reflect::set(&entry, &JsValue::from_str("urls"), &urls);
        if let Some(credentials) = &server.credentials {
            let _ = js_sys::Reflect::set(
                &entry,
                &JsValue::from_str("username"),
                &JsValue::from_str(&credentials.username),
            );
            let _ = js_sys::Reflect::set(
                &entry,
                &JsValue::from_str("credential"),
                &JsValue::from_str(&credentials.password),
            );
        }
        servers.push(&entry);
    }
    let _ = js_sys::Reflect::set(&configuration, &JsValue::from_str("iceServers"), &servers);
    configuration
}

/// Wire a channel's `onopen` / `onmessage` to the core's peer-event channel.
fn wire_channel(
    channel: &RtcDataChannel,
    event_tx: &UnboundedSender<PeerEvent>,
    open: PeerEvent,
    message: fn(Vec<u8>) -> PeerEvent,
    closures: &mut Vec<JsValue>,
) {
    {
        let tx = event_tx.clone();
        let callback = Closure::<dyn FnMut()>::new(move || {
            let _ = tx.unbounded_send(open.clone());
        });
        channel.set_onopen(Some(callback.as_ref().unchecked_ref()));
        closures.push(callback.into_js_value());
    }
    {
        let tx = event_tx.clone();
        let callback = Closure::<dyn FnMut(_)>::new(move |event: MessageEvent| {
            if let Some(payload) = message_bytes(&event.data()) {
                let _ = tx.unbounded_send(message(payload));
            }
        });
        channel.set_onmessage(Some(callback.as_ref().unchecked_ref()));
        closures.push(callback.into_js_value());
    }
}

/// A data-channel frame as bytes.
///
/// `binaryType = "arraybuffer"` makes the protobuf frames `ArrayBuffer`s. A
/// string frame still decodes: the core is told the same bytes either way and
/// decides what they are.
fn message_bytes(data: &JsValue) -> Option<Vec<u8>> {
    if let Some(buffer) = data.dyn_ref::<js_sys::ArrayBuffer>() {
        return Some(js_sys::Uint8Array::new(buffer).to_vec());
    }
    if let Some(view) = data.dyn_ref::<js_sys::Uint8Array>() {
        return Some(view.to_vec());
    }
    if let Some(text) = data.as_string() {
        return Some(text.into_bytes());
    }
    log::warn!("[reactor-wasm] dropping data-channel frame of unsupported type");
    None
}

/// Send on a channel, as a binary or text frame.
fn send(channel: &RtcDataChannel, payload: &[u8], binary: bool) -> Result<(), CoreError> {
    if !binary {
        let text = std::str::from_utf8(payload)
            .map_err(|_| CoreError::Decode("non-utf8 payload sent as a text frame".into()))?;
        return channel.send_with_str(text).map_err(js_err);
    }
    channel.send_with_u8_array(payload).map_err(js_err)
}

fn not_prepared() -> CoreError {
    CoreError::InvalidState("peer transport not prepared".into())
}

fn no_transceiver(name: &str) -> CoreError {
    CoreError::InvalidState(format!("no transceiver for track '{name}'"))
}
