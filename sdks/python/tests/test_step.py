"""Stepped generation against a faked `libreactor_ffi`.

The fake implements the C ABI's step surface over plain Python data, with real
C buffers behind every pointer the accessors hand out — so the path that copies a
native result out and frees it is the path these tests run, not a stand-in for it.
"""

from __future__ import annotations

import asyncio
import ctypes
import json
from typing import Any
from unittest import mock

import pytest

from reactor_sdk import (
    DecodeError,
    GenerationModeMismatchError,
    InvalidStateError,
    MessageTooLargeError,
    Reactor,
    StepCommand,
    StepCommandFailedError,
    StepFile,
    StepFrame,
    StepNotReadyError,
    StepRefusedError,
    StepResult,
    StepResultTooLargeError,
    StepSessionEndedError,
    StepTrackOutput,
    TrackKind,
)
from reactor_sdk._step import _wire_commands
from reactor_sdk.errors import AbortedError

np = pytest.importorskip("numpy")


def _error(code: str, message: str = "nope", recoverable: bool = False) -> bytes:
    return json.dumps(
        {"code": code, "message": message, "recoverable": recoverable, "operation": "step"}
    ).encode()


class _NativeFrame:
    """One frame as the native result holds it: C memory behind every pointer."""

    def __init__(
        self,
        data: bytes,
        dtype: str,
        shape: tuple[int, ...],
        metadata: bytes | None = None,
        capture_time_us: int | None = None,
    ) -> None:
        self.data = ctypes.create_string_buffer(data, len(data))
        self.data_len = len(data)
        self.dtype = dtype.encode()
        self.shape = (ctypes.c_uint32 * len(shape))(*shape)
        self.ndim = len(shape)
        self.metadata = (
            ctypes.create_string_buffer(metadata, max(1, len(metadata)))
            if metadata is not None
            else None
        )
        self.metadata_len = len(metadata) if metadata is not None else 0
        self.capture_time_us = capture_time_us


