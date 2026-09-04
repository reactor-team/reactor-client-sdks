"""Connection statistics: the objects `Reactor.get_stats()` answers with.

The arithmetic is not here. Counters come from the WebRTC engine and the rates
are derived in the Rust core, so that every FFI-based SDK reports the same
numbers under the same names — see `crates/reactor-core/src/stats.rs`. This
module is the decode: JSON in, typed objects out, and a `DecodeError` rather than
a plausible-looking zero when the payload is not what it should be.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from .errors import DecodeError


@dataclass(frozen=True)
class InboundStream:
    """One receive stream's counters, as the engine reports them."""

    ssrc: int
    #: ``"audio"``, ``"video"``, or `None` when the engine reported no kind.
    #: What makes `ConnectionStats.jitter_s` a question about the video stream
    #: rather than about whichever stream happened to be worst.
    kind: str | None
    packets_received: int
    #: Signed. RFC 3550 allows a negative count when duplicates arrive.
    packets_lost: int
    bytes_received: int
    #: Jitter in seconds.
    jitter_s: float
    nack_count: int
    #: Cumulative decode time in seconds.
    total_decode_time_s: float
    #: Video only; 0.0 until the engine has measured a window's worth.
    frames_per_second: float
    frames_decoded: int
    frames_dropped: int
    #: Decoded frame size; 0 for audio and before the first frame.
    frame_width: int
    frame_height: int


@dataclass(frozen=True)
class OutboundStream:
    """One send stream's counters, as the engine reports them.

    The last four come from the far end's RTCP report about us, so they stay at
    zero until it has sent one — a zero there is "not measured yet", not a
    zero-latency link with no loss.
    """

    ssrc: int
    #: ``"audio"``, ``"video"``, or `None` when the engine reported no kind.
    kind: str | None
    packets_sent: int
    retransmitted_packets_sent: int
    bytes_sent: int
    #: What the encoder is aiming at, in bits per second.
    target_bitrate_bps: float
    #: Video only; 0.0 until the engine has measured a window's worth.
    frames_per_second: float
    frames_sent: int
    #: Encoded frame size; 0 for audio and before the first frame.
    frame_width: int
    frame_height: int
    #: Round-trip time in seconds; 0.0 when not yet measured.
    round_trip_time_s: float
    #: Cumulative round-trip time in seconds.
    total_round_trip_time_s: float
    #: Fraction of this stream the receiver reports as lost, 0.0–1.0.
    fraction_lost: float
    #: Packets the receiver reports as lost. Signed, per RFC 3550.
    packets_lost: int


@dataclass(frozen=True)
class CandidatePair:
    """One ICE candidate pair.

    A connection gathers many — a plain loopback produces eighteen — and exactly
    one is `nominated`. Only that one carries traffic; the rest report zeroes, so
    anything aggregating across pairs averages in candidates that carried
    nothing.
    """

    #: Current RTT in seconds; 0.0 when not yet measured.
    current_round_trip_time_s: float
    #: Cumulative RTT in seconds across every check on this pair.
    total_round_trip_time_s: float
    priority: int
    #: ``"succeeded"``, ``"waiting"``, ``"in-progress"``, ``"failed"`` or
    #: ``"cancelled"``.
    state: str
    #: Whether ICE selected this pair. Read this rather than inferring the
    #: selected pair from `state` and `priority`.
    nominated: bool
    writable: bool
    #: The congestion controller's estimates, in bits per second; 0.0 when it
    #: has none yet.
    available_outgoing_bitrate_bps: float
    available_incoming_bitrate_bps: float
    #: Everything this pair carried — RTCP and data channel included, so wider
    #: than the per-stream RTP counters.
    bytes_sent: int
    bytes_received: int
    packets_sent: int
    packets_received: int
    #: ``"host"``, ``"srflx"``, ``"prflx"``, ``"relay"``, or `None` before ICE
    #: selected anything. ``"relay"`` means this pair goes through TURN.
    local_candidate_type: str | None
    #: ``"udp"``, ``"tcp"``, ``"tls"``, or `None` when not relayed.
    local_relay_protocol: str | None


