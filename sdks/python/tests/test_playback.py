"""Tests for `AudioPlayer`.

Two layers, tested two different ways. The FIFO/preroll/drop logic is pure and
deterministic, so it runs against a fake `sounddevice` — a real callback thread
pulling on a deadline would make the same assertions flaky for no benefit.
`TestRealDevice` is the other layer: it opens an actual output stream on
whatever this machine has, because the fake proves the logic but not that the
real `sd.RawOutputStream(...)` call is spelled correctly — it skips itself
where no device is available (headless CI), the same way `test_ffi_bindings.py`
skips without a built library.
"""

from __future__ import annotations

import logging
import sys
import types
from typing import Any
from unittest import mock

import pytest

from reactor_sdk import Reactor, Track
from reactor_sdk.playback import AudioPlayer

# int16 mono at 1000 Hz: 2 bytes/frame, so byte counts double as frame counts
# and every duration in ms doubles as a byte count too — keeps the arithmetic
# in each test honest at a glance instead of buried in a helper.
_RATE = 1000
_CHANNELS = 1


class _FakeStream:
    """Stands in for `sd.RawOutputStream`: records the call, exposes `callback`
    so a test can pull a block itself instead of waiting on a real thread."""

    instances: list[_FakeStream] = []

    def __init__(self, *, samplerate: int, channels: int, dtype: str, callback: Any) -> None:
        self.samplerate = samplerate
        self.channels = channels
        self.dtype = dtype
        self.callback = callback
        self.started = False
        self.closed = False
        _FakeStream.instances.append(self)

    def start(self) -> None:
        self.started = True

    def stop(self) -> None:
        self.started = False

    def close(self) -> None:
        self.closed = True


@pytest.fixture(autouse=True)
def _fake_sounddevice(monkeypatch: pytest.MonkeyPatch) -> None:
    """Every test in this file sees a `sounddevice` that never touches hardware,
    unless it explicitly asks otherwise (`TestUnavailable`, `TestRealDevice`)."""
    _FakeStream.instances.clear()
    fake = types.ModuleType("sounddevice")
    fake.RawOutputStream = _FakeStream  # type: ignore[attr-defined]
    monkeypatch.setitem(sys.modules, "sounddevice", fake)


def _pull(stream: _FakeStream, frames: int) -> bytes:
    outdata = bytearray(frames * _CHANNELS * 2)
    stream.callback(outdata, frames, None, None)
    return bytes(outdata)


class TestOpening:
    def test_the_device_opens_on_the_first_submit(self) -> None:
        player = AudioPlayer()
        assert _FakeStream.instances == []

        player.submit(b"\x00\x00", _RATE, _CHANNELS)

        assert len(_FakeStream.instances) == 1
        stream = _FakeStream.instances[0]
        assert (stream.samplerate, stream.channels, stream.dtype) == (_RATE, _CHANNELS, "int16")
        assert stream.started

    def test_a_second_submit_does_not_reopen_the_device(self) -> None:
        player = AudioPlayer()
        player.submit(b"\x00\x00", _RATE, _CHANNELS)
        player.submit(b"\x00\x00", _RATE, _CHANNELS)
        assert len(_FakeStream.instances) == 1

    def test_a_format_change_is_ignored_rather_than_reopened(
        self, caplog: pytest.LogCaptureFixture
    ) -> None:
        player = AudioPlayer()
        player.submit(b"\x00\x00", _RATE, _CHANNELS)
        with caplog.at_level(logging.WARNING, logger="reactor_sdk.playback"):
            player.submit(b"\x00\x00", _RATE * 2, _CHANNELS)
        assert len(_FakeStream.instances) == 1
        assert "format changed" in caplog.text


class TestPreroll:
    def test_silence_plays_until_preroll_is_reached(self) -> None:
        player = AudioPlayer(preroll_ms=10, max_buffer_ms=1000)  # 20-byte preroll
        player.submit(b"A" * 10, _RATE, _CHANNELS)  # under the 20-byte threshold
        stream = _FakeStream.instances[0]

        assert _pull(stream, frames=5) == bytes(10)  # silence, not the buffered "A"s

    def test_playback_starts_once_preroll_is_reached(self) -> None:
        player = AudioPlayer(preroll_ms=10, max_buffer_ms=1000)  # 20-byte preroll
        player.submit(b"A" * 20, _RATE, _CHANNELS)
        stream = _FakeStream.instances[0]

        assert _pull(stream, frames=10) == b"A" * 20

    def test_an_underrun_re_arms_the_preroll(self) -> None:
        """Draining the buffer below the wanted block size, not just to zero,
        must go back to collecting rather than stutter on every later block."""
        player = AudioPlayer(preroll_ms=10, max_buffer_ms=1000)
        player.submit(b"A" * 20, _RATE, _CHANNELS)  # exactly the preroll
        stream = _FakeStream.instances[0]
        _pull(stream, frames=10)  # consumes it all, playing=True

        player.submit(b"B" * 5, _RATE, _CHANNELS)  # short of the next block
        block = _pull(stream, frames=10)  # under-run: padded with zeros
        assert block == b"B" * 5 + bytes(15)

        # Re-armed: a little more data alone must not resume playback...
        player.submit(b"C" * 5, _RATE, _CHANNELS)
        assert _pull(stream, frames=5) == bytes(10)
        # ...only reaching the preroll threshold again does.
        player.submit(b"D" * 15, _RATE, _CHANNELS)
        assert _pull(stream, frames=10) == (b"C" * 5 + b"D" * 15)[:20]


