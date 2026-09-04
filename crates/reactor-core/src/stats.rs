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
//! # What this cannot report, and why
//!
//! The browser SDK's `ConnectionStats` carries four fields absent here:
//! `candidateType`, `availableIncomingBitrate`, `availableOutgoingBitrate` and
//! `framesPerSecond`. All four are missing at the engine, not dropped here —
//! `reactor-webrtc`'s C ABI carries no local-candidate reference, no
//! available-bitrate estimate and no frame counters, and no arithmetic recovers a
//! field that never arrived. See [`crate::peer::CandidatePairStats`].
//!
//! What is here and not there: the per-stream arrays, NACK counts, retransmitted
//! packets and cumulative decode time, which the browser's own report has but the
//! browser SDK's extractor does not surface.

use std::sync::Mutex;

use serde::Serialize;

use crate::peer::{CandidatePairState, CandidatePairStats, TransportStats};

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
    /// "Selected" is inferred: the engine reports no `nominated` flag, so this is
    /// the highest-priority pair in state `succeeded` — which is what nomination
    /// picks in practice. With no succeeded pair reporting one, this falls back to
    /// the largest RTT any send stream measured, and is `None` when neither has a
    /// reading yet.
    pub rtt_ms: Option<f64>,

    /// Worst jitter across the receive streams, in seconds.
    ///
    /// The maximum, not a particular stream's: the engine does not say which
    /// stream is video, so there is no "the video stream" to single out the way
    /// the browser SDK does. Read `inbound` for the per-stream values.
    pub jitter_s: Option<f64>,

    /// Fraction of inbound packets lost since the connection came up, `0.0`–`1.0`.
    ///
    /// Cumulative rather than per-window, matching the browser SDK. Summed across
    /// receive streams. `None` until at least one packet has been accounted for.
    pub packet_loss_ratio: Option<f64>,

    /// Receive rate over the window since the previous sample, in bits per second.
    ///
    /// `None` on the first sample, when the window was shorter than
    /// [`MIN_RATE_WINDOW_MS`], or when the stream set changed under it — a
    /// reconnect brings up new SSRCs whose counters restart from zero, and
    /// differencing across that would report a large negative rate.
    ///
    /// This counts RTP payload only, where the browser SDK's `incomingBitrate`
    /// counts everything the selected candidate pair carried — RTCP and data
    /// channel included. The media rate is the more useful of the two and the only
    /// one available here; expect it to read slightly lower than the browser's.
    pub incoming_bitrate_bps: Option<f64>,

    /// Send rate over the same window, in bits per second. As
    /// [`ConnectionStats::incoming_bitrate_bps`].
    pub outgoing_bitrate_bps: Option<f64>,

    /// What the encoders are currently aiming at, summed across send streams, in
    /// bits per second.
    ///
    /// The target, not the achieved rate — compare with
    /// [`ConnectionStats::outgoing_bitrate_bps`], which is measured. `None` when
    /// nothing is being sent.
    pub target_bitrate_bps: Option<f64>,

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
    /// duplicates arrive. [`ConnectionStats::packet_loss_ratio`] floors it at zero
    /// instead, since a negative fraction of packets lost is not a fraction.
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
    pub packets_received: u32,
    pub packets_lost: i32,
    pub bytes_received: u64,
    pub jitter_s: f64,
    pub nack_count: u32,
    pub total_decode_time_s: f64,
}

/// One send stream, as serialized. Mirrors [`crate::peer::OutboundRtpStats`].
#[derive(Debug, Clone, Default, PartialEq, Serialize)]
pub struct OutboundStats {
    pub ssrc: u32,
    pub packets_sent: u32,
    pub retransmitted_packets_sent: u32,
    pub bytes_sent: u64,
    pub target_bitrate_bps: f64,
    pub round_trip_time_s: f64,
}

/// One ICE candidate pair, as serialized. Mirrors [`CandidatePairStats`].
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct CandidatePair {
    pub current_round_trip_time_s: f64,
    pub priority: u64,
    pub state: &'static str,
}

