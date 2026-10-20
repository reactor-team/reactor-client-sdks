"""Cross-language contract tests against the real `libreactor_ffi`.

Skipped when no built library is present, so a plain `pytest` works on a fresh
checkout. Build one with `cargo build -p reactor-ffi --release`, or point
`REACTOR_FFI_LIB` at it.

These exercise the null-handle contract *through ctypes*, which is what makes them
worth more than their Rust counterparts in `crates/reactor-ffi/src/lib.rs`: they
check that `_ffi.py`'s `argtypes` and `restype` declarations actually match the
compiled ABI. ctypes verifies nothing against the header, so a signature that has
drifted is silent undefined behaviour — these tests are the only place it surfaces.
"""

from __future__ import annotations

import asyncio
import ctypes
import json
from pathlib import Path

import pytest

from reactor_sdk import _ffi


def _library_available() -> bool:
    """Resolve the library path without loading it.

    Deliberately not `get_lib()`: this runs at collection time, and loading here
    would populate the module-level cache before `test_client.py` gets to assert
    that constructing a `Reactor` leaves it untouched. `_find_lib` also returns
    `REACTOR_FFI_LIB` unverified, so the path is checked here.
    """
    try:
        return Path(_ffi._find_lib()).is_file()
    except FileNotFoundError:
        return False


pytestmark = pytest.mark.skipif(
    not _library_available(),
    reason="libreactor_ffi not built; run `cargo build -p reactor-ffi --release`",
)


def test_every_declared_function_resolves_in_the_library() -> None:
    """`_load()` assigns `argtypes` on each symbol, so an unresolvable name raises
    `AttributeError` here. This is the check that a function renamed in Rust cannot
    silently leave the ctypes bindings pointing at nothing.
    """
    lib = _ffi.get_lib()
    assert isinstance(lib, ctypes.CDLL)


def test_the_loaded_library_speaks_the_abi_these_bindings_declare() -> None:
    """The other half of the far-less-clever unit tests in `test_ffi_abi.py`:
    against the real library, the gate accepts and the two version constants
    agree.
    """
    lib = _ffi.get_lib()
    assert lib.reactor_abi_version() == _ffi.ABI_VERSION


def test_status_of_a_null_handle_is_disconnected() -> None:
    assert _ffi.get_lib().reactor_status(None) == b"disconnected"


def test_status_returns_a_stable_static_pointer() -> None:
    """Documented as a static literal that must never be freed. `restype` is
    `c_char_p`, so ctypes copies the bytes and the pointer itself stays valid.
    """
    lib = _ffi.get_lib()
    assert lib.reactor_status(None) == lib.reactor_status(None)


def test_session_id_of_a_null_handle_is_null() -> None:
    assert not _ffi.get_lib().reactor_session_id(None)


def test_track_getters_of_a_null_handle_are_null() -> None:
    lib = _ffi.get_lib()
    assert not lib.reactor_tracks(None)
    assert not lib.reactor_paused_tracks(None)


def test_the_media_callback_signatures_match_the_compiled_abi() -> None:
    """The one place the frame/audio ABI is checked end to end.

    Both callbacks gained a leading track name, and ctypes verifies no such thing:
    a `CFUNCTYPE` whose arguments have drifted from the Rust `extern "C" fn` is
    undefined behaviour that usually presents as a garbage pointer, not an error.
    Building each trampoline here at least pins the declared shapes in one readable
    place next to the header.
    """

    @_ffi.ON_FRAME_FN
    def on_frame(
        track: bytes | None,
        data: int,
        width: int,
        height: int,
        frame_id: int,
        timestamp_us: int,
        user_data: int,
        user_data_len: int,
        userdata: int,
    ) -> None: ...

    @_ffi.ON_AUDIO_FN
    def on_audio(
        track: bytes | None,
        samples: int,
        num_samples: int,
        sample_rate: int,
        channels: int,
        userdata: int,
    ) -> None: ...

    callbacks = _ffi.ReactorCallbacks(on_frame=on_frame, on_audio=on_audio)
    assert callbacks.on_frame and callbacks.on_audio


def test_send_command_on_a_null_handle_skips_the_completion() -> None:
    """The async entry points return early on a null handle *without* invoking the
    completion — see the matching Rust test in `crates/reactor-ffi/src/lib.rs`.
    """
    calls: list[int] = []

    @_ffi.COMPLETION_FN
    def completion(ok: int, _result: bytes | None, _error: bytes | None, _userdata: int) -> None:
        calls.append(ok)

    _ffi.get_lib().reactor_send_command(None, b"hello", b"{}", None, completion, None)

    assert calls == []


def test_unpublish_track_on_a_null_handle_reports_failure() -> None:
    lib = _ffi.get_lib()
    ptr = lib.reactor_unpublish_track(None, b"video")
    assert ptr is not None
    try:
        payload = json.loads(ctypes.cast(ptr, ctypes.c_char_p).value)
        assert payload["code"] == "INVALID_STATE"
    finally:
        lib.reactor_free_string(ptr)


def test_destroy_and_free_string_accept_null() -> None:
    lib = _ffi.get_lib()
    lib.reactor_destroy(None)
    lib.reactor_free_string(None)