@dataclass(frozen=True)
class ConnectionStats:
    """A statistics snapshot for the live connection.

    The scalars are the summary — what a health check or an overlay reads. The
    three lists at the bottom are the engine's own per-stream report, for when the
    summary is not enough.

    A scalar is `None` when the engine has not measured it yet, which is a
    different thing from zero: no RTT yet is not a zero-latency link, and no
    incoming bitrate yet is not an idle one.
    """

    #: Round-trip time in milliseconds, from the candidate pair ICE nominated.
    #: Falls back to the largest any send stream measured. `None` until one of
    #: them has a reading.
    rtt_ms: float | None
    #: Jitter on the received video stream, in seconds — the same stream the
    #: browser SDK reads. With no video stream, the worst across the receive
    #: streams there are. Per-stream values are in `inbound`.
    jitter_s: float | None
    #: Fraction of inbound packets lost since the connection came up, 0.0–1.0.
    #: Cumulative, not per-window, and from the video stream for the same reason
    #: `jitter_s` is.
    packet_loss_ratio: float | None
    #: Receive rate over the window since the previous `get_stats()`, in bits per
    #: second.
    #:
    #: Measured on the nominated candidate pair, so it covers everything that
    #: pair carried — RTP, RTCP and the data channel — which is what the browser
    #: SDK's `incomingBitrate` measures.
    #:
    #: `None` on the first call after connecting, on a call less than 200 ms
    #: after the last one, before ICE has nominated a pair, and on the first
    #: call after a reconnect — a reconnect nominates a different pair whose
    #: counters restart from zero.
    incoming_bitrate_bps: float | None
    #: Send rate over the same window, on the same terms.
    outgoing_bitrate_bps: float | None
    #: The congestion controller's own estimate of what the path can carry, in
    #: bits per second — not what is flowing. `None` until it has one, which
    #: needs media on the wire: a data-channel-only connection never reports it.
    available_incoming_bitrate_bps: float | None
    #: As above, for the send direction.
    available_outgoing_bitrate_bps: float | None
    #: What the encoders are aiming at, summed across send streams, in bits per
    #: second. The target, not the achieved rate — compare
    #: `outgoing_bitrate_bps`, which is measured.
    target_bitrate_bps: float | None
    #: Frames per second on the received video stream. `None` with no video
    #: stream, and until the engine has measured a window's worth.
    frames_per_second: float | None
    #: Transport type of the nominated pair's local candidate: ``"host"``,
    #: ``"srflx"``, ``"prflx"`` or ``"relay"``. ``"relay"`` means the media is
    #: going through a TURN server, which is the first thing worth knowing when
    #: latency is bad. `None` before ICE has selected anything.
    candidate_type: str | None
    #: ``"udp"``, ``"tcp"`` or ``"tls"`` when `candidate_type` is ``"relay"``;
    #: `None` when the path is not relayed. Not a field the browser SDK reports.
    relay_protocol: str | None
    #: State of the pair `rtt_ms` was read from. `None` when the engine reported
    #: no candidate pairs at all.
    candidate_pair_state: str | None

    #: Cumulative counters, summed across streams. Present on every call, even
    #: when the derived rates above are not, so a caller can do its own
    #: arithmetic over whatever window it likes.
    packets_received: int
    #: Signed, and the sign is meaningful — see `InboundStream.packets_lost`.
    #: The plain sum across receive streams, so one stream's duplicates do
    #: offset another's losses here; `packet_loss_ratio` is the field to read
    #: for "how bad is it".
    packets_lost: int
    packets_sent: int
    bytes_received: int
    bytes_sent: int

    #: When the sample was taken, in Unix milliseconds.
    timestamp_ms: float

    #: The engine's per-stream report, unaggregated.
    inbound: list[InboundStream] = field(default_factory=list)
    outbound: list[OutboundStream] = field(default_factory=list)
    candidate_pairs: list[CandidatePair] = field(default_factory=list)


def _optional_float(payload: dict[str, Any], key: str) -> float | None:
    value = payload.get(key)
    return None if value is None else float(value)


