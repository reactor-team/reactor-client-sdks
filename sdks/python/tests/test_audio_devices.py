"""Tests for the audio device helpers.

No real device is opened. `sounddevice` is replaced in `sys.modules` with a fake
that hands back the callback it was given, so the tests drive the device's side of
the conversation directly — which is the only way to exercise a jitter buffer,
since what it does depends entirely on *when* the device asks relative to when
audio arrives.

That is also where the bugs live. The buffer decides what to sacrifice when the
device and the stream disagree, and every one of those decisions is audible: a
misplaced leftover is continuous crackle, a missing preroll is a click on every
under-run, an unbounded buffer is latency that grows all session.
"""

from __future__ import annotations

import ctypes
import json
import sys
import threading
import time
from typing import Any
from unittest import mock

import pytest

from reactor_sdk import Reactor
from reactor_sdk.audio_devices import (
    BYTES_PER_SAMPLE,
    MAX_BUFFER_MS,
    PREROLL_MS,
    Microphone,
    Speaker,
)

RATE = 48_000
MONO = 1


def ms_of(pcm: bytes, channels: int = MONO) -> float:
    """How many milliseconds of audio a buffer holds."""
    return len(pcm) / (RATE * channels * BYTES_PER_SAMPLE) * 1000


def frames(ms: float, channels: int = MONO) -> bytes:
    """`ms` of arbitrary non-silent audio."""
    return bytes([1, 2] * int(RATE * ms / 1000) * channels)


