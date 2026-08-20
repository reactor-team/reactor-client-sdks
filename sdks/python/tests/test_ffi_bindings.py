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
    lib.reactor_push_audio_frame(None, b"audio", pcm, 2, 48000, 1)
