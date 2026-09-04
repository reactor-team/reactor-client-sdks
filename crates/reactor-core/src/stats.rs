//! Connection statistics: one snapshot, and the rates derived from two.
//!
//! [`crate::peer::PeerTransport::get_stats`] answers with counters — bytes and
//! packets since the peer connection came up, plus a few instantaneous readings.
//! A caller wants bitrates. Turning one into the other needs a previous sample,
//! and that is all [`StatsSampler`] is: the previous sample, and the arithmetic
//! between it and this one.
//!
//! It lives in the core rather than in each binding on purpose. The browser SDK
//! does the same arithmetic in TypeScript because there the counters come from
//! `RTCPeerConnection.getStats()` and never cross this crate; every FFI-based
//! binding, by contrast, would otherwise reimplement it, and two bindings
//! disagreeing about what "outgoing bitrate" means is worse than either answer.
//!
//! # Reading the same numbers the browser reads
//!
//! Since `reactor-webrtc` 0.15 (REA-6019) the engine reports the fields this
//! needs to match `sdks/js/src/internal/stats.ts`, and it now does: the same
//! candidate pair, the same byte counters, the same video stream. Three choices
//! are worth knowing about, because each one used to be forced and is now
//! deliberate:
//!
//! * **The pair is the *nominated* one**, not the highest-priority `succeeded`
//!   one. Only the nominated pair carries traffic; the rest report zeroes.
//! * **The bitrates come from that pair's byte counters**, so they cover
//!   everything it carried — RTCP and data channel included — which is what the
//!   browser measures. Summing the per-stream RTP counters instead read low.
//! * **Jitter, loss and fps come from the video stream**, found by
//!   [`crate::peer::StreamKind`]. With no video stream, jitter and loss fall back
//!   to the aggregate across receive streams, where the browser reports nothing
//!   at all; that is the one place this deliberately answers more than the
//!   browser does, because an audio-only session having no readable jitter is a
//!   limitation rather than a definition.

use std::sync::Mutex;

use serde::Serialize;

use crate::peer::{CandidatePairStats, StreamKind, TransportStats};

/// The shortest window a rate is derived over, in milliseconds.
///
/// Two samples taken microseconds apart divide a byte count by almost nothing,
/// and the answer is noise wearing the units of a bitrate — worse than no answer,
/// because it looks like one. Below this the rate fields report `None` and the
/// baseline is *kept*: the sample after it then measures over the longer window
/// rather than starting over, so a caller polling faster than this still gets
/// rates, just not on every call.
pub const MIN_RATE_WINDOW_MS: f64 = 200.0;

/// A derived statistics snapshot.
///
/// The scalar fields at the top are the summary — what a health check or an
/// overlay reads. The three arrays at the bottom are the engine's own report,
/// unaggregated, for a caller who needs per-stream detail.
///
/// Every scalar that can be genuinely unknown is an `Option`, and unknown is
/// serialized as `null` rather than omitted: a binding decoding this can then
/// tell "the engine did not measure this yet" from "this SDK does not report it",
/// which an absent key cannot.
#[derive(Debug, Clone, Default, PartialEq, Serialize)]
pub struct ConnectionStats {
    /// Round-trip time in milliseconds, from the selected ICE candidate pair.
    ///
    /// "Selected" is the pair ICE nominated. With no nominated pair reporting an
    /// RTT yet, this falls back to the largest any send stream measured — which
    /// comes from the far end's RTCP report about us, so it too takes a moment to
    /// appear. `None` until one of them has a reading.
    pub rtt_ms: Option<f64>,

    /// Jitter on the received video stream, in seconds.
    ///
    /// The same stream the browser SDK reads. With no video stream, the worst
    /// jitter across the receive streams there are — see the module docs. Read
    /// `inbound` for the per-stream values.
    pub jitter_s: Option<f64>,

    /// Fraction of inbound packets lost since the connection came up, `0.0`-`1.0`.
    ///
    /// Cumulative rather than per-window, matching the browser SDK, and taken
    /// from the video stream for the same reason `jitter_s` is. With no video
    /// stream it is summed across receive streams, with each stream's count
    /// floored at zero first: a negative count is that stream's duplicates and
    /// must not cancel a real loss on another. `None` until at least one packet
    /// has been accounted for.
    pub packet_loss_ratio: Option<f64>,

    /// Receive rate over the window since the previous sample, in bits per second.
    ///
    /// Measured on the nominated candidate pair, so it covers everything that
    /// pair carried — RTP, RTCP and the data channel — which is what the browser
    /// SDK's `incomingBitrate` measures.
    ///
    /// `None` on the first sample, when the window was shorter than
    /// [`MIN_RATE_WINDOW_MS`], when there is no nominated pair yet, or when the
    /// connection changed under it: a reconnect nominates a different pair and
    /// brings up new SSRCs, both of whose counters restart from zero, and
    /// differencing across that would report a large negative rate.
    pub incoming_bitrate_bps: Option<f64>,

    /// Send rate over the same window, in bits per second. As
    /// [`ConnectionStats::incoming_bitrate_bps`].
    pub outgoing_bitrate_bps: Option<f64>,

    /// The congestion controller's own estimate of what the path can carry, in
    /// bits per second, from the nominated pair.
    ///
    /// Not a measurement of what is flowing — compare
    /// [`ConnectionStats::incoming_bitrate_bps`], which is. `None` until the
    /// controller has an estimate, which needs media on the wire: a
    /// data-channel-only connection reports nothing here for its whole life.
    pub available_incoming_bitrate_bps: Option<f64>,
    /// As above, for the send direction.
    pub available_outgoing_bitrate_bps: Option<f64>,