/// The previous sample, kept so the next one can be a rate.
#[derive(Debug, Clone, PartialEq)]
struct Baseline {
    at_ms: f64,
    bytes_received: u64,
    bytes_sent: u64,
    /// The SSRCs the sample covered, in the order the engine listed them.
    ///
    /// The guard against differencing across a reconnect. The browser SDK watches
    /// the candidate-pair id for the same reason — an ICE restart nominates a
    /// different pair whose counters have their own baseline — and the SSRC set is
    /// this layer's equivalent, since a fresh peer connection negotiates fresh
    /// ones.
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
    /// Called when the transport goes away. The SSRC check would catch the
    /// reconnect anyway — this is the belt to its braces, and it also means the
    /// first sample after a reconnect reports no rate rather than a rate measured
    /// across the gap the disconnect left.
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
        let mut worst_jitter: Option<f64> = None;
        for s in &raw.inbound {
            stats.packets_received += u64::from(s.packets_received);
            stats.packets_lost += i64::from(s.packets_lost);
            stats.bytes_received += s.bytes_received;
            // NaN cannot come from a counter, but it can come from a float field
            // an engine left uninitialised, and `max` on a NaN silently keeps
            // whichever side it was handed second. Skip it instead.
            if s.jitter_s.is_finite() {
                worst_jitter = Some(worst_jitter.map_or(s.jitter_s, |w: f64| w.max(s.jitter_s)));
            }
            stats.inbound.push(InboundStats {
                ssrc: s.ssrc,
                packets_received: s.packets_received,
                packets_lost: s.packets_lost,
                bytes_received: s.bytes_received,
                jitter_s: s.jitter_s,
                nack_count: s.nack_count,
                total_decode_time_s: s.total_decode_time_s,
            });
        }
        stats.jitter_s = worst_jitter;

        // Negative loss (duplicates) floors at zero: the ratio is a fraction of
        // packets accounted for, and a negative fraction is not one. The signed
        // count stays available in `packets_lost` for anyone who wants it.
        let lost = stats.packets_lost.max(0) as u64;
        let accounted = stats.packets_received + lost;
        if accounted > 0 {
            stats.packet_loss_ratio = Some(lost as f64 / accounted as f64);
        }

        // ── Send side ───────────────────────────────────────────────────────
        let mut target_bps = 0.0_f64;
        let mut any_target = false;
        let mut outbound_rtt_s: Option<f64> = None;
        for s in &raw.outbound {
            stats.packets_sent += u64::from(s.packets_sent);
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
                packets_sent: s.packets_sent,
                retransmitted_packets_sent: s.retransmitted_packets_sent,
                bytes_sent: s.bytes_sent,
                target_bitrate_bps: s.target_bitrate_bps,
                round_trip_time_s: s.round_trip_time_s,
            });
        }
        if any_target {
            stats.target_bitrate_bps = Some(target_bps);
        }

        // ── ICE ─────────────────────────────────────────────────────────────
        for p in &raw.candidate_pairs {
            stats.candidate_pairs.push(CandidatePair {
                current_round_trip_time_s: p.current_round_trip_time_s,
                priority: p.priority,
                state: p.state.as_str(),
            });
        }
        let selected = select_pair(&raw.candidate_pairs);
        stats.candidate_pair_state = selected.map(|p| p.state.as_str());
        stats.rtt_ms = selected
            .filter(|p| {
                p.current_round_trip_time_s.is_finite() && p.current_round_trip_time_s > 0.0
            })
            .map(|p| p.current_round_trip_time_s * 1000.0)
            .or(outbound_rtt_s.map(|s| s * 1000.0));

        // ── Rates, against the previous sample ──────────────────────────────
        let streams: Vec<u32> = raw
            .inbound
            .iter()
            .map(|s| s.ssrc)
            .chain(raw.outbound.iter().map(|s| s.ssrc))
            .collect();
        let current = Baseline {
            at_ms: now_ms,
            bytes_received: stats.bytes_received,
            bytes_sent: stats.bytes_sent,
            streams,
        };

        let mut previous = self.previous.lock().unwrap();
        match previous.as_ref() {
            Some(prev) if prev.streams == current.streams => {
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
            // First sample, or the streams changed under us. Either way there is
            // nothing to difference against; this becomes the baseline.
            _ => *previous = Some(current),
        }

        stats
    }
}

/// Bits per second between two cumulative byte counts `elapsed_ms` apart.
///
/// `saturating_sub` because a counter that went backwards means the stream was
/// reset under a set of SSRCs that happened not to change — reporting `0.0` for
/// that window is wrong by one sample, where a negative bitrate is wrong in a way
/// that propagates into whatever averages it.
fn rate_bps(previous_bytes: u64, current_bytes: u64, elapsed_ms: f64) -> f64 {
    let delta_bits = (current_bytes.saturating_sub(previous_bytes) as f64) * 8.0;
    delta_bits / elapsed_ms * 1000.0
}

/// The pair `rtt_ms` should come from.
///
/// The engine reports no `nominated` flag, so this stands in for it: the
/// highest-priority `succeeded` pair, which is the one nomination arrives at.
/// Falling back to the highest-priority pair in any state means an
/// still-connecting transport reports the state it is actually in rather than
/// `None`, which reads as "no ICE at all".
fn select_pair(pairs: &[CandidatePairStats]) -> Option<&CandidatePairStats> {
    pairs
        .iter()
        .filter(|p| p.state == CandidatePairState::Succeeded)
        .max_by_key(|p| p.priority)
        .or_else(|| pairs.iter().max_by_key(|p| p.priority))
}