class _FakeStream:
    """Stands in for a sounddevice stream, exposing the callback it was handed."""

    def __init__(self, **kwargs: Any) -> None:
        self.kwargs = kwargs
        self.callback = kwargs["callback"]
        self.started = False
        self.closed = False
        self.fail_on_start = False

    def start(self) -> None:
        if self.fail_on_start:
            raise RuntimeError("no such device")
        self.started = True

    def stop(self) -> None:
        self.started = False

    def close(self) -> None:
        self.closed = True

    # ── the device's side of the conversation ──────────────────────────────

    def pull(self, n_frames: int, channels: int = MONO) -> bytes:
        """Ask for a block, as the device would, and return what it got."""
        out = memoryview(bytearray(n_frames * channels * BYTES_PER_SAMPLE))
        self.callback(out, n_frames, None, None)
        return bytes(out)

    def capture(self, pcm: bytes) -> None:
        """Deliver a captured block, as the device would."""
        self.callback(memoryview(bytearray(pcm)), len(pcm) // BYTES_PER_SAMPLE, None, None)


class _FakeSoundDevice:
    def __init__(self) -> None:
        self.output: _FakeStream | None = None
        self.input: _FakeStream | None = None
        self.fail_output_start = False

    def RawOutputStream(self, **kwargs: Any) -> _FakeStream:  # noqa: N802 - sounddevice's name
        self.output = _FakeStream(**kwargs)
        self.output.fail_on_start = self.fail_output_start
        return self.output

    def RawInputStream(self, **kwargs: Any) -> _FakeStream:  # noqa: N802 - sounddevice's name
        self.input = _FakeStream(**kwargs)
        return self.input


@pytest.fixture
def sd(monkeypatch: pytest.MonkeyPatch) -> _FakeSoundDevice:
    fake = _FakeSoundDevice()
    monkeypatch.setitem(sys.modules, "sounddevice", fake)
    return fake


DECLARED = [
    {"name": "mic", "kind": "audio", "direction": "sendonly"},
    {"name": "speech", "kind": "audio", "direction": "recvonly"},
]


class _FakeLib:
    """Enough of the FFI for a `Track` to resolve itself and take a push."""

    def __init__(self) -> None:
        self._buffers: list[Any] = []
        self.pushed: list[tuple] = []

    def _string(self, payload: object) -> int:
        buffer = ctypes.create_string_buffer(json.dumps(payload).encode())
        self._buffers.append(buffer)
        return ctypes.cast(buffer, ctypes.c_void_p).value or 0

    declared: list[dict] = DECLARED

    def reactor_tracks(self, _handle: object) -> int:
        return self._string(self.declared)

    def reactor_paused_tracks(self, _handle: object) -> int:
        return self._string([])

    def reactor_free_string(self, _ptr: object) -> None: ...

    def reactor_push_audio_frame(self, _h, name, buf, spc, rate, channels) -> None:
        self.pushed.append((name, len(bytes(buf)), spc.value, rate.value, channels.value))


@pytest.fixture
def reactor(monkeypatch: pytest.MonkeyPatch) -> tuple[Reactor, _FakeLib]:
    """A connected-looking client whose tracks are real `Track` objects.

    Real ones on purpose: these helpers call `push_frame` and `on_raw_frame`, and a
    stub would keep passing if either signature drifted.
    """
    client = Reactor("m")
    client._handle = 1234
    lib = _FakeLib()
    monkeypatch.setattr("reactor_sdk.client.get_lib", lambda: lib)
    # A Microphone pushes into its track, and push_frame refuses a slot that was
    # never activated — so a fixture that left them unpublished would be testing
    # that guard over and over instead of the capture path.
    for track in client.tracks.with_direction("sendonly"):
        track._published = True
    return client, lib


class TestItStaysOutOfTheMandatoryNamespace:
    """The reason these live in their own module rather than in `reactor_sdk`.

    They are the one optional feature — `pip install "reactor-sdk[audio]"` — and
    keeping them off the import path everyone pays for is what makes that honest.
    """

    def test_importing_the_package_does_not_import_them(self) -> None:
        import subprocess
        import sys

        # A fresh interpreter, because this one has already imported the module.
        result = subprocess.run(
            [
                sys.executable,
                "-c",
                "import reactor_sdk, sys; print('reactor_sdk.audio_devices' in sys.modules)",
            ],
            capture_output=True,
            text=True,
            check=True,
        )
        assert result.stdout.strip() == "False", (
            "importing reactor_sdk pulled in the optional audio module"
        )

    def test_they_are_not_reachable_from_the_top_level(self) -> None:
        import reactor_sdk

        for name in ("Speaker", "Microphone"):
            assert not hasattr(reactor_sdk, name), (
                f"{name} is in reactor_sdk; it belongs to reactor_sdk.audio_devices"
            )


class TestSpeakerBuffer:
    """What the buffer trades away, and when."""

    def _playing(self, sd: _FakeSoundDevice) -> tuple[Speaker, _FakeStream]:
        """A speaker past its preroll, so the next pull returns real audio."""
        speaker = Speaker().start()
        speaker.submit(frames(PREROLL_MS), RATE, MONO)
        stream = sd.output
        assert stream is not None
        return speaker, stream

    def test_the_device_opens_with_the_format_of_the_first_frame(
        self, sd: _FakeSoundDevice
    ) -> None:
        """Not assumed: the frame reports its rate and channel count, and guessing
        wrong renders noise rather than failing."""
        Speaker().start().submit(frames(10, channels=2), 16_000, 2)

        assert sd.output is not None
        assert sd.output.kwargs["samplerate"] == 16_000
        assert sd.output.kwargs["channels"] == 2
        assert sd.output.kwargs["dtype"] == "int16"
        assert sd.output.started

    def test_silence_until_the_preroll_is_collected(self, sd: _FakeSoundDevice) -> None:
        """The device asks the moment the stream opens, before any audio exists.
        Answering with what little there is turns every such request into a click."""
        speaker = Speaker().start()
        speaker.submit(frames(PREROLL_MS / 2), RATE, MONO)

        block = sd.output.pull(480)

        assert block == bytes(len(block)), "should be silence, not a half-filled block"

    def test_audio_flows_once_the_preroll_is_there(self, sd: _FakeSoundDevice) -> None:
        _, stream = self._playing(sd)
        assert stream.pull(480) != bytes(960)

    def test_a_block_continues_where_the_last_one_stopped(self, sd: _FakeSoundDevice) -> None:
        """The crackle bug this buffer is shaped to avoid: the device's block size
        does not divide the ~10 ms chunks the SDK delivers, so anything that puts a
        leftover back on the queue reorders the stream a little, every block."""
        speaker = Speaker().start()
        # A recognisable ramp, so a misplaced byte is visible rather than merely audible.
        ramp = bytes(range(256)) * 8
        speaker.submit(frames(PREROLL_MS) + ramp, RATE, MONO)
        stream = sd.output

        preroll = stream.pull(int(RATE * PREROLL_MS / 1000))
        first = stream.pull(300)
        second = stream.pull(724)

        assert len(preroll) + len(first) + len(second) == len(frames(PREROLL_MS)) + len(ramp)
        assert first + second == ramp, "the stream must come out in the order it went in"

    def test_the_backlog_is_bounded_and_the_oldest_goes(self, sd: _FakeSoundDevice) -> None:
        """An unbounded buffer is latency that grows for the whole session. Dropping
        the oldest means what plays next is the most recent audio."""
        speaker = Speaker().start()
        head = bytes([0x11, 0x11]) * int(RATE * MAX_BUFFER_MS / 1000)
        tail = bytes([0x22, 0x22]) * int(RATE * 100 / 1000)

        speaker.submit(head + tail, RATE, MONO)

        assert speaker.dropped_ms == pytest.approx(100, abs=2)
        # Exactly the oldest 100 ms went, and the newest audio is all still there.
        assert bytes(speaker._pcm) == head[len(tail) :] + tail

    def test_an_under_run_is_padded_counted_and_re_prerolled(self, sd: _FakeSoundDevice) -> None:
        """One short silence beats a click on nearly every block, which is what
        continuing at the edge of an empty buffer produces."""
        speaker, stream = self._playing(sd)
        stream.pull(int(RATE * PREROLL_MS / 1000))  # drain the preroll

        speaker.submit(frames(5), RATE, MONO)
        block = stream.pull(480)  # asks for 10 ms, only 5 ms is there

        assert speaker.under_runs == 1
        assert block[-100:] == bytes(100), "the shortfall is padded with silence"
        # Back to collecting: the next request is silence rather than a trickle.
        speaker.submit(frames(5), RATE, MONO)
        assert stream.pull(480) == bytes(960)

    def test_a_format_change_is_refused_rather_than_played_at_the_wrong_pitch(
        self, sd: _FakeSoundDevice, caplog: pytest.LogCaptureFixture
    ) -> None:
        speaker = Speaker().start()
        speaker.submit(frames(10), RATE, MONO)

        with caplog.at_level("WARNING"):
            speaker.submit(frames(10), 16_000, MONO)

        assert "format changed" in caplog.text
        assert sd.output.kwargs["samplerate"] == RATE


class TestSpeakerLifecycle:
    def test_a_missing_sounddevice_is_reported_at_start(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """At start, not at the first frame: a missing dependency should surface
        where the caller is looking, not on a media delivery thread."""
        monkeypatch.setitem(sys.modules, "sounddevice", None)
        monkeypatch.delitem(sys.modules, "sounddevice")
        monkeypatch.setattr(
            "reactor_sdk.audio_devices._sounddevice",
            mock.Mock(side_effect=ModuleNotFoundError("audio devices need sounddevice")),
        )
        with pytest.raises(ModuleNotFoundError, match="need sounddevice"):
            Speaker().start()

    def test_a_device_that_will_not_open_does_not_take_the_session_down(
        self, sd: _FakeSoundDevice, caplog: pytest.LogCaptureFixture
    ) -> None:
        """No output device, a rate it will not take, an exclusive-mode conflict.
        The stream is what failed; the caller's session is not the SDK's to end."""
        sd.fail_output_start = True
        speaker = Speaker().start()

        with caplog.at_level("WARNING"):
            speaker.submit(frames(10), RATE, MONO)
            speaker.submit(frames(10), RATE, MONO)  # and again, without retrying

        assert "could not open an audio output device" in caplog.text

    def test_stop_closes_the_device_and_is_idempotent(self, sd: _FakeSoundDevice) -> None:
        speaker = Speaker().start()
        speaker.submit(frames(10), RATE, MONO)

        speaker.stop()
        speaker.stop()

        assert sd.output.closed

    def test_it_attaches_to_a_track_and_detaches_on_stop(
        self, sd: _FakeSoundDevice, reactor: tuple[Reactor, _FakeLib]
    ) -> None:
        client, _ = reactor
        track = client.track("speech")

        speaker = Speaker(track).start()
        client._fire_on_track("audio", b"speech", frames(PREROLL_MS), 480, RATE, MONO)
        assert sd.output is not None, "the track's frames should have opened the device"

        speaker.stop()
        before = len(speaker._pcm)
        client._fire_on_track("audio", b"speech", frames(10), 480, RATE, MONO)
        assert len(speaker._pcm) == before, "nothing should arrive after stop"

    def test_a_stop_during_an_open_does_not_leak_the_stream(self, sd: _FakeSoundDevice) -> None:
        """The race the lifecycle lock exists for.

        A frame arriving as the speaker is stopped used to pass the `_closed` check,
        `stop` would then find no stream to close, and the frame would go on to open
        one nothing ever closed — playback outliving its own teardown, with the
        device held open. Reproduced by making the open slow and stopping during it.
        """
        speaker = Speaker().start()
        opening = threading.Event()
        may_finish = threading.Event()
        real_stream = sd.RawOutputStream

        def slow_open(**kwargs: Any) -> _FakeStream:
            opening.set()
            may_finish.wait(timeout=2)
            return real_stream(**kwargs)

        sd.RawOutputStream = slow_open  # type: ignore[method-assign]

        submitting = threading.Thread(target=speaker.submit, args=(frames(10), RATE, MONO))
        submitting.start()
        assert opening.wait(timeout=2), "the open never started"

        stopping = threading.Thread(target=speaker.stop)
        stopping.start()
        # Long enough for stop to reach the lock. Without the fix there is no lock to
        # reach, so it runs straight through and the assertion below fails — which is
        # what makes this a regression test rather than a timing coincidence.
        time.sleep(0.05)
        may_finish.set()

        submitting.join(timeout=2)
        stopping.join(timeout=2)

        assert sd.output is not None, "the fake should have been constructed"
        assert sd.output.closed, "a stream opened during stop was left open"

    def test_as_a_context_manager(self, sd: _FakeSoundDevice) -> None:
        with Speaker() as speaker:
            speaker.submit(frames(10), RATE, MONO)
        assert sd.output.closed


class TestMicrophone:
    def test_it_captures_in_blocks_the_far_end_expects(
        self, sd: _FakeSoundDevice, reactor: tuple[Reactor, _FakeLib]
    ) -> None:
        """Left to the device the block size is whatever it prefers — often far
        larger, arriving as bursts the far end's jitter buffer has to smooth."""
        client, _ = reactor
        Microphone(client.track("mic")).start()

        assert sd.input is not None
        assert sd.input.kwargs["blocksize"] == 480  # 10 ms at 48 kHz
        assert sd.input.kwargs["samplerate"] == 48_000
        assert sd.input.kwargs["channels"] == 1
        assert sd.input.kwargs["dtype"] == "int16"

    def test_a_captured_block_reaches_the_track(
        self, sd: _FakeSoundDevice, reactor: tuple[Reactor, _FakeLib]
    ) -> None:
        """Through the real `Track.push_frame`, so the audio-kind dispatch and the
        samples-per-channel arithmetic are exercised rather than assumed."""
        client, lib = reactor
        mic = Microphone(client.track("mic")).start()

        sd.input.capture(frames(10))

        assert lib.pushed == [(b"mic", 960, 480, 48_000, 1)]
        assert mic.blocks_sent == 1

    def test_a_format_the_track_cannot_send_is_refused_at_construction(
        self, sd: _FakeSoundDevice, reactor: tuple[Reactor, _FakeLib]
    ) -> None:
        """At construction, not at the first block: `_captured` runs on the device's
        thread, where a raise is printed and swallowed rather than reaching the
        caller. Nothing opens a device for a capture that could not be sent."""
        client, _ = reactor

        with pytest.raises(ValueError, match="48000 Hz mono"):
            Microphone(client.track("mic"), sample_rate=16_000)
        with pytest.raises(ValueError, match="48000 Hz mono"):
            Microphone(client.track("mic"), channels=2)

        assert sd.input is None

    def test_stop_closes_the_device_and_is_idempotent(
        self, sd: _FakeSoundDevice, reactor: tuple[Reactor, _FakeLib]
    ) -> None:
        client, _ = reactor
        mic = Microphone(client.track("mic")).start()

        mic.stop()
        mic.stop()

        assert sd.input.closed

    def test_as_a_context_manager(
        self, sd: _FakeSoundDevice, reactor: tuple[Reactor, _FakeLib]
    ) -> None:
        client, _ = reactor
        with Microphone(client.track("mic")):
            pass
        assert sd.input.closed