    /// What the encoders are currently aiming at, summed across send streams, in
    /// bits per second.
    ///
    /// The target, not the achieved rate — compare with
    /// [`ConnectionStats::outgoing_bitrate_bps`], which is measured. `None` when
    /// nothing is being sent.
    pub target_bitrate_bps: Option<f64>,

    /// Frames per second on the received video stream. `None` with no video
    /// stream, and until the engine has measured a window's worth.
    pub frames_per_second: Option<f64>,

    /// Transport type of the nominated pair's local candidate: `"host"`,
    /// `"srflx"`, `"prflx"` or `"relay"`.
    ///
    /// `"relay"` means the media is going through a TURN server, which is the
    /// first thing worth knowing when latency is bad. `None` before ICE has
    /// selected anything — the same point at which the browser's `candidateType`
    /// is undefined.
    pub candidate_type: Option<&'static str>,

    /// Transport to the TURN server when `candidate_type` is `"relay"`:
    /// `"udp"`, `"tcp"` or `"tls"`. `None` when the path is not relayed.
    ///
    /// Not a field the browser SDK reports.
    pub relay_protocol: Option<&'static str>,

    /// State of the pair `rtt_ms` was read from: `"succeeded"`, `"waiting"`,
    /// `"in-progress"`, `"failed"` or `"cancelled"`. `None` when the engine
    /// reported no pairs at all, which is what an unconnected transport looks
    /// like.
    pub candidate_pair_state: Option<&'static str>,

    /// Cumulative counters, summed across streams. Present even when the derived
    /// rates above are not, so a caller can do its own arithmetic over whatever
    /// window it likes.
    pub packets_received: u64,
    /// Signed, and the sign is meaningful: RFC 3550 allows a negative count when
    /// duplicates arrive. This is the plain sum across receive streams, so one
    /// stream's duplicates do offset another's losses here —
    /// [`ConnectionStats::packet_loss_ratio`] is the field to read for "how bad
    /// is it".
    pub packets_lost: i64,
    pub packets_sent: u64,
    pub bytes_received: u64,
    pub bytes_sent: u64,

    /// When this sample was taken, in Unix milliseconds — the host's wall clock,
    /// via [`crate::runtime::Platform::now_ms`].
    pub timestamp_ms: f64,

    /// The engine's per-stream report, unaggregated.
    pub inbound: Vec<InboundStats>,
    pub outbound: Vec<OutboundStats>,
    pub candidate_pairs: Vec<CandidatePair>,
}

/// One receive stream, as serialized. Mirrors [`crate::peer::InboundRtpStats`].
#[derive(Debug, Clone, Default, PartialEq, Serialize)]
pub struct InboundStats {
    pub ssrc: u32,
    /// `"audio"`, `"video"`, or `null` when the engine reported no kind.
    pub kind: Option<&'static str>,
    pub packets_received: u32,
    pub packets_lost: i32,
    pub bytes_received: u64,
    pub jitter_s: f64,
    pub nack_count: u32,
    pub total_decode_time_s: f64,
    pub frames_per_second: f64,
    pub frames_decoded: u32,
    pub frames_dropped: u32,
    pub frame_width: u32,
    pub frame_height: u32,
}

/// One send stream, as serialized. Mirrors [`crate::peer::OutboundRtpStats`].
#[derive(Debug, Clone, Default, PartialEq, Serialize)]
pub struct OutboundStats {
    pub ssrc: u32,
    /// `"audio"`, `"video"`, or `null` when the engine reported no kind.
    pub kind: Option<&'static str>,
    pub packets_sent: u64,
    pub retransmitted_packets_sent: u64,
    pub bytes_sent: u64,
    pub target_bitrate_bps: f64,
    pub round_trip_time_s: f64,
    pub total_round_trip_time_s: f64,
    /// The receiver's own numbers for this stream, from its RTCP report.
    pub fraction_lost: f64,
    pub packets_lost: i32,
    pub frames_per_second: f64,
    pub frames_sent: u32,
    pub frame_width: u32,
    pub frame_height: u32,
}

/// One ICE candidate pair, as serialized. Mirrors
/// [`crate::peer::CandidatePairStats`].
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct CandidatePair {
    pub current_round_trip_time_s: f64,
    pub total_round_trip_time_s: f64,
    pub priority: u64,
    pub state: &'static str,
    pub nominated: bool,
    pub writable: bool,
    pub available_outgoing_bitrate_bps: f64,
    pub available_incoming_bitrate_bps: f64,
    pub bytes_sent: u64,
    pub bytes_received: u64,
    pub packets_sent: u64,
    pub packets_received: u64,
    pub local_candidate_type: Option<&'static str>,
    pub local_relay_protocol: Option<&'static str>,
}

/// The previous sample, kept so the next one can be a rate.
#[derive(Debug, Clone, PartialEq)]
struct Baseline {
    at_ms: f64,
    bytes_received: u64,
    bytes_sent: u64,
    /// Which pair the byte counts came from.
    ///
    /// The browser SDK watches the candidate-pair id for this, because an ICE
    /// restart nominates a different pair whose counters have their own baseline.
    /// The engine reports no id, so this is the pair's priority — derived from the
    /// candidate pair itself, and different for a different pair.
    pair_priority: u64,
    /// The SSRCs the sample covered, in the order the engine listed them.
    ///
    /// Belt to the priority's braces. A reconnect over the same interfaces could
    /// in principle nominate a pair with the same priority; it cannot also
    /// negotiate the same SSRCs.
    streams: Vec<u32>,
}