class TestOverflow:
    def test_the_oldest_bytes_are_dropped_once_the_buffer_is_full(self) -> None:
        player = AudioPlayer(preroll_ms=0, max_buffer_ms=10)  # 20-byte ceiling
        player.submit(b"A" * 20, _RATE, _CHANNELS)
        player.submit(b"B" * 10, _RATE, _CHANNELS)  # 30 buffered, 10 over

        stream = _FakeStream.instances[0]
        # The oldest 10 "A"s are gone; what remains is the newest 20 bytes.
        assert _pull(stream, frames=10) == b"A" * 10 + b"B" * 10


class TestUnavailable:
    def test_missing_sounddevice_is_silent_not_fatal(
        self, monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
    ) -> None:
        monkeypatch.setitem(sys.modules, "sounddevice", None)
        player = AudioPlayer()

        with caplog.at_level(logging.WARNING, logger="reactor_sdk.playback"):
            player.submit(b"\x00\x00", _RATE, _CHANNELS)  # must not raise

        assert "not installed" in caplog.text
        assert _FakeStream.instances == []

    def test_stays_unavailable_after_the_first_failed_attempt(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """Otherwise every single frame re-attempts the import and re-logs the
        same warning for the life of a session with no player."""
        monkeypatch.setitem(sys.modules, "sounddevice", None)
        player = AudioPlayer()
        player.submit(b"\x00\x00", _RATE, _CHANNELS)

        with mock.patch("builtins.__import__", side_effect=AssertionError("should not import")):
            player.submit(b"\x00\x00", _RATE, _CHANNELS)  # must not raise either

    def test_a_device_open_failure_is_also_silent(self, caplog: pytest.LogCaptureFixture) -> None:
        def explode(**_kwargs: object) -> None:
            raise OSError("no such device")

        sys.modules["sounddevice"].RawOutputStream = explode  # type: ignore[union-attr]
        player = AudioPlayer()

        with caplog.at_level(logging.WARNING, logger="reactor_sdk.playback"):
            player.submit(b"\x00\x00", _RATE, _CHANNELS)

        assert "could not open" in caplog.text


class TestAttach:
    """`attach()` is `on_raw_frame` plus dropping the sample count — pinned end
    to end through a real `Track`, not just checked as a lambda in isolation."""

    def _speech_track(self, monkeypatch: pytest.MonkeyPatch) -> tuple[Reactor, Track]:
        # Track holds its Reactor weakly, so the caller must keep `reactor` alive
        # itself — returning only the Track lets it be collected before attach()
        # runs, which is a fixture bug, not something this test is about.
        reactor = Reactor("https://api.reactor.inc", "m")
        reactor._handle = 1234
        declared = [{"name": "speech", "kind": "audio", "direction": "recvonly"}]
        lib = mock.Mock()

        import ctypes
        import json

        buf = ctypes.create_string_buffer(json.dumps(declared).encode())
        lib.reactor_tracks = lambda _h: ctypes.cast(buf, ctypes.c_void_p).value
        lib.reactor_paused_tracks = lambda _h: 0
        lib.reactor_free_string = lambda _p: None
        monkeypatch.setattr("reactor_sdk.client.get_lib", lambda: lib)
        return reactor, reactor.track("speech")

    def test_attach_returns_the_track(self, monkeypatch: pytest.MonkeyPatch) -> None:
        _reactor, track = self._speech_track(monkeypatch)
        assert AudioPlayer().attach(track) is track

    def test_a_raw_frame_reaches_submit_without_the_sample_count(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        reactor, track = self._speech_track(monkeypatch)
        player = AudioPlayer()
        received: list[tuple[bytes, int, int]] = []
        player.submit = lambda pcm, rate, channels: received.append((pcm, rate, channels))  # type: ignore[method-assign]

        player.attach(track)
        reactor._fire(f"audio@{track.name}", b"\x01\x02", 1, 48000, 2)

        assert received == [(b"\x01\x02", 48000, 2)]


class TestRealDevice:
    """No fake here — the point is to catch a call that's wrong in a way the
    fake's permissive signature would not: an argument name PortAudio rejects,
    a dtype it does not support, on whatever real backend this machine has."""

    @pytest.fixture(autouse=True)
    def _real_sounddevice(self, monkeypatch: pytest.MonkeyPatch) -> None:
        # The file-wide autouse fixture already cached a fake under this name —
        # drop it so the import below reaches the real package, if there is one.
        monkeypatch.delitem(sys.modules, "sounddevice", raising=False)
        sd = pytest.importorskip("sounddevice")
        try:
            if not any(d["max_output_channels"] > 0 for d in sd.query_devices()):
                pytest.skip("no audio output device on this machine")
        except Exception as e:
            pytest.skip(f"could not query audio devices: {e}")

    def test_a_real_stream_opens_plays_silence_and_closes(self) -> None:
        player = AudioPlayer(preroll_ms=0)
        player.submit(bytes(960), _RATE, _CHANNELS)  # opens a real device
        assert player._stream is not None
        player.stop()
        assert player._stream is None
