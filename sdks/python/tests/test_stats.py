"""Tests for `get_stats()` and the decode behind it.

The arithmetic these numbers come out of lives in `reactor-core` and is tested
there; what is testable here is the binding's half — that the FFI is actually
called, that the JSON becomes the documented objects, and that a payload which is
not what it should be raises rather than reading as a healthy connection.

That last one is the reason this file exists. A snapshot is read to decide whether
something is wrong, so every field that fails to parse into a zero is a
`get_stats()` that says "fine".
"""

from __future__ import annotations

import json
from typing import Any
from unittest import mock

import pytest

from reactor_sdk import DecodeError, Reactor
from reactor_sdk._stats import stats_from_payload

# What the core serializes for a healthy connection, with the two derived
# bitrates present — i.e. not the first sample.
FULL_PAYLOAD: dict[str, Any] = {
    "rtt_ms": 21.5,
    "jitter_s": 0.004,
    "packet_loss_ratio": 0.002,
    "incoming_bitrate_bps": 1_843_200.0,
    "outgoing_bitrate_bps": 96_000.0,
    "target_bitrate_bps": 2_500_000.0,
    "candidate_pair_state": "succeeded",
    "packets_received": 4_021,
    "packets_lost": 8,
    "packets_sent": 512,
    "bytes_received": 5_123_456,
    "bytes_sent": 65_536,
    "timestamp_ms": 1_757_000_000_000.0,
    "inbound": [
        {
            "ssrc": 111,
            "packets_received": 4_021,
            "packets_lost": 8,
            "bytes_received": 5_123_456,
            "jitter_s": 0.004,
            "nack_count": 2,
            "total_decode_time_s": 1.25,
        }
    ],
    "outbound": [
        {
            "ssrc": 222,
            "packets_sent": 512,
            "retransmitted_packets_sent": 1,
            "bytes_sent": 65_536,
            "target_bitrate_bps": 2_500_000.0,
            "round_trip_time_s": 0.0215,
        }
    ],
    "candidate_pairs": [
        {
            "current_round_trip_time_s": 0.0215,
            "priority": 9_115_038_255_631_187_199,
            "state": "succeeded",
        }
    ],
}

# What the core serializes on the first sample after connecting: counters, but
# nothing to have derived a rate from yet.
FIRST_SAMPLE_PAYLOAD: dict[str, Any] = {
    **FULL_PAYLOAD,
    "rtt_ms": None,
    "jitter_s": None,
    "packet_loss_ratio": None,
    "incoming_bitrate_bps": None,
    "outgoing_bitrate_bps": None,
    "target_bitrate_bps": None,
    "candidate_pair_state": None,
    "inbound": [],
    "outbound": [],
    "candidate_pairs": [],
}


def _reactor() -> Reactor:
    reactor = Reactor("m")
    # A real handle costs a session; conftest keeps this one away from
    # reactor_destroy.
    reactor._handle = 1234
    return reactor


def _lib_answering(payload: Any) -> mock.Mock:
    """A fake library whose `reactor_get_stats` completes with `payload`."""

    def fake_get_stats(handle, completion, userdata):
        body = None if payload is None else json.dumps(payload).encode()
        completion(1, body, None, None)

    lib = mock.Mock()
    lib.reactor_get_stats = fake_get_stats
    return lib


class TestGetStatsDispatch:
    async def test_the_handle_reaches_the_ffi_and_the_payload_comes_back_typed(self) -> None:
        seen: dict[str, Any] = {}

        def fake_get_stats(handle, completion, userdata):
            seen["handle"] = handle.value
            completion(1, json.dumps(FULL_PAYLOAD).encode(), None, None)

        lib = mock.Mock()
        lib.reactor_get_stats = fake_get_stats
        reactor = _reactor()

        with mock.patch("reactor_sdk.client.get_lib", return_value=lib):
            stats = await reactor.get_stats()

        assert seen["handle"] == 1234
        assert stats.rtt_ms == 21.5
        assert stats.incoming_bitrate_bps == 1_843_200.0
        assert stats.candidate_pair_state == "succeeded"
        assert stats.bytes_received == 5_123_456

    async def test_the_per_stream_arrays_survive_the_crossing(self) -> None:
        reactor = _reactor()

        with mock.patch("reactor_sdk.client.get_lib", return_value=_lib_answering(FULL_PAYLOAD)):
            stats = await reactor.get_stats()

        assert [s.ssrc for s in stats.inbound] == [111]
        assert stats.inbound[0].nack_count == 2
        assert stats.inbound[0].total_decode_time_s == 1.25
        assert [s.ssrc for s in stats.outbound] == [222]
        assert stats.outbound[0].retransmitted_packets_sent == 1
        assert stats.candidate_pairs[0].state == "succeeded"
        # A candidate-pair priority is a 64-bit value; Python's int takes it, but
        # a binding that read it as a float would round it.
        assert stats.candidate_pairs[0].priority == 9_115_038_255_631_187_199

    async def test_a_first_sample_reports_none_for_the_rates_not_zero(self) -> None:
        """Zero would read as an idle connection, which is the opposite of what
        "no previous sample to compare against" means."""
        reactor = _reactor()

        with mock.patch(
            "reactor_sdk.client.get_lib", return_value=_lib_answering(FIRST_SAMPLE_PAYLOAD)
        ):
            stats = await reactor.get_stats()

        assert stats.incoming_bitrate_bps is None
        assert stats.outgoing_bitrate_bps is None
        assert stats.rtt_ms is None
        # The counters are still there on every sample.
        assert stats.packets_received == 4_021

    async def test_a_failed_call_raises_the_typed_error(self) -> None:
        def fake_get_stats(handle, completion, userdata):
            completion(
                0,
                None,
                json.dumps(
                    {
                        "code": "INVALID_STATE",
                        "message": "no connection to read statistics from (status: disconnected)",
                        "recoverable": False,
                        "operation": "get_stats",
                    }
                ).encode(),
                None,
            )

        lib = mock.Mock()
        lib.reactor_get_stats = fake_get_stats
        reactor = _reactor()

        from reactor_sdk import InvalidStateError

        with mock.patch("reactor_sdk.client.get_lib", return_value=lib):
            with pytest.raises(InvalidStateError, match="no connection to read statistics"):
                await reactor.get_stats()

    async def test_without_a_handle_it_refuses_before_calling_in(self) -> None:
        with pytest.raises(RuntimeError, match="handle not created"):
            await Reactor("m").get_stats()