/// Derives rates from successive [`TransportStats`] snapshots.
///
/// One per client. `sample` is the whole interface, and it is `&self` because a
/// binding may call `get_stats()` from anywhere: the baseline is behind a mutex,
/// so two concurrent calls cannot interleave a read of it with a write.
#[derive(Debug, Default)]
pub struct StatsSampler {
    previous: Mutex<Option<Baseline>>,
}

impl StatsSampler {
    pub fn new() -> Self {
        Self::default()
    }

    /// Forget the baseline.
    ///
    /// Called when the transport goes away. The pair and SSRC checks would catch
    /// the reconnect anyway — this is the belt to their braces, and it also means
    /// the first sample after a reconnect reports no rate rather than a rate
    /// measured across the gap the disconnect left.
    pub fn reset(&self) {
        *self.previous.lock().unwrap() = None;
    }

    /// Derive a snapshot from `raw`, taken at `now_ms`.
    pub fn sample(&self, raw: &TransportStats, now_ms: f64) -> ConnectionStats {
        let mut stats = ConnectionStats {
            timestamp_ms: now_ms,
            ..ConnectionStats::default()
        };

        // ── Receive side ────────────────────────────────────────────────────
        //
        // The aggregate counters are summed over every stream; the scalar
        // *quality* readings come from the video stream, which is what the browser
        // SDK reads. Both happen in one walk so the per-stream array is built once.
        let mut worst_jitter: Option<f64> = None;
        // Floored per stream, not once over the total: a negative count is one
        // stream's duplicates (RFC 3550 allows it) and says nothing about any
        // other, so summing the signed values first lets one stream's duplicates
        // cancel another's real loss.
        let mut lost_for_ratio: u64 = 0;
        for s in &raw.inbound {
            stats.packets_received += u64::from(s.packets_received);
            stats.packets_lost += i64::from(s.packets_lost);
            lost_for_ratio += u64::try_from(s.packets_lost).unwrap_or(0);
            stats.bytes_received += s.bytes_received;
            // NaN cannot come from a counter, but it can come from a float field
            // an engine left uninitialised, and `max` on a NaN silently keeps
            // whichever side it was handed second. Skip it instead.
            if s.jitter_s.is_finite() {
                worst_jitter = Some(worst_jitter.map_or(s.jitter_s, |w: f64| w.max(s.jitter_s)));
            }
            stats.inbound.push(InboundStats {
                ssrc: s.ssrc,
                kind: stream_kind_str(s.kind),
                packets_received: s.packets_received,
                packets_lost: s.packets_lost,
                bytes_received: s.bytes_received,
                jitter_s: s.jitter_s,
                nack_count: s.nack_count,
                total_decode_time_s: s.total_decode_time_s,
                frames_per_second: s.frames_per_second,
                frames_decoded: s.frames_decoded,
                frames_dropped: s.frames_dropped,
                frame_width: s.frame_width,
                frame_height: s.frame_height,
            });
        }

        // The first video receive stream, as the browser SDK picks it. A model
        // declaring several video outputs has several; the browser reads the first
        // and so does this, rather than inventing an aggregate the two SDKs would
        // then disagree about.
        let video_in = raw.inbound.iter().find(|s| s.kind == StreamKind::Video);

        stats.jitter_s = match video_in {
            Some(s) if s.jitter_s.is_finite() => Some(s.jitter_s),
            // No video stream, or a video stream whose reading is unusable: the
            // worst of what there is. See the module docs for why this answers
            // where the browser does not.
            _ => worst_jitter,
        };

        stats.frames_per_second = video_in
            .map(|s| s.frames_per_second)
            .filter(|fps| fps.is_finite() && *fps > 0.0);

        stats.packet_loss_ratio = match video_in {
            Some(s) => loss_ratio(u64::from(s.packets_received), s.packets_lost.max(0) as u64),
            None => loss_ratio(stats.packets_received, lost_for_ratio),
        };

        // ── Send side ───────────────────────────────────────────────────────
        let mut target_bps = 0.0_f64;
        let mut any_target = false;
        let mut outbound_rtt_s: Option<f64> = None;
        for s in &raw.outbound {
            stats.packets_sent += s.packets_sent;
            stats.bytes_sent += s.bytes_sent;
            if s.target_bitrate_bps.is_finite() && s.target_bitrate_bps > 0.0 {
                target_bps += s.target_bitrate_bps;
                any_target = true;
            }
            // Zero is the engine's "not measured yet", not a zero-latency link.
            if s.round_trip_time_s.is_finite() && s.round_trip_time_s > 0.0 {
                outbound_rtt_s = Some(
                    outbound_rtt_s.map_or(s.round_trip_time_s, |w: f64| w.max(s.round_trip_time_s)),
                );
            }
            stats.outbound.push(OutboundStats {
                ssrc: s.ssrc,
                kind: stream_kind_str(s.kind),
                packets_sent: s.packets_sent,
                retransmitted_packets_sent: s.retransmitted_packets_sent,
                bytes_sent: s.bytes_sent,
                target_bitrate_bps: s.target_bitrate_bps,
                round_trip_time_s: s.round_trip_time_s,
                total_round_trip_time_s: s.total_round_trip_time_s,
                fraction_lost: s.fraction_lost,
                packets_lost: s.packets_lost,
                frames_per_second: s.frames_per_second,
                frames_sent: s.frames_sent,
                frame_width: s.frame_width,
                frame_height: s.frame_height,
            });
        }
        if any_target {
            stats.target_bitrate_bps = Some(target_bps);
        }

        // ── ICE ─────────────────────────────────────────────────────────────
        for p in &raw.candidate_pairs {
            stats.candidate_pairs.push(CandidatePair {
                current_round_trip_time_s: p.current_round_trip_time_s,
                total_round_trip_time_s: p.total_round_trip_time_s,
                priority: p.priority,
                state: p.state.as_str(),
                nominated: p.nominated,
                writable: p.writable,
                available_outgoing_bitrate_bps: p.available_outgoing_bitrate_bps,
                available_incoming_bitrate_bps: p.available_incoming_bitrate_bps,
                bytes_sent: p.bytes_sent,
                bytes_received: p.bytes_received,
                packets_sent: p.packets_sent,
                packets_received: p.packets_received,
                local_candidate_type: p.local_candidate_type.as_str(),
                local_relay_protocol: p.local_relay_protocol.as_str(),
            });
        }

        let selected = select_pair(&raw.candidate_pairs);
        // The nominated pair, or nothing. Every pair carries a candidate type,
        // including the ones ICE is still checking — so reading it off the
        // fallback would answer "host" during setup and then change to "relay"
        // once ICE nominated a relayed pair. A caller polling through connection
        // setup would see a path that was never selected. The browser reports
        // nothing until nomination, and so does this.
        let nominated = selected.filter(|p| p.nominated);
        stats.candidate_type = nominated.and_then(|p| p.local_candidate_type.as_str());
        stats.relay_protocol = nominated.and_then(|p| p.local_relay_protocol.as_str());
        // The state and the RTT are about how far ICE got, so they do read the
        // fallback: "in-progress" is a useful answer where a candidate type is
        // a misleading one.
        stats.candidate_pair_state = selected.map(|p| p.state.as_str());
        stats.rtt_ms = selected
            .filter(|p| {
                p.current_round_trip_time_s.is_finite() && p.current_round_trip_time_s > 0.0
            })
            .map(|p| p.current_round_trip_time_s * 1000.0)
            .or(outbound_rtt_s.map(|s| s * 1000.0));
        stats.available_incoming_bitrate_bps = nominated
            .map(|p| p.available_incoming_bitrate_bps)
            .filter(|b| b.is_finite() && *b > 0.0);
        stats.available_outgoing_bitrate_bps = nominated
            .map(|p| p.available_outgoing_bitrate_bps)
            .filter(|b| b.is_finite() && *b > 0.0);

        // ── Rates, against the previous sample ──────────────────────────────
        //
        // From the nominated pair's counters, which is what the browser measures.
        // Nothing to measure without one: an un-nominated pair carries no traffic,
        // so there is no rate to report rather than a rate of zero.
        let Some(pair) = nominated else {
            return stats;
        };
        let streams: Vec<u32> = raw
            .inbound
            .iter()
            .map(|s| s.ssrc)
            .chain(raw.outbound.iter().map(|s| s.ssrc))
            .collect();
        let current = Baseline {
            at_ms: now_ms,
            bytes_received: pair.bytes_received,
            bytes_sent: pair.bytes_sent,
            pair_priority: pair.priority,
            streams,
        };

        let mut previous = self.previous.lock().unwrap();
        match previous.as_ref() {
            Some(prev)
                if prev.pair_priority == current.pair_priority
                    && prev.streams == current.streams =>
            {
                let elapsed_ms = current.at_ms - prev.at_ms;
                if elapsed_ms >= MIN_RATE_WINDOW_MS {
                    stats.incoming_bitrate_bps = Some(rate_bps(
                        prev.bytes_received,
                        current.bytes_received,
                        elapsed_ms,
                    ));
                    stats.outgoing_bitrate_bps =
                        Some(rate_bps(prev.bytes_sent, current.bytes_sent, elapsed_ms));
                    *previous = Some(current);
                }
                // Too short a window: the baseline stays put, so the next sample
                // measures from it over a window long enough to mean something.
            }
            // First sample, or the connection changed under us. Either way there
            // is nothing to difference against; this becomes the baseline.
            _ => *previous = Some(current),
        }

        stats
    }
}