class FakeStepLib:
    """The step half of the C ABI, plus just enough of the rest to connect."""

    def __init__(self) -> None:
        self.results: dict[int, tuple[int, list[tuple[str, str, list[_NativeFrame]]]]] = {}
        self.elapsed: dict[int, float] = {}
        # Per result: each track's rate, 0.0 for a track the runtime gave none.
        self.rates: dict[int, list[float]] = {}
        # Per result: (name, content_type, size) per file, and the error string,
        # both behind real C buffers like every other borrowed string.
        self.files: dict[int, list[tuple[Any, Any, int]]] = {}
        self.errors: dict[int, Any] = {}
        self.freed: list[int] = []
        self.created_with: dict[str, Any] = {}
        self.step_calls: list[tuple[bytes | None, bytes | None]] = []
        self.step_answer: Any = None  # callable(completion) run on reactor_step
        self.cleared = 0
        self.create_error: bytes | None = None
        self.free_strings: list[int] = []

    # -- lifecycle ---------------------------------------------------------

    def reactor_create_with_options(
        self, api_url, model_name, jwt, callbacks, options_json, error_out
    ):
        self.created_with = json.loads(options_json)
        if self.create_error is not None:
            self._error_buf = ctypes.create_string_buffer(self.create_error)
            error_out._obj.value = ctypes.addressof(self._error_buf)
            return None
        return 1234

    def reactor_create_with_adm(self, *args):
        self.created_with = {"via": "reactor_create_with_adm"}
        return 1234

    def reactor_connect(self, handle, session_id, connection_id, completion, userdata):
        completion(1, b"{}", None, None)

    def reactor_destroy(self, handle):
        return 0

    def reactor_free_string(self, ptr):
        self.free_strings.append(ptr.value if isinstance(ptr, ctypes.c_void_p) else ptr)

    # -- steps -------------------------------------------------------------

    def reactor_step(self, handle, state_json, commands_json, completion, userdata):
        self.step_calls.append((state_json, commands_json))
        if self.step_answer is not None:
            self.step_answer(completion)

    def reactor_clear_step_inputs(self, handle):
        self.cleared += 1

    def add_result(
        self,
        index: int,
        tracks: list[tuple[str, str, list[_NativeFrame]]],
        elapsed: float = 0.0,
        files: list[tuple[str, str, int]] = (),  # type: ignore[assignment]
        error: str | None = None,
        rates: list[float] = (),  # type: ignore[assignment]
    ) -> int:
        ptr = 0x1000 + len(self.results)
        self.results[ptr] = (index, tracks)
        self.elapsed[ptr] = elapsed
        self.rates[ptr] = [*rates, *[0.0] * (len(tracks) - len(rates))]
        self.files[ptr] = [
            (ctypes.create_string_buffer(n.encode()), ctypes.create_string_buffer(c.encode()), s)
            for n, c, s in files
        ]
        self.errors[ptr] = None if error is None else ctypes.create_string_buffer(error.encode())
        return ptr

    def _frame(self, ptr, t, f) -> _NativeFrame | None:
        _, tracks = self.results[ptr.value]
        if t >= len(tracks) or f >= len(tracks[t][2]):
            return None
        return tracks[t][2][f]

    def reactor_step_result_step(self, ptr):
        return self.results[ptr.value][0]

    def reactor_step_result_elapsed(self, ptr):
        return self.elapsed[ptr.value]

    def reactor_step_result_error(self, ptr):
        # What the c_char_p restype hands back: the borrowed string's bytes.
        buf = self.errors[ptr.value]
        return None if buf is None else buf.value

    def reactor_step_result_file_count(self, ptr):
        return len(self.files[ptr.value])

    def reactor_step_result_file_name(self, ptr, i):
        return self.files[ptr.value][i][0].value

    def reactor_step_result_file_content_type(self, ptr, i):
        return self.files[ptr.value][i][1].value

    def reactor_step_result_file_size(self, ptr, i):
        return self.files[ptr.value][i][2]

    def reactor_step_result_track_count(self, ptr):
        return len(self.results[ptr.value][1])

    def reactor_step_result_track_name(self, ptr, t):
        return self.results[ptr.value][1][t][0].encode()

    def reactor_step_result_track_kind(self, ptr, t):
        return self.results[ptr.value][1][t][1].encode()

    def reactor_step_result_track_rate(self, ptr, t):
        return self.rates[ptr.value][t]

    def reactor_step_result_frame_count(self, ptr, t):
        return len(self.results[ptr.value][1][t][2])

    def reactor_step_result_frame_encoding(self, ptr, t, f):
        return b"raw"

    def reactor_step_result_frame_dtype(self, ptr, t, f):
        return self._frame(ptr, t, f).dtype

    def reactor_step_result_frame_shape(self, ptr, t, f, ndim):
        frame = self._frame(ptr, t, f)
        ndim._obj.value = frame.ndim
        return frame.shape

    def reactor_step_result_frame_data(self, ptr, t, f, length):
        frame = self._frame(ptr, t, f)
        length._obj.value = frame.data_len
        return ctypes.addressof(frame.data)

    def reactor_step_result_frame_metadata(self, ptr, t, f, length):
        frame = self._frame(ptr, t, f)
        if frame.metadata is None:
            length._obj.value = 0
            return None
        length._obj.value = frame.metadata_len
        return ctypes.addressof(frame.metadata)

    def reactor_step_result_frame_capture_time_us(self, ptr, t, f, out):
        frame = self._frame(ptr, t, f)
        if frame.capture_time_us is None:
            return 0
        out._obj.value = frame.capture_time_us
        return 1

    def reactor_step_result_free(self, ptr):
        self.freed.append(ptr.value)


@pytest.fixture
def lib(monkeypatch: pytest.MonkeyPatch) -> FakeStepLib:
    fake = FakeStepLib()
    monkeypatch.setattr("reactor_sdk.client.get_lib", lambda: fake)
    return fake