class TestDecodeRefusesRatherThanGuessing:
    """Every row here used to be a `ConnectionStats` full of zeroes, which is
    indistinguishable from a connection that is up and carrying nothing."""

    async def test_a_success_with_no_payload_raises(self) -> None:
        """`_async_op` reports an absent result as `None`. For a call whose whole
        purpose is the result, that is a decode failure, not an empty snapshot."""
        reactor = _reactor()

        with mock.patch("reactor_sdk.client.get_lib", return_value=_lib_answering(None)):
            with pytest.raises(DecodeError, match="not an object"):
                await reactor.get_stats()

    def test_a_payload_that_is_not_an_object_raises(self) -> None:
        with pytest.raises(DecodeError, match="not an object"):
            stats_from_payload([1, 2, 3])

    @pytest.mark.parametrize(
        "missing",
        [
            "packets_received",
            "packets_lost",
            "packets_sent",
            "bytes_received",
            "bytes_sent",
            "timestamp_ms",
        ],
    )
    def test_a_missing_counter_raises(self, missing: str) -> None:
        payload = {k: v for k, v in FULL_PAYLOAD.items() if k != missing}

        with pytest.raises(DecodeError, match="could not be read"):
            stats_from_payload(payload)

    def test_a_counter_of_the_wrong_type_raises(self) -> None:
        payload = {**FULL_PAYLOAD, "bytes_received": "5123456 bytes"}

        with pytest.raises(DecodeError, match="could not be read"):
            stats_from_payload(payload)

    def test_a_malformed_inbound_entry_raises(self) -> None:
        payload = {**FULL_PAYLOAD, "inbound": [{"ssrc": 111}]}

        with pytest.raises(DecodeError, match="could not be read"):
            stats_from_payload(payload)

    def test_the_error_names_the_failing_operation(self) -> None:
        """So `on_error` handlers and logs can attribute it without the traceback."""
        with pytest.raises(DecodeError) as raised:
            stats_from_payload({})

        assert raised.value.operation == "get_stats"
        assert raised.value.code == "DECODE_FAILED"


class TestDecodeShape:
    def test_null_scalars_become_none_and_absent_arrays_become_empty(self) -> None:
        """The arrays are the only fields allowed to be absent: a connection with
        no receive streams reports none, and that is not a failure."""
        payload = {k: v for k, v in FIRST_SAMPLE_PAYLOAD.items()}
        del payload["inbound"]
        del payload["outbound"]
        del payload["candidate_pairs"]

        stats = stats_from_payload(payload)

        assert stats.jitter_s is None
        assert stats.packet_loss_ratio is None
        assert stats.inbound == []
        assert stats.outbound == []
        assert stats.candidate_pairs == []

    def test_a_negative_loss_count_survives_as_negative(self) -> None:
        """RFC 3550 allows it when duplicates arrive, and the core reports the
        signed count deliberately — clamping it here would hide the duplicates
        the ratio already accounts for."""
        payload = {**FULL_PAYLOAD, "packets_lost": -3, "packet_loss_ratio": 0.0}

        stats = stats_from_payload(payload)

        assert stats.packets_lost == -3
        assert stats.packet_loss_ratio == 0.0

    def test_an_integer_where_a_float_is_documented_is_accepted(self) -> None:
        """`serde_json` writes a whole f64 as `21.0`, but a hand-built payload or
        another producer may write `21`. Both are the same number."""
        payload = {**FULL_PAYLOAD, "rtt_ms": 21, "jitter_s": 0}

        stats = stats_from_payload(payload)

        assert stats.rtt_ms == 21.0
        assert stats.jitter_s == 0.0

    def test_the_snapshot_is_frozen(self) -> None:
        """It is a reading taken at a moment; a caller that could edit it would be
        editing history."""
        stats = stats_from_payload(FULL_PAYLOAD)

        with pytest.raises(Exception):
            stats.rtt_ms = 0.0  # type: ignore[misc]