fn stream_kind_str(kind: StreamKind) -> Option<&'static str> {
    match kind {
        StreamKind::Unknown => None,
        StreamKind::Audio => Some("audio"),
        StreamKind::Video => Some("video"),
    }
}

/// `lost / (received + lost)`, or `None` when nothing has been accounted for.
///
/// `None` rather than zero on an idle stream: no packets seen is not a perfect
/// link.
fn loss_ratio(received: u64, lost: u64) -> Option<f64> {
    let accounted = received + lost;
    (accounted > 0).then(|| lost as f64 / accounted as f64)
}

/// Bits per second between two cumulative byte counts `elapsed_ms` apart.
///
/// `saturating_sub` because a counter that went backwards means the stream was
/// reset under a pair and SSRC set that happened not to change — reporting `0.0`
/// for that window is wrong by one sample, where a negative bitrate is wrong in a
/// way that propagates into whatever averages it.
fn rate_bps(previous_bytes: u64, current_bytes: u64, elapsed_ms: f64) -> f64 {
    let delta_bits = (current_bytes.saturating_sub(previous_bytes) as f64) * 8.0;
    delta_bits / elapsed_ms * 1000.0
}

/// The pair the scalars should come from: the one ICE nominated.
///
/// Falling back to the highest-priority pair in any state means a still-
/// connecting transport reports the state it is actually in rather than `None`,
/// which reads as "no ICE at all". Anything that needs traffic counters re-checks
/// `nominated` — a fallback pair carries nothing.
fn select_pair(pairs: &[CandidatePairStats]) -> Option<&CandidatePairStats> {
    pairs
        .iter()
        .find(|p| p.nominated)
        .or_else(|| pairs.iter().max_by_key(|p| p.priority))
}