async def _stepped(lib: FakeStepLib, **kwargs: Any) -> Reactor:
    reactor = Reactor("owner/model", jwt="t", generation="stepped", **kwargs)
    await reactor.connect()
    return reactor


# ── construction ────────────────────────────────────────────────────────────


class TestConstruction:
    def test_streamed_is_the_default(self) -> None:
        assert Reactor("m").generation == "streamed"

    @pytest.mark.parametrize("generation", ["step", "STEPPED", "", None])
    def test_an_unknown_generation_is_refused(self, generation: Any) -> None:
        with pytest.raises(ValueError, match="generation"):
            Reactor("m", generation=generation)

    def test_a_step_timeout_without_stepped_generation_is_refused(self) -> None:
        with pytest.raises(ValueError, match="step_timeout"):
            Reactor("m", step_timeout=10)

    @pytest.mark.parametrize("timeout", [0, -1, float("inf"), float("nan"), "10"])
    def test_a_step_timeout_must_be_a_positive_number(self, timeout: Any) -> None:
        with pytest.raises(ValueError, match="step_timeout"):
            Reactor("m", generation="stepped", step_timeout=timeout)

    async def test_stepped_creates_through_the_options_entry(self, lib: FakeStepLib) -> None:
        await _stepped(lib, step_timeout=12.5)
        assert lib.created_with == {
            "local": False,
            "auto_resume_tracks": True,
            "adm": "synthetic",
            "sdk_version": lib.created_with["sdk_version"],
            "sdk_type": "python",
            "generation": "stepped",
            "step_timeout_seconds": 12.5,
        }

    async def test_streamed_keeps_the_existing_entry(self, lib: FakeStepLib) -> None:
        await Reactor("m", jwt="t").connect()
        assert lib.created_with == {"via": "reactor_create_with_adm"}

    async def test_refused_options_raise_the_typed_error_and_free_it(
        self, lib: FakeStepLib
    ) -> None:
        lib.create_error = json.dumps(
            {"code": "INVALID_STATE", "message": "bad options", "recoverable": False}
        ).encode()
        with pytest.raises(InvalidStateError, match="bad options"):
            await Reactor("m", jwt="t", generation="stepped").connect()
        assert len(lib.free_strings) == 1

    async def test_a_library_without_stepped_generation_says_so(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        # spec= limits the fake to the pre-stepped surface.
        old = mock.Mock(spec=["reactor_create_with_adm", "reactor_destroy"])
        monkeypatch.setattr("reactor_sdk.client.get_lib", lambda: old)
        with pytest.raises(RuntimeError, match="predates stepped generation"):
            await Reactor("m", jwt="t", generation="stepped").connect()

    async def test_a_runtime_that_ignores_the_mode_fails_connect_typed(
        self, lib: FakeStepLib
    ) -> None:
        lib.reactor_connect = lambda h, s, c, completion, u: completion(
            0, None, _error("GENERATION_MODE_MISMATCH", "runtime started streamed"), None
        )
        with pytest.raises(GenerationModeMismatchError):
            await Reactor("m", jwt="t", generation="stepped").connect()


# ── step() ──────────────────────────────────────────────────────────────────


class TestStep:
    async def test_step_needs_a_stepped_client(self, lib: FakeStepLib) -> None:
        reactor = Reactor("m", jwt="t")
        await reactor.connect()
        with pytest.raises(InvalidStateError, match='generation="stepped"'):
            await reactor.step()
        assert lib.step_calls == []

    async def test_step_needs_a_handle(self) -> None:
        with pytest.raises(RuntimeError, match="connect"):
            await Reactor("m", generation="stepped").step()

    async def test_state_and_commands_reach_the_ffi_as_json(self, lib: FakeStepLib) -> None:
        reactor = await _stepped(lib)
        lib.step_answer = lambda done: done(1, lib.add_result(1, []), None, None)
        await reactor.step(
            state={"prompt": "fox"},
            commands=[StepCommand("advance", {"frames": 2}), {"command": "reset"}],
        )
        state, commands = lib.step_calls[0]
        assert json.loads(state) == {"prompt": "fox"}
        assert json.loads(commands) == [
            {"command": "advance", "data": {"frames": 2}},
            {"command": "reset", "data": None},
        ]

    async def test_no_state_is_a_null_pointer_and_no_commands_an_empty_list(
        self, lib: FakeStepLib
    ) -> None:
        reactor = await _stepped(lib)
        lib.step_answer = lambda done: done(1, lib.add_result(1, []), None, None)
        await reactor.step()
        await reactor.step(state=None, commands=None)
        assert lib.step_calls == [(None, b"[]"), (None, b"[]")]

    async def test_a_result_is_copied_out_decoded_and_freed(self, lib: FakeStepLib) -> None:
        reactor = await _stepped(lib)
        rgb = np.arange(2 * 3 * 3, dtype=np.uint8).reshape(2, 3, 3)
        pcm = np.array([[1, -2, 3], [4, 5, -6]], dtype="<i2")
        ptr = lib.add_result(
            7,
            [
                (
                    "main_video",
                    "video",
                    [_NativeFrame(rgb.tobytes(), "uint8", (2, 3, 3), b"tag", 99)],
                ),
                ("main_audio", "audio", [_NativeFrame(pcm.tobytes(), "int16", (2, 3))]),
            ],
            elapsed=0.25,
            rates=[24.0, 48000.0],
        )
        lib.step_answer = lambda done: done(1, ptr, None, None)

        result = await reactor.step()

        assert lib.freed == [ptr], "freed exactly once, after the copy"
        assert isinstance(result, StepResult)
        assert result.step == 7
        assert result.elapsed == 0.25
        video = result.track("main_video")
        assert video.kind is TrackKind.VIDEO
        assert video.rate == 24.0
        frame = video.frames[0]
        assert frame.encoding == "raw"
        assert frame.metadata == b"tag"
        assert frame.capture_time_us == 99
        np.testing.assert_array_equal(frame.array, rgb)
        assert result.track("main_audio").kind is TrackKind.AUDIO
        assert result.track("main_audio").rate == 48000.0
        audio = result.track("main_audio").frames[0]
        assert audio.metadata is None and audio.capture_time_us is None
        assert audio.array.dtype == np.int16
        np.testing.assert_array_equal(audio.array, pcm)
        assert result.files == ()
        assert result.error is None

    async def test_files_are_copied_out_in_order(self, lib: FakeStepLib) -> None:
        reactor = await _stepped(lib)
        ptr = lib.add_result(
            2,
            [],
            files=[("depth.npy", "application/octet-stream", 4096), ("log.txt", "text/plain", 0)],
        )
        lib.step_answer = lambda done: done(1, ptr, None, None)

        result = await reactor.step()

        assert result.files == (
            StepFile("depth.npy", "application/octet-stream", 4096),
            StepFile("log.txt", "text/plain", 0),
        )
        assert lib.freed == [ptr]

    async def test_a_step_the_model_failed_resolves_with_its_error(self, lib: FakeStepLib) -> None:
        reactor = await _stepped(lib)
        ptr = lib.add_result(4, [], elapsed=0.5, error="out of memory")
        lib.step_answer = lambda done: done(1, ptr, None, None)

        result = await reactor.step(state={"prompt": "fox"})

        assert result.step == 4
        assert result.error == "out of memory"
        assert result.elapsed == 0.5
        assert lib.freed == [ptr]

    async def test_a_frame_whose_bytes_disagree_with_its_shape_fails_and_is_still_freed(
        self, lib: FakeStepLib
    ) -> None:
        reactor = await _stepped(lib)
        ptr = lib.add_result(1, [("v", "video", [_NativeFrame(b"\0" * 5, "uint8", (2, 3, 3))])])
        lib.step_answer = lambda done: done(1, ptr, None, None)
        with pytest.raises(DecodeError, match="needs 18 bytes, got 5") as raised:
            await reactor.step()
        assert raised.value.operation == "step"
        assert lib.freed == [ptr]

    @pytest.mark.parametrize(
        ("code", "expected"),
        [
            ("step_refused", StepRefusedError),
            ("step_not_ready", StepNotReadyError),
            ("step_result_too_large", StepResultTooLargeError),
            ("step_command_failed", StepCommandFailedError),
            ("step_session_ended", StepSessionEndedError),
            ("MESSAGE_TOO_LARGE", MessageTooLargeError),
            ("UNSUPPORTED_FRAME_ENCODING", None),
        ],
    )
    async def test_failures_raise_their_own_class(
        self, lib: FakeStepLib, code: str, expected: type | None
    ) -> None:
        from reactor_sdk import UnsupportedFrameEncodingError

        expected = expected or UnsupportedFrameEncodingError
        reactor = await _stepped(lib)
        lib.step_answer = lambda done: done(0, None, _error(code), None)
        with pytest.raises(expected) as caught:
            await reactor.step()
        assert caught.value.code == code
        assert caught.value.operation == "step"

    async def test_a_step_in_flight_when_the_client_closes_is_aborted(
        self, lib: FakeStepLib
    ) -> None:
        reactor = await _stepped(lib)
        pending = asyncio.ensure_future(reactor.step())
        await asyncio.sleep(0)
        reactor.close()
        with pytest.raises(AbortedError):
            await asyncio.wait_for(pending, timeout=1)

    async def test_clear_step_inputs_reaches_the_ffi(self, lib: FakeStepLib) -> None:
        reactor = await _stepped(lib)
        reactor.clear_step_inputs()
        assert lib.cleared == 1

    def test_clear_step_inputs_without_a_handle_is_a_noop(self) -> None:
        Reactor("m", generation="stepped").clear_step_inputs()

    def test_clear_step_inputs_refuses_a_streamed_client(self) -> None:
        with pytest.raises(InvalidStateError, match="stepped") as raised:
            Reactor("m").clear_step_inputs()
        assert raised.value.operation == "clear_step_inputs"

    async def test_state_must_be_a_mapping(self, lib: FakeStepLib) -> None:
        reactor = await _stepped(lib)
        with pytest.raises(TypeError, match="state"):
            await reactor.step(state=["prompt"])  # type: ignore[arg-type]


# ── commands and results ───────────────────────────────────────────────────


class TestWireCommands:
    @pytest.mark.parametrize(
        ("commands", "error", "match"),
        [
            ("advance", TypeError, "sequence"),
            ({"command": "x"}, TypeError, "sequence"),
            ([{"data": {}}], ValueError, '"command"'),
            ([{"command": "x", "args": {}}], ValueError, "unknown keys"),
            ([StepCommand("")], ValueError, "non-empty"),
            ([StepCommand("x", [1])], TypeError, "mapping"),  # type: ignore[arg-type]
            ([42], TypeError, "StepCommand"),
        ],
    )
    def test_malformed_commands_are_refused(self, commands: Any, error: type, match: str) -> None:
        with pytest.raises(error, match=match):
            _wire_commands(commands)


class TestStepResult:
    def test_track_by_name_names_what_did_produce_output(self) -> None:
        result = StepResult(step=2, tracks=(StepTrackOutput("a", TrackKind.VIDEO),))
        assert result.track("a").name == "a"
        with pytest.raises(KeyError, match="'a'"):
            result.track("b")

    def test_the_array_is_a_read_only_view(self) -> None:
        frame = StepFrame(data=bytes(6), dtype="uint8", shape=(1, 2, 3))
        assert frame.array.shape == (1, 2, 3)
        assert not frame.array.flags.writeable

    def test_files_and_error_default_to_none_kept_and_no_failure(self) -> None:
        result = StepResult(step=1)
        assert result.files == ()
        assert result.error is None

    def test_a_track_rate_defaults_to_unspecified(self) -> None:
        assert StepTrackOutput("a", TrackKind.AUDIO).rate == 0.0

    def test_an_unknown_dtype_is_not_size_checked(self) -> None:
        StepFrame(data=b"abc", dtype="bfloat16", shape=(9,))
