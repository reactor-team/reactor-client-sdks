//! Data-channel chunking in the browser: the wire format reactor-webrtc uses,
//! from [`reactor_webrtc_dc_chunking`], driven by `RTCDataChannel`'s
//! buffered-amount events.
//!
//! The offer declares `a=x-reactor-dc-chunking` in the copy sent to the
//! coordinator; the browser's own description is left as it wrote it, and it
//! ignores the attribute when the runtime's answer mirrors it. A channel is
//! chunked when that answer declared it, decided once it opens: both of this
//! SDK's channels are ordered and reliable, which is what the runtime checks
//! on its side.
//!
//! Frames are 4 KiB. A page cannot change the browser's SCTP `max_burst` (4),
//! and with it 4 KiB frames measured best at every message size.

use std::cell::{Cell, RefCell};
use std::rc::Rc;

use reactor_core::error::CoreError;
use reactor_webrtc_dc_chunking::sdp::{self, Params};
use reactor_webrtc_dc_chunking::{
    Delivery, Reassembler, SendConfig, SendError, SendQueue, BROWSER_CHUNK_SIZE,
    DEFAULT_MAX_MESSAGE_SIZE,
};
use web_sys::RtcDataChannel;

use crate::http::js_err;

/// The largest message this side sends or accepts, advertised in the offer.
const LOCAL_MAX_MESSAGE_SIZE: u64 = DEFAULT_MAX_MESSAGE_SIZE;

/// The offer as the coordinator receives it: the browser's, declaring
/// chunking.
pub(crate) fn declare(offer: &str) -> String {
    sdp::declare(offer, &Params::local(LOCAL_MAX_MESSAGE_SIZE))
}

/// One connection's negotiation: the runtime's parameters, once its answer
/// declared chunking. Shared with the channel callbacks, which decide from it.
#[derive(Clone, Default)]
pub(crate) struct Negotiation(Rc<Cell<Option<Params>>>);

impl Negotiation {
    /// Record what the runtime's answer declared.
    pub(crate) fn on_answer(&self, answer: &str) {
        self.0.set(sdp::parse(answer));
    }

    /// Forget an answer the browser rejected.
    pub(crate) fn clear(&self) {
        self.0.set(None);
    }
}

struct Chunked {
    queue: SendQueue,
    reassembler: Reassembler,
}

/// What a frame that arrived on a channel amounts to.
pub(crate) enum Received {
    /// A whole message for the core.
    Message(Vec<u8>),
    /// Nothing yet: part of a message, or one past this side's limit, dropped.
    Nothing,
    /// The peer broke the wire format; the channel has been closed.
    Broken,
}

/// One channel's framing, decided when it opens.
#[derive(Clone, Default)]
pub(crate) struct Framing(Rc<RefCell<Option<Chunked>>>);

impl Framing {
    /// Decide from the negotiation, once the channel is open. Returns whether
    /// the channel is chunked.
    pub(crate) fn decide(&self, channel: &RtcDataChannel, negotiation: &Negotiation) -> bool {
        let Some(remote) = negotiation.0.get() else {
            return false;
        };
        let config = SendConfig {
            chunk_size: BROWSER_CHUNK_SIZE,
            max_message_size: sdp::effective_max_message_size(LOCAL_MAX_MESSAGE_SIZE, &remote),
            ..SendConfig::default()
        };
        // Resume pumping at the queue's low-water mark.
        channel.set_buffered_amount_low_threshold(clamp_u32(config.low_water));
        *self.0.borrow_mut() = Some(Chunked {
            queue: SendQueue::new(config),
            reassembler: Reassembler::new(LOCAL_MAX_MESSAGE_SIZE),
        });
        true
    }

    /// The largest message the channel takes, once it is chunked.
    pub(crate) fn max_message_size(&self) -> Option<u64> {
        self.0
            .borrow()
            .as_ref()
            .map(|c| c.queue.config().max_message_size)
    }

    /// Queue a message and start it moving. `None` when the channel is plain.
    pub(crate) fn send(
        &self,
        channel: &RtcDataChannel,
        payload: &[u8],
        binary: bool,
    ) -> Option<Result<(), CoreError>> {
        let queued = {
            let mut framing = self.0.borrow_mut();
            let chunked = framing.as_mut()?;
            chunked.queue.push(payload.to_vec(), binary)
        };
        Some(match queued {
            Ok(()) => self.pump(channel),
            Err(SendError::TooLarge { size, max }) => Err(CoreError::MessageTooLarge {
                size: size as usize,
                max: max as usize,
            }),
            Err(full @ SendError::QueueFull { .. }) => Err(CoreError::Peer(full.to_string())),
        })
    }

    /// Hand the browser frames while its buffer has room. A failed send means
    /// the channel is going away: what is queued can no longer follow.
    pub(crate) fn pump(&self, channel: &RtcDataChannel) -> Result<(), CoreError> {
        let mut framing = self.0.borrow_mut();
        let Some(chunked) = framing.as_mut() else {
            return Ok(());
        };
        while let Some(frame) = chunked
            .queue
            .next_frame(u64::from(channel.buffered_amount()))
        {
            if let Err(error) = channel.send_with_u8_array(&frame) {
                chunked.queue.clear();
                return Err(js_err(error));
            }
        }
        Ok(())
    }

    /// Take a frame off a chunked channel. `None` when the channel is plain.
    /// Every frame is binary; a text frame is a broken stream.
    pub(crate) fn receive(
        &self,
        channel: &RtcDataChannel,
        frame: &[u8],
        text: bool,
    ) -> Option<Received> {
        let mut framing = self.0.borrow_mut();
        let chunked = framing.as_mut()?;
        let delivery = if text {
            Err(reactor_webrtc_dc_chunking::FrameError::TypeChanged)
        } else {
            chunked.reassembler.push(frame)
        };
        Some(match delivery {
            Ok(Delivery::Message { data, .. }) => Received::Message(data),
            Ok(Delivery::Pending) => Received::Nothing,
            Ok(Delivery::Dropped { size }) => {
                log::warn!(
                    "[reactor-wasm] dropped a {size}-byte message, larger than this side accepts"
                );
                Received::Nothing
            }
            Err(error) => {
                log::warn!("[reactor-wasm] data-channel framing broken ({error:?}); closing");
                chunked.reassembler.reset();
                chunked.queue.clear();
                channel.close();
                Received::Broken
            }
        })
    }
}

fn clamp_u32(n: u64) -> u32 {
    u32::try_from(n).unwrap_or(u32::MAX)
}