#[cfg(test)]
mod tests {
    use super::*;
    // Only the tests build raw snapshots; the module itself reads them through
    // `TransportStats`, so importing these at the top would be an unused import.
    use crate::peer::{
        CandidatePairState, IceCandidateType, InboundRtpStats, OutboundRtpStats, RelayProtocol,
    };

    fn inbound(ssrc: u32, bytes: u64, packets: u32, lost: i32) -> InboundRtpStats {
        InboundRtpStats {
            ssrc,
            packets_received: packets,
            bytes_received: bytes,
            packets_lost: lost,
            ..InboundRtpStats::default()
        }
    }

    /// An inbound stream that says what it is — the 0.15 shape.
    fn inbound_kind(
        ssrc: u32,
        kind: StreamKind,
        packets: u32,
        lost: i32,
        jitter_s: f64,
    ) -> InboundRtpStats {
        InboundRtpStats {
            ssrc,
            kind,
            packets_received: packets,
            packets_lost: lost,
            jitter_s,
            ..InboundRtpStats::default()
        }
    }

    fn outbound(ssrc: u32, bytes: u64) -> OutboundRtpStats {
        OutboundRtpStats {
            ssrc,
            bytes_sent: bytes,
            ..OutboundRtpStats::default()
        }
    }

    /// The nominated pair, carrying `received`/`sent` bytes.
    fn nominated(priority: u64, received: u64, sent: u64) -> CandidatePairStats {
        CandidatePairStats {
            current_round_trip_time_s: 0.021,
            priority,
            state: CandidatePairState::Succeeded,
            nominated: true,
            writable: true,
            bytes_received: received,
            bytes_sent: sent,
            local_candidate_type: IceCandidateType::Host,
            ..CandidatePairStats::default()
        }
    }

    fn pair(rtt_s: f64, priority: u64, state: CandidatePairState) -> CandidatePairStats {
        CandidatePairStats {
            current_round_trip_time_s: rtt_s,
            priority,
            state,
            ..CandidatePairStats::default()
        }
    }

    // ── Rates, on the nominated pair's counters ───────────────────────────────

    #[test]
    fn the_first_sample_reports_counters_but_no_rates() {
        let sampler = StatsSampler::new();
        let raw = TransportStats {
            inbound: vec![inbound(1, 1_000, 10, 0)],
            candidate_pairs: vec![nominated(9, 1_000, 0)],
            ..TransportStats::default()
        };

        let stats = sampler.sample(&raw, 1_000.0);

        assert_eq!(stats.bytes_received, 1_000);
        // Nothing to difference against. A zero here would read as "the link is
        // carrying nothing", which is the opposite of what one sample means.
        assert_eq!(stats.incoming_bitrate_bps, None);
        assert_eq!(stats.outgoing_bitrate_bps, None);
    }

    /// The rate is the *pair's* bytes, not the streams'. That is what the browser
    /// measures, and the two differ: the pair also carried RTCP and the data
    /// channel.
    #[test]
    fn a_rate_is_the_nominated_pairs_bytes_over_the_window() {
        let sampler = StatsSampler::new();
        let at = |received, sent| TransportStats {
            // Deliberately unlike the pair's, so a regression that reads the
            // stream counters instead produces a different number rather than
            // the same one by luck.
            inbound: vec![inbound(1, 1, 10, 0)],
            outbound: vec![outbound(2, 1)],
            candidate_pairs: vec![nominated(9, received, sent)],
        };

        sampler.sample(&at(1_000, 500), 1_000.0);
        let stats = sampler.sample(&at(2_000, 1_000), 2_000.0);

        // 1000 bytes in 1000 ms = 8000 bits/s.
        assert_eq!(stats.incoming_bitrate_bps, Some(8_000.0));
        assert_eq!(stats.outgoing_bitrate_bps, Some(4_000.0));
    }

    #[test]
    fn with_no_nominated_pair_there_is_no_rate_to_report() {
        let sampler = StatsSampler::new();
        // Succeeded but not nominated: it carries nothing, so there is nothing to
        // measure — and a zero would read as an idle connection.
        let raw = TransportStats {
            candidate_pairs: vec![pair(0.02, 9, CandidatePairState::Succeeded)],
            ..TransportStats::default()
        };

        sampler.sample(&raw, 1_000.0);
        let stats = sampler.sample(&raw, 2_000.0);

        assert_eq!(stats.incoming_bitrate_bps, None);
        // The state still reports, so a caller can see how far ICE got.
        assert_eq!(stats.candidate_pair_state, Some("succeeded"));
    }

    #[test]
    fn a_window_below_the_floor_reports_no_rate_and_keeps_the_baseline() {
        let sampler = StatsSampler::new();
        let at = |received| TransportStats {
            candidate_pairs: vec![nominated(9, received, 0)],
            ..TransportStats::default()
        };

        sampler.sample(&at(1_000), 1_000.0);
        // 10 ms later: dividing by that is noise in the units of a bitrate.
        let noisy = sampler.sample(&at(1_100), 1_010.0);
        assert_eq!(noisy.incoming_bitrate_bps, None);

        // The baseline was kept, so this measures the whole 1000 ms rather than
        // starting over from the sample that was too close.
        let usable = sampler.sample(&at(2_000), 2_000.0);
        assert_eq!(usable.incoming_bitrate_bps, Some(8_000.0));
    }

    /// An ICE restart nominates a different pair, whose counters have their own
    /// baseline. The browser guards on the pair's id; the engine reports none, so
    /// this guards on its priority.
    #[test]
    fn a_newly_nominated_pair_is_not_differenced_against_the_old_one() {
        let sampler = StatsSampler::new();
        let before = TransportStats {
            candidate_pairs: vec![nominated(9, 100_000, 0)],
            ..TransportStats::default()
        };
        let after = TransportStats {
            candidate_pairs: vec![nominated(7, 1_000, 0)],
            ..TransportStats::default()
        };

        sampler.sample(&before, 1_000.0);
        let stats = sampler.sample(&after, 2_000.0);

        // Differencing these would report roughly -792 kbps.
        assert_eq!(stats.incoming_bitrate_bps, None);
    }