#[cfg(test)]
mod tests {
    use super::*;
    // Only the tests build raw snapshots; the module itself reads them through
    // `TransportStats`, so importing these at the top would be an unused import.
    use crate::peer::{InboundRtpStats, OutboundRtpStats};

    fn inbound(ssrc: u32, bytes: u64, packets: u32, lost: i32) -> InboundRtpStats {
        InboundRtpStats {
            ssrc,
            packets_received: packets,
            bytes_received: bytes,
            packets_lost: lost,
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

    fn pair(rtt_s: f64, priority: u64, state: CandidatePairState) -> CandidatePairStats {
        CandidatePairStats {
            current_round_trip_time_s: rtt_s,
            priority,
            state,
        }
    }

    #[test]
    fn the_first_sample_reports_counters_but_no_rates() {
        let sampler = StatsSampler::new();
        let raw = TransportStats {
            inbound: vec![inbound(1, 1_000, 10, 0)],
            ..TransportStats::default()
        };

        let stats = sampler.sample(&raw, 1_000.0);

        assert_eq!(stats.bytes_received, 1_000);
        // Nothing to difference against. A zero here would read as "the link is
        // carrying nothing", which is the opposite of what one sample means.
        assert_eq!(stats.incoming_bitrate_bps, None);
        assert_eq!(stats.outgoing_bitrate_bps, None);
    }

    #[test]
    fn a_rate_is_bytes_over_the_window_in_bits_per_second() {
        let sampler = StatsSampler::new();
        let first = TransportStats {
            inbound: vec![inbound(1, 1_000, 10, 0)],
            outbound: vec![outbound(2, 500)],
            ..TransportStats::default()
        };
        let second = TransportStats {
            inbound: vec![inbound(1, 2_000, 20, 0)],
            outbound: vec![outbound(2, 1_000)],
            ..TransportStats::default()
        };

        sampler.sample(&first, 1_000.0);
        let stats = sampler.sample(&second, 2_000.0);

        // 1000 bytes in 1000 ms = 8000 bits/s.
        assert_eq!(stats.incoming_bitrate_bps, Some(8_000.0));
        assert_eq!(stats.outgoing_bitrate_bps, Some(4_000.0));
    }

    #[test]
    fn a_window_below_the_floor_reports_no_rate_and_keeps_the_baseline() {
        let sampler = StatsSampler::new();
        let at = |bytes| TransportStats {
            inbound: vec![inbound(1, bytes, 10, 0)],
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

    #[test]
    fn new_ssrcs_after_a_reconnect_are_not_differenced_against_the_old_ones() {
        let sampler = StatsSampler::new();
        let before = TransportStats {
            inbound: vec![inbound(1, 100_000, 1_000, 0)],
            ..TransportStats::default()
        };
        // A reconnect: fresh peer connection, fresh SSRC, counters from zero.
        let after = TransportStats {
            inbound: vec![inbound(99, 1_000, 10, 0)],
            ..TransportStats::default()
        };

        sampler.sample(&before, 1_000.0);
        let stats = sampler.sample(&after, 2_000.0);

        // Differencing these would report roughly -792 kbps.
        assert_eq!(stats.incoming_bitrate_bps, None);
        assert_eq!(stats.bytes_received, 1_000);
    }

    #[test]
    fn a_counter_that_went_backwards_reports_zero_rather_than_a_negative_rate() {
        let sampler = StatsSampler::new();
        let at = |bytes| TransportStats {
            inbound: vec![inbound(1, bytes, 10, 0)],
            ..TransportStats::default()
        };

        sampler.sample(&at(5_000), 1_000.0);
        let stats = sampler.sample(&at(1_000), 2_000.0);

        assert_eq!(stats.incoming_bitrate_bps, Some(0.0));
    }

    #[test]
    fn reset_makes_the_next_sample_a_first_sample_again() {
        let sampler = StatsSampler::new();
        let at = |bytes| TransportStats {
            inbound: vec![inbound(1, bytes, 10, 0)],
            ..TransportStats::default()
        };

        sampler.sample(&at(1_000), 1_000.0);
        sampler.reset();
        let stats = sampler.sample(&at(2_000), 2_000.0);

        // Without the reset this would be 8000 bps, measured across a gap in
        // which the transport was not up.
        assert_eq!(stats.incoming_bitrate_bps, None);
    }

    #[test]
    fn loss_is_a_fraction_of_the_packets_accounted_for() {
        let sampler = StatsSampler::new();
        let raw = TransportStats {
            inbound: vec![inbound(1, 0, 99, 1)],
            ..TransportStats::default()
        };

        let stats = sampler.sample(&raw, 1_000.0);

        assert_eq!(stats.packet_loss_ratio, Some(0.01));
    }

    #[test]
    fn duplicates_make_the_signed_count_negative_and_the_ratio_zero() {
        let sampler = StatsSampler::new();
        let raw = TransportStats {
            inbound: vec![inbound(1, 0, 100, -3)],
            ..TransportStats::default()
        };

        let stats = sampler.sample(&raw, 1_000.0);

        // The sign survives where it is meaningful, and is floored where it is not.
        assert_eq!(stats.packets_lost, -3);
        assert_eq!(stats.packet_loss_ratio, Some(0.0));
    }

    #[test]
    fn nothing_received_yet_is_no_ratio_rather_than_a_perfect_one() {
        let sampler = StatsSampler::new();

        let stats = sampler.sample(&TransportStats::default(), 1_000.0);

        assert_eq!(stats.packet_loss_ratio, None);
        assert_eq!(stats.jitter_s, None);
        assert_eq!(stats.candidate_pair_state, None);
        assert_eq!(stats.rtt_ms, None);
    }

    #[test]
    fn rtt_comes_from_the_highest_priority_succeeded_pair() {
        let sampler = StatsSampler::new();
        let raw = TransportStats {
            candidate_pairs: vec![
                pair(0.100, 10, CandidatePairState::Succeeded),
                pair(0.020, 99, CandidatePairState::Succeeded),
                // A better priority, but not the pair carrying anything.
                pair(0.001, 1_000, CandidatePairState::Failed),
            ],
            ..TransportStats::default()
        };

        let stats = sampler.sample(&raw, 1_000.0);

        assert_eq!(stats.rtt_ms, Some(20.0));
        assert_eq!(stats.candidate_pair_state, Some("succeeded"));
    }

    #[test]
    fn a_connecting_transport_reports_its_state_and_falls_back_for_rtt() {
        let sampler = StatsSampler::new();
        let raw = TransportStats {
            // No succeeded pair, and no RTT measured on the one there is.
            candidate_pairs: vec![pair(0.0, 5, CandidatePairState::InProgress)],
            outbound: vec![OutboundRtpStats {
                ssrc: 2,
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
    fn jitter_is_the_worst_stream_and_the_per_stream_values_survive() {
        let sampler = StatsSampler::new();
        let raw = TransportStats {
            inbound: vec![
                InboundRtpStats {
                    ssrc: 1,
                    jitter_s: 0.004,
                    ..InboundRtpStats::default()
                },
                InboundRtpStats {
                    ssrc: 2,
                    jitter_s: 0.031,
                    ..InboundRtpStats::default()
                },
            ],
            ..TransportStats::default()
        };

        let stats = sampler.sample(&raw, 1_000.0);

        assert_eq!(stats.jitter_s, Some(0.031));
        assert_eq!(stats.inbound.len(), 2);
        assert_eq!(stats.inbound[1].jitter_s, 0.031);
    }

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

    #[test]
    fn an_unknown_scalar_serializes_as_null_rather_than_disappearing() {
        let sampler = StatsSampler::new();

        let stats = sampler.sample(&TransportStats::default(), 42.0);
        let json = serde_json::to_value(&stats).expect("serialize");

        // A binding must be able to tell "not measured" from "not reported by
        // this SDK", and an absent key cannot say which it is.
        assert!(json.get("rtt_ms").is_some());
        assert!(json["rtt_ms"].is_null());
        assert!(json["incoming_bitrate_bps"].is_null());
        assert_eq!(json["timestamp_ms"], 42.0);
        assert_eq!(json["packets_received"], 0);
        assert_eq!(json["inbound"], serde_json::json!([]));
    }

    #[test]
    fn a_nan_jitter_does_not_become_the_answer() {
        let sampler = StatsSampler::new();
        let raw = TransportStats {
            inbound: vec![
                InboundRtpStats {
                    ssrc: 1,
                    jitter_s: f64::NAN,
                    ..InboundRtpStats::default()
                },
                InboundRtpStats {
                    ssrc: 2,
                    jitter_s: 0.007,
                    ..InboundRtpStats::default()
                },
            ],
            ..TransportStats::default()
        };

        let stats = sampler.sample(&raw, 1_000.0);

        // `f64::max` keeps whichever operand it was handed second when one is
        // NaN, so ordering alone would have decided this.
        assert_eq!(stats.jitter_s, Some(0.007));
    }
}