def stats_from_payload(payload: Any) -> ConnectionStats:
    """Decode what `reactor_get_stats` reported.

    Raises `DecodeError` for anything that is not the documented object. A
    snapshot is read to decide whether a connection is healthy, and a zero
    substituted for a field that failed to parse says "healthy" — which is the one
    answer worth never guessing at.
    """
    if not isinstance(payload, dict):
        raise DecodeError(
            f"connection statistics arrived as {type(payload).__name__}, "
            f"not an object: {payload!r}",
            operation="get_stats",
        )
    try:
        return ConnectionStats(
            rtt_ms=_optional_float(payload, "rtt_ms"),
            jitter_s=_optional_float(payload, "jitter_s"),
            packet_loss_ratio=_optional_float(payload, "packet_loss_ratio"),
            incoming_bitrate_bps=_optional_float(payload, "incoming_bitrate_bps"),
            outgoing_bitrate_bps=_optional_float(payload, "outgoing_bitrate_bps"),
            available_incoming_bitrate_bps=_optional_float(
                payload, "available_incoming_bitrate_bps"
            ),
            available_outgoing_bitrate_bps=_optional_float(
                payload, "available_outgoing_bitrate_bps"
            ),
            target_bitrate_bps=_optional_float(payload, "target_bitrate_bps"),
            frames_per_second=_optional_float(payload, "frames_per_second"),
            candidate_type=payload.get("candidate_type"),
            relay_protocol=payload.get("relay_protocol"),
            candidate_pair_state=payload.get("candidate_pair_state"),
            packets_received=int(payload["packets_received"]),
            packets_lost=int(payload["packets_lost"]),
            packets_sent=int(payload["packets_sent"]),
            bytes_received=int(payload["bytes_received"]),
            bytes_sent=int(payload["bytes_sent"]),
            timestamp_ms=float(payload["timestamp_ms"]),
            inbound=[
                InboundStream(
                    ssrc=int(s["ssrc"]),
                    kind=s.get("kind"),
                    packets_received=int(s["packets_received"]),
                    packets_lost=int(s["packets_lost"]),
                    bytes_received=int(s["bytes_received"]),
                    jitter_s=float(s["jitter_s"]),
                    nack_count=int(s["nack_count"]),
                    total_decode_time_s=float(s["total_decode_time_s"]),
                    frames_per_second=float(s["frames_per_second"]),
                    frames_decoded=int(s["frames_decoded"]),
                    frames_dropped=int(s["frames_dropped"]),
                    frame_width=int(s["frame_width"]),
                    frame_height=int(s["frame_height"]),
                )
                for s in payload.get("inbound", [])
            ],
            outbound=[
                OutboundStream(
                    ssrc=int(s["ssrc"]),
                    kind=s.get("kind"),
                    packets_sent=int(s["packets_sent"]),
                    retransmitted_packets_sent=int(s["retransmitted_packets_sent"]),
                    bytes_sent=int(s["bytes_sent"]),
                    target_bitrate_bps=float(s["target_bitrate_bps"]),
                    frames_per_second=float(s["frames_per_second"]),
                    frames_sent=int(s["frames_sent"]),
                    frame_width=int(s["frame_width"]),
                    frame_height=int(s["frame_height"]),
                    round_trip_time_s=float(s["round_trip_time_s"]),
                    total_round_trip_time_s=float(s["total_round_trip_time_s"]),
                    fraction_lost=float(s["fraction_lost"]),
                    packets_lost=int(s["packets_lost"]),
                )
                for s in payload.get("outbound", [])
            ],
            candidate_pairs=[
                CandidatePair(
                    current_round_trip_time_s=float(p["current_round_trip_time_s"]),
                    total_round_trip_time_s=float(p["total_round_trip_time_s"]),
                    priority=int(p["priority"]),
                    state=str(p["state"]),
                    nominated=bool(p["nominated"]),
                    writable=bool(p["writable"]),
                    available_outgoing_bitrate_bps=float(p["available_outgoing_bitrate_bps"]),
                    available_incoming_bitrate_bps=float(p["available_incoming_bitrate_bps"]),
                    bytes_sent=int(p["bytes_sent"]),
                    bytes_received=int(p["bytes_received"]),
                    packets_sent=int(p["packets_sent"]),
                    packets_received=int(p["packets_received"]),
                    local_candidate_type=p.get("local_candidate_type"),
                    local_relay_protocol=p.get("local_relay_protocol"),
                )
                for p in payload.get("candidate_pairs", [])
            ],
        )
    except (KeyError, TypeError, ValueError) as error:
        raise DecodeError(
            f"connection statistics could not be read ({error!r}): {payload!r}",
            operation="get_stats",
        ) from error