    /// The same pair could in principle come back with the same priority after a
    /// reconnect; it cannot also negotiate the same SSRCs.
    #[test]
    fn new_ssrcs_after_a_reconnect_are_not_differenced_against_the_old_ones() {
        let sampler = StatsSampler::new();
        let before = TransportStats {
            inbound: vec![inbound(1, 0, 1_000, 0)],
            candidate_pairs: vec![nominated(9, 100_000, 0)],
            ..TransportStats::default()
        };
        let after = TransportStats {
            inbound: vec![inbound(99, 0, 10, 0)],
            candidate_pairs: vec![nominated(9, 1_000, 0)],
            ..TransportStats::default()
        };

        sampler.sample(&before, 1_000.0);
        let stats = sampler.sample(&after, 2_000.0);

        assert_eq!(stats.incoming_bitrate_bps, None);
    }

    #[test]
    fn a_counter_that_went_backwards_reports_zero_rather_than_a_negative_rate() {
        let sampler = StatsSampler::new();
        let at = |received| TransportStats {
            candidate_pairs: vec![nominated(9, received, 0)],
            ..TransportStats::default()
        };

        sampler.sample(&at(5_000), 1_000.0);
        let stats = sampler.sample(&at(1_000), 2_000.0);

        assert_eq!(stats.incoming_bitrate_bps, Some(0.0));
    }

    #[test]
    fn reset_makes_the_next_sample_a_first_sample_again() {
        let sampler = StatsSampler::new();
        let at = |received| TransportStats {
            candidate_pairs: vec![nominated(9, received, 0)],
            ..TransportStats::default()
        };

        sampler.sample(&at(1_000), 1_000.0);
        sampler.reset();
        let stats = sampler.sample(&at(2_000), 2_000.0);

        // Without the reset this would be 8000 bps, measured across a gap in
        // which the transport was not up.
        assert_eq!(stats.incoming_bitrate_bps, None);
    }

    // ── Which pair the scalars come from ──────────────────────────────────────

    /// `nominated` beats priority, and that is the whole point of taking it: the
    /// pair ICE chose is not always the highest-priority succeeded one, and only
    /// it carries traffic.
    #[test]
    fn the_scalars_come_from_the_nominated_pair_not_the_highest_priority_one() {
        let sampler = StatsSampler::new();
        let raw = TransportStats {
            candidate_pairs: vec![
                // Higher priority, succeeded — and not the one ICE picked.
                pair(0.100, 1_000, CandidatePairState::Succeeded),
                CandidatePairStats {
                    current_round_trip_time_s: 0.020,
                    priority: 10,
                    state: CandidatePairState::Succeeded,
                    nominated: true,
                    local_candidate_type: IceCandidateType::Relay,
                    local_relay_protocol: RelayProtocol::Tls,
                    available_incoming_bitrate_bps: 3_000_000.0,
                    available_outgoing_bitrate_bps: 1_500_000.0,
                    ..CandidatePairStats::default()
                },
            ],
            ..TransportStats::default()
        };

        let stats = sampler.sample(&raw, 1_000.0);

        // 20ms, not 100ms.
        assert_eq!(stats.rtt_ms, Some(20.0));
        assert_eq!(stats.candidate_type, Some("relay"));
        assert_eq!(stats.relay_protocol, Some("tls"));
        assert_eq!(stats.available_incoming_bitrate_bps, Some(3_000_000.0));
        assert_eq!(stats.available_outgoing_bitrate_bps, Some(1_500_000.0));
    }

    #[test]
    fn a_direct_path_reports_its_candidate_type_and_no_relay_protocol() {
        let sampler = StatsSampler::new();
        let raw = TransportStats {
            candidate_pairs: vec![nominated(9, 0, 0)],
            ..TransportStats::default()
        };

        let stats = sampler.sample(&raw, 1_000.0);

        assert_eq!(stats.candidate_type, Some("host"));
        // Not relayed is the absence of a protocol, not a protocol.
        assert_eq!(stats.relay_protocol, None);
    }

    /// A candidate type read before nomination is a path that may never be
    /// selected. The state is still worth reporting, because "how far did ICE
    /// get" is a different question from "what is carrying the media".
    #[test]
    fn a_pair_ice_has_not_nominated_reports_no_candidate_type() {
        let sampler = StatsSampler::new();
        let raw = TransportStats {
            candidate_pairs: vec![CandidatePairStats {
                priority: 5,
                state: CandidatePairState::InProgress,
                nominated: false,
                // Present on the pair, and deliberately not reported.
                local_candidate_type: IceCandidateType::Host,
                available_incoming_bitrate_bps: 900_000.0,
                ..CandidatePairStats::default()
            }],
            ..TransportStats::default()
        };

        let stats = sampler.sample(&raw, 1_000.0);

        assert_eq!(stats.candidate_type, None);
        assert_eq!(stats.relay_protocol, None);
        assert_eq!(stats.available_incoming_bitrate_bps, None);
        // But the state does report, so a caller can see ICE is still working.
        assert_eq!(stats.candidate_pair_state, Some("in-progress"));
        // And the pair itself is in the array, type included, for anyone who
        // wants to watch nomination happen.
        assert_eq!(stats.candidate_pairs[0].local_candidate_type, Some("host"));
    }

