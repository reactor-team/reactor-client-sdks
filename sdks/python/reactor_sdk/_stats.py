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
    """One receive stream's counters, as the engine reports them.

    There is no `kind` here because the engine does not report one: a video
    stream and an audio one are told apart by their `ssrc` and by nothing else.
    """

    ssrc: int
    packets_received: int
    #: Signed. RFC 3550 allows a negative count when duplicates arrive.
    packets_lost: int
    bytes_received: int
    #: Jitter in seconds.
    jitter_s: float
    nack_count: int
    #: Cumulative decode time in seconds.
    total_decode_time_s: float


@dataclass(frozen=True)
class OutboundStream:
    """One send stream's counters, as the engine reports them."""

    ssrc: int
    packets_sent: int
    retransmitted_packets_sent: int
    bytes_sent: int
    #: What the encoder is aiming at, in bits per second.
    target_bitrate_bps: float
    #: Round-trip time in seconds; 0.0 when not yet measured.
    round_trip_time_s: float


@dataclass(frozen=True)
class CandidatePair:
    """One ICE candidate pair.

    Thin, because the engine's report is: there is no pair id, no `nominated`
    flag and no reference to the local candidate, so a pair cannot say whether it
    was host, STUN-reflexive or relayed.
    """

    #: Current RTT in seconds; 0.0 when not yet measured.
    current_round_trip_time_s: float
    priority: int
    #: ``"succeeded"``, ``"waiting"``, ``"in-progress"``, ``"failed"`` or
    #: ``"cancelled"``.
    state: str


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

    #: Round-trip time in milliseconds, from the selected ICE candidate pair.
    #: `None` until something has measured one.
    rtt_ms: float | None
    #: The worst jitter across the receive streams, in seconds. The maximum
    #: rather than a particular stream's: the engine does not say which stream is
    #: video, so there is no "the video stream" to single out. Per-stream values
    #: are in `inbound`.
    jitter_s: float | None
    #: Fraction of inbound packets lost since the connection came up, 0.0–1.0.
    #: Cumulative, not per-window.
    packet_loss_ratio: float | None
    #: Receive rate over the window since the previous `get_stats()`, in bits per
    #: second. `None` on the first call after connecting, on a call less than
    #: 200 ms after the last one, and on the first call after a reconnect —
    #: deriving a rate takes two samples of the same streams.
    #:
    #: RTP payload only, where the browser SDK's `incomingBitrate` counts
    #: everything the candidate pair carried, RTCP and data channel included. So
    #: expect this to read slightly lower than the browser's for the same traffic.
    incoming_bitrate_bps: float | None
    #: Send rate over the same window, on the same terms.
    outgoing_bitrate_bps: float | None
    #: What the encoders are aiming at, summed across send streams, in bits per
    #: second. The target, not the achieved rate — compare
    #: `outgoing_bitrate_bps`, which is measured.
    target_bitrate_bps: float | None
    #: State of the pair `rtt_ms` was read from. `None` when the engine reported
    #: no candidate pairs at all.
    candidate_pair_state: str | None

    #: Cumulative counters, summed across streams. Present on every call, even
    #: when the derived rates above are not, so a caller can do its own
    #: arithmetic over whatever window it likes.
    packets_received: int
    #: Signed, and the sign is meaningful — see `InboundStream.packets_lost`.
    #: `packet_loss_ratio` floors it at zero instead.
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
            target_bitrate_bps=_optional_float(payload, "target_bitrate_bps"),
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
                    packets_received=int(s["packets_received"]),
                    packets_lost=int(s["packets_lost"]),
                    bytes_received=int(s["bytes_received"]),
                    jitter_s=float(s["jitter_s"]),
                    nack_count=int(s["nack_count"]),
                    total_decode_time_s=float(s["total_decode_time_s"]),
                )
                for s in payload.get("inbound", [])
            ],
            outbound=[
                OutboundStream(
                    ssrc=int(s["ssrc"]),
                    packets_sent=int(s["packets_sent"]),
                    retransmitted_packets_sent=int(s["retransmitted_packets_sent"]),
                    bytes_sent=int(s["bytes_sent"]),
                    target_bitrate_bps=float(s["target_bitrate_bps"]),
                    round_trip_time_s=float(s["round_trip_time_s"]),
                )
                for s in payload.get("outbound", [])
            ],
            candidate_pairs=[
                CandidatePair(
                    current_round_trip_time_s=float(p["current_round_trip_time_s"]),
                    priority=int(p["priority"]),
                    state=str(p["state"]),
                )
                for p in payload.get("candidate_pairs", [])
            ],
        )
    except (KeyError, TypeError, ValueError) as error:
        raise DecodeError(
            f"connection statistics could not be read ({error!r}): {payload!r}",
            operation="get_stats",
        ) from error