def test_the_engine_clock_is_readable_and_moves_forward() -> None:
    """The clock `capture_time_us` is read in: no handle, and forward-moving — a
    stamp only means anything against the value the next frame gets. Exposed as
    `reactor_sdk.time_micros`, which is what a caller stamps its tracks from.
    """
    from reactor_sdk import time_micros

    first = time_micros()
    assert isinstance(first, int) and first > 0
    assert time_micros() >= first


def test_media_push_on_a_null_handle_is_a_noop() -> None:
    lib = _ffi.get_lib()
    pixels = (ctypes.c_uint8 * 4)()
    pcm = (ctypes.c_int16 * 2)()

    lib.reactor_push_video_frame(None, b"video", pixels, 1, 1)
    lib.reactor_push_video_frame_with_metadata(None, b"video", pixels, 1, 1, None, 0)
    lib.reactor_push_video_frame_with_metadata_at(
        None, b"video", pixels, 1, 1, None, 0, 1_700_000_000_000_000
    )
    assert lib.reactor_push_begin(None) == 0
    lib.reactor_push_video_frame_timed(None, b"video", pixels, 1, 1, None, 0, 0, 0, 0.5, 0)
    lib.reactor_push_audio_frame(None, b"audio", pcm, 2, 48000, 1)


def test_marking_a_delivery_outside_a_frame_callback_is_a_noop() -> None:
    _ffi.get_lib().reactor_frame_delivered()


# ── Stepped generation ──────────────────────────────────────────────────────


def test_the_library_exports_stepped_generation() -> None:
    assert _ffi.has_stepped(_ffi.get_lib())


def test_step_on_a_null_handle_skips_the_completion() -> None:
    calls: list[int] = []

    @_ffi.STEP_COMPLETION_FN
    def completion(ok: int, _result: int | None, _error: bytes | None, _userdata: int) -> None:
        calls.append(ok)

    lib = _ffi.get_lib()
    lib.reactor_step(None, b'{"prompt":"x"}', b"[]", completion, None)
    lib.reactor_clear_step_inputs(None)

    assert calls == []


def test_step_result_accessors_accept_a_null_result() -> None:
    lib = _ffi.get_lib()
    ndim = ctypes.c_uint32(7)
    length = ctypes.c_size_t(7)
    captured = ctypes.c_int64(7)
    assert lib.reactor_step_result_step(None) == 0
    assert lib.reactor_step_result_elapsed(None) == 0.0
    assert lib.reactor_step_result_error(None) is None
    assert lib.reactor_step_result_file_count(None) == 0
    assert lib.reactor_step_result_file_name(None, 0) is None
    assert lib.reactor_step_result_file_content_type(None, 0) is None
    assert lib.reactor_step_result_file_size(None, 0) == 0
    assert lib.reactor_step_result_track_count(None) == 0
    assert lib.reactor_step_result_track_name(None, 0) is None
    assert lib.reactor_step_result_track_kind(None, 0) is None
    assert lib.reactor_step_result_track_rate(None, 0) == 0.0
    assert lib.reactor_step_result_frame_count(None, 0) == 0
    assert lib.reactor_step_result_frame_encoding(None, 0, 0) is None
    assert lib.reactor_step_result_frame_dtype(None, 0, 0) is None
    assert not lib.reactor_step_result_frame_shape(None, 0, 0, ctypes.byref(ndim))
    assert ndim.value == 0
    assert lib.reactor_step_result_frame_data(None, 0, 0, ctypes.byref(length)) is None
    assert length.value == 0
    assert lib.reactor_step_result_frame_metadata(None, 0, 0, ctypes.byref(length)) is None
    assert lib.reactor_step_result_frame_capture_time_us(None, 0, 0, ctypes.byref(captured)) == 0
    assert captured.value == 7
    lib.reactor_step_result_free(None)


def test_create_with_options_refuses_an_unknown_key_through_error_out() -> None:
    lib = _ffi.get_lib()
    error = ctypes.c_void_p()
    handle = lib.reactor_create_with_options(
        b"http://localhost:1",
        b"owner/model",
        None,
        None,
        b'{"generaton":"stepped"}',
        ctypes.byref(error),
    )
    assert not handle
    assert error.value
    try:
        payload = json.loads(ctypes.cast(error.value, ctypes.c_char_p).value)
        assert payload["code"] == "INVALID_STATE"
        assert payload["operation"] == "create"
    finally:
        lib.reactor_free_string(error)


async def test_a_stepped_step_before_connecting_fails_through_its_completion() -> None:
    """Create a real stepped handle and step it unconnected: the completion
    answers, typed, on the library's own thread."""
    lib = _ffi.get_lib()
    loop = asyncio.get_running_loop()
    done: asyncio.Future = loop.create_future()

    @_ffi.STEP_COMPLETION_FN
    def completion(ok: int, result: int | None, error: bytes | None, _userdata: int) -> None:
        loop.call_soon_threadsafe(done.set_result, (ok, result, error))

    handle = lib.reactor_create_with_options(
        b"http://localhost:1", b"owner/model", None, None, b'{"generation":"stepped"}', None
    )
    assert handle
    try:
        lib.reactor_step(ctypes.c_void_p(handle), None, None, completion, None)
        ok, result, error = await asyncio.wait_for(done, timeout=5)
    finally:
        assert lib.reactor_destroy(ctypes.c_void_p(handle)) == 0
    assert ok == 0 and not result
    assert json.loads(error)["code"] == "INVALID_STATE"