    #[test]
    fn a_connecting_transport_reports_its_state_and_falls_back_for_rtt() {
        let sampler = StatsSampler::new();
        let raw = TransportStats {
            // Nothing nominated, and no RTT on the pair there is.
            candidate_pairs: vec![pair(0.0, 5, CandidatePairState::InProgress)],
            outbound: vec![OutboundRtpStats {
                ssrc: 2,
                // Now a real measurement: 0.15 revived this field.
                round_trip_time_s: 0.035,
                ..OutboundRtpStats::default()
            }],
            ..TransportStats::default()
        };

        let stats = sampler.sample(&raw, 1_000.0);

        assert_eq!(stats.candidate_pair_state, Some("in-progress"));
        assert_eq!(stats.rtt_ms, Some(35.0));
    }

    #[test]
    fn an_unestimated_available_bitrate_is_absent_rather_than_zero() {
        let sampler = StatsSampler::new();
        // A data-channel-only connection: nominated, carrying bytes, and the
        // congestion controller has no media to estimate against.
        let raw = TransportStats {
            candidate_pairs: vec![nominated(9, 1_000, 1_000)],
            ..TransportStats::default()
        };

        let stats = sampler.sample(&raw, 1_000.0);

        assert_eq!(stats.available_incoming_bitrate_bps, None);
        assert_eq!(stats.available_outgoing_bitrate_bps, None);
    }

    // ── Jitter, loss and fps come from the video stream ───────────────────────

    /// The acceptance criterion of REA-6019: the same number the browser reports
    /// for the same connection. The browser reads the video inbound-rtp; so does
    /// this, now that the engine says which one that is.
    #[test]
    fn jitter_and_loss_come_from_the_video_stream_not_from_the_worst_one() {
        let sampler = StatsSampler::new();
        let raw = TransportStats {
            inbound: vec![
                // Audio, and much worse — the old aggregate reported this one.
                inbound_kind(1, StreamKind::Audio, 900, 100, 0.080),
                inbound_kind(2, StreamKind::Video, 990, 10, 0.004),
            ],
            ..TransportStats::default()
        };

        let stats = sampler.sample(&raw, 1_000.0);

        assert_eq!(stats.jitter_s, Some(0.004));
        let ratio = stats.packet_loss_ratio.expect("a ratio");
        assert!((ratio - 10.0 / 1_000.0).abs() < 1e-12, "got {ratio}");
        // The aggregate counters still cover everything.
        assert_eq!(stats.packets_received, 1_890);
        assert_eq!(stats.packets_lost, 110);
    }

    #[test]
    fn fps_comes_from_the_video_stream() {
        let sampler = StatsSampler::new();
        let raw = TransportStats {
            inbound: vec![
                inbound_kind(1, StreamKind::Audio, 100, 0, 0.0),
                InboundRtpStats {
                    ssrc: 2,
                    kind: StreamKind::Video,
                    frames_per_second: 29.97,
                    frame_width: 1920,
                    frame_height: 1080,
                    frames_decoded: 300,
                    ..InboundRtpStats::default()
                },
            ],
            ..TransportStats::default()
        };

        let stats = sampler.sample(&raw, 1_000.0);

        assert_eq!(stats.frames_per_second, Some(29.97));
        // And the per-stream detail survives, kind included.
        assert_eq!(stats.inbound[0].kind, Some("audio"));
        assert_eq!(stats.inbound[1].kind, Some("video"));
        assert_eq!(stats.inbound[1].frame_width, 1920);
    }

    #[test]
    fn an_audio_only_session_still_reports_jitter_and_loss() {
        let sampler = StatsSampler::new();
        let raw = TransportStats {
            inbound: vec![inbound_kind(1, StreamKind::Audio, 990, 10, 0.012)],
            ..TransportStats::default()
        };

        let stats = sampler.sample(&raw, 1_000.0);

        // The browser reports nothing here, having only looked for video. See the
        // module docs: answering is the deliberate difference.
        assert_eq!(stats.jitter_s, Some(0.012));
        assert!(stats.packet_loss_ratio.is_some());
        // But no fps, because there is no video to have a frame rate.
        assert_eq!(stats.frames_per_second, None);
    }

    /// One stream's duplicates must not cancel another's real loss.
    ///
    /// Reachable on the no-video path, which is the path an audio-only session
    /// takes — the video path reads a single stream and cannot cancel anything.
    #[test]
    fn duplicates_on_one_stream_do_not_hide_loss_on_another() {
        let sampler = StatsSampler::new();
        let raw = TransportStats {
            inbound: vec![
                // Duplicates, so RFC 3550's count went negative.
                inbound(1, 0, 100, -10),
                // Ten genuinely lost.
                inbound(2, 0, 80, 10),
            ],
            ..TransportStats::default()
        };

        let stats = sampler.sample(&raw, 1_000.0);

        // The signed aggregate still offsets — that is what it is for.
        assert_eq!(stats.packets_lost, 0);
        // The ratio must not: 10 lost of (180 received + 10 lost).
        let ratio = stats.packet_loss_ratio.expect("a ratio");
        assert!((ratio - 10.0 / 190.0).abs() < 1e-12, "got {ratio}");
    }

    #[test]
    fn nothing_received_yet_is_no_ratio_rather_than_a_perfect_one() {
        let sampler = StatsSampler::new();

        let stats = sampler.sample(&TransportStats::default(), 1_000.0);

        assert_eq!(stats.packet_loss_ratio, None);
        assert_eq!(stats.jitter_s, None);
        assert_eq!(stats.candidate_pair_state, None);
        assert_eq!(stats.candidate_type, None);
        assert_eq!(stats.rtt_ms, None);
        assert_eq!(stats.frames_per_second, None);
    }

    #[test]
    fn a_nan_jitter_does_not_become_the_answer() {
        let sampler = StatsSampler::new();
        let raw = TransportStats {
            inbound: vec![
                inbound_kind(1, StreamKind::Unknown, 0, 0, f64::NAN),
                inbound_kind(2, StreamKind::Unknown, 0, 0, 0.007),
            ],
            ..TransportStats::default()
        };

        let stats = sampler.sample(&raw, 1_000.0);

        // `f64::max` keeps whichever operand it was handed second when one is
        // NaN, so ordering alone would have decided this.
        assert_eq!(stats.jitter_s, Some(0.007));
    }

    /// A video stream whose jitter is NaN must not poison the answer either — the
    /// video path reads one field and cannot fall back on `max` by itself.
    #[test]
    fn a_nan_jitter_on_the_video_stream_falls_back_to_the_aggregate() {
        let sampler = StatsSampler::new();
        let raw = TransportStats {
            inbound: vec![
                inbound_kind(1, StreamKind::Audio, 0, 0, 0.005),
                inbound_kind(2, StreamKind::Video, 0, 0, f64::NAN),
            ],
            ..TransportStats::default()
        };

        let stats = sampler.sample(&raw, 1_000.0);

        assert_eq!(stats.jitter_s, Some(0.005));
    }

    // ── Send side ─────────────────────────────────────────────────────────────

    #[test]
    fn an_unmeasured_target_bitrate_is_absent_rather_than_zero() {
        let sampler = StatsSampler::new();
        let raw = TransportStats {
            outbound: vec![outbound(2, 1_000)],
            ..TransportStats::default()
        };

        let stats = sampler.sample(&raw, 1_000.0);

        assert_eq!(stats.target_bitrate_bps, None);
    }

    #[test]
    fn targets_sum_across_senders() {
        let sampler = StatsSampler::new();
        let raw = TransportStats {
            outbound: vec![
                OutboundRtpStats {
                    ssrc: 1,
                    target_bitrate_bps: 1_000_000.0,
                    ..OutboundRtpStats::default()
                },
                OutboundRtpStats {
                    ssrc: 2,
                    target_bitrate_bps: 500_000.0,
                    ..OutboundRtpStats::default()
                },
            ],
            ..TransportStats::default()
        };

        let stats = sampler.sample(&raw, 1_000.0);

        assert_eq!(stats.target_bitrate_bps, Some(1_500_000.0));
    }

    /// The send path's own loss and RTT, from the receiver's report — all absent
    /// before 0.15 — and counters past where a u32 wrapped.
    #[test]
    fn the_receivers_report_about_us_reaches_the_outbound_entry() {
        let sampler = StatsSampler::new();
        let raw = TransportStats {
            outbound: vec![OutboundRtpStats {
                ssrc: 2,
                kind: StreamKind::Video,
                packets_sent: 5_000_000_000,
                retransmitted_packets_sent: 5_000_000_001,
                round_trip_time_s: 0.031,
                total_round_trip_time_s: 3.1,
                fraction_lost: 0.02,
                packets_lost: 7,
                ..OutboundRtpStats::default()
            }],
            ..TransportStats::default()
        };

        let stats = sampler.sample(&raw, 1_000.0);

        let s = &stats.outbound[0];
        assert_eq!(s.kind, Some("video"));
        assert_eq!(s.fraction_lost, 0.02);
        assert_eq!(s.packets_lost, 7);
        assert_eq!(s.total_round_trip_time_s, 3.1);
        // Past 2^32, which is where a u32 counter wrapped.
        assert_eq!(s.packets_sent, 5_000_000_000);
        assert_eq!(s.retransmitted_packets_sent, 5_000_000_001);
        assert_eq!(stats.packets_sent, 5_000_000_000);
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    #[test]
    fn an_unknown_scalar_serializes_as_null_rather_than_disappearing() {
        let sampler = StatsSampler::new();

        let stats = sampler.sample(&TransportStats::default(), 42.0);
        let json = serde_json::to_value(&stats).expect("serialize");

        // A binding must be able to tell "not measured" from "not reported by
        // this SDK", and an absent key cannot say which it is.
        for key in [
            "rtt_ms",
            "incoming_bitrate_bps",
            "available_incoming_bitrate_bps",
            "available_outgoing_bitrate_bps",
            "frames_per_second",
            "candidate_type",
            "relay_protocol",
        ] {
            assert!(json.get(key).is_some(), "{key} is missing from the payload");
            assert!(json[key].is_null(), "{key} should be null");
        }
        assert_eq!(json["timestamp_ms"], 42.0);
        assert_eq!(json["packets_received"], 0);
        assert_eq!(json["inbound"], serde_json::json!([]));
    }

    #[test]
    fn the_pair_array_carries_the_new_fields() {
        let sampler = StatsSampler::new();
        let raw = TransportStats {
            candidate_pairs: vec![nominated(9_115_038_255_631_187_199, 4_000, 3_000)],
            ..TransportStats::default()
        };

        let stats = sampler.sample(&raw, 1_000.0);
        let json = serde_json::to_value(&stats).expect("serialize");
        let p = &json["candidate_pairs"][0];

        assert_eq!(p["nominated"], true);
        assert_eq!(p["writable"], true);
        assert_eq!(p["local_candidate_type"], "host");
        assert!(p["local_relay_protocol"].is_null());
        assert_eq!(p["bytes_received"], 4_000);
        assert_eq!(p["packets_received"], 0);
        // A 64-bit priority must survive as an integer, not round through a float.
        assert_eq!(p["priority"], 9_115_038_255_631_187_199u64);
    }
}
