"""ctypes bindings to libreactor_ffi.

The shared library is located by (in priority order):
1. ``REACTOR_FFI_LIB`` environment variable
2. ``libreactor_ffi.dylib`` / ``libreactor_ffi.so`` next to this file
3. ``<repo-root>/target/release/libreactor_ffi.<ext>``
"""

from __future__ import annotations

import ctypes
import os
import sys
from pathlib import Path

# ---------------------------------------------------------------------------
# Library loading
# ---------------------------------------------------------------------------

# The ABI these signatures describe, equal to REACTOR_ABI_VERSION in
# reactor_ffi.h — the Rust side keeps its own copy pinned to that header with a
# test, so a release that bumps the ABI bumps all three in one change.
ABI_VERSION = 4


class AbiMismatchError(RuntimeError):
    """The loaded libreactor_ffi speaks an ABI version this SDK cannot use.

    ctypes resolves every `reactor_` symbol by name, so a library one version
    behind loads cleanly and then reads these `argtypes` one position off — an
    older build takes the callbacks pointer where `auto_resume_tracks` sits now,
    which looks like a crash or a hang rather than a version error. Checking the
    version first is what makes it the latter.
    """


def _find_lib() -> str:
    if path := os.environ.get("REACTOR_FFI_LIB"):
        return path
    if sys.platform == "darwin":
        names = ["libreactor_ffi.dylib"]
    elif sys.platform == "win32":
        names = ["reactor_ffi.dll"]
    else:
        names = ["libreactor_ffi.so"]

    here = Path(__file__).parent
    for name in names:
        candidate = here / name
        if candidate.exists():
            return str(candidate)

    # Walk up to find the Cargo workspace root and look in target/release
    cursor = here
    for _ in range(8):
        release = cursor / "target" / "release" / names[0]
        if release.exists():
            return str(release)
        cursor = cursor.parent

    raise FileNotFoundError(
        f"libreactor_ffi not found.  Set REACTOR_FFI_LIB or copy the built "
        f"library next to {__file__}"
    )


def _check_abi_version(lib: ctypes.CDLL) -> None:
    """Refuse a library that does not speak ABI_VERSION, before a signature is used.

    Runs first in `_load()` so a stale library found through `REACTOR_FFI_LIB` or
    an old local build is rejected at load time, not at the first `reactor_create`
    call. The same gate exists in the Java and C++ bindings for the same reason.
    """
    try:
        abi_version = lib.reactor_abi_version
    except AttributeError:
        raise AbiMismatchError(
            f"reactor_sdk speaks reactor-ffi ABI version {ABI_VERSION}, and the "
            f"loaded library exports no reactor_abi_version — it predates it. "
            f"Rebuild it with `cargo build -p reactor-ffi --release`."
        ) from None
    abi_version.restype = ctypes.c_uint32
    abi_version.argtypes = []
    actual = abi_version()
    if actual != ABI_VERSION:
        raise AbiMismatchError(
            f"reactor_sdk speaks reactor-ffi ABI version {ABI_VERSION}, the loaded "
            f"library speaks {actual}. They are not compatible; rebuild it with "
            f"`cargo build -p reactor-ffi --release` or point REACTOR_FFI_LIB at "
            f"the rebuilt library."
        )


def _load() -> ctypes.CDLL:
    lib = ctypes.CDLL(_find_lib())
    _check_abi_version(lib)

    # void*-returning functions need explicit restype
    lib.reactor_create.restype = ctypes.c_void_p
    lib.reactor_create.argtypes = [
        ctypes.c_char_p,  # api_url
        ctypes.c_char_p,  # model_name
        ctypes.c_char_p,  # jwt (nullable)
        ctypes.c_int,  # local
        ctypes.c_int,  # auto_resume_tracks
        ctypes.c_void_p,  # callbacks (nullable)
        ctypes.c_char_p,  # sdk_version (nullable)
        ctypes.c_char_p,  # sdk_type (nullable)
        ctypes.c_char_p,  # extra_headers_json (nullable)
    ]

    lib.reactor_create_with_adm.restype = ctypes.c_void_p
    lib.reactor_create_with_adm.argtypes = [
        ctypes.c_char_p,
        ctypes.c_char_p,
        ctypes.c_char_p,
        ctypes.c_int,
        ctypes.c_int,  # auto_resume_tracks
        ctypes.c_void_p,
        ctypes.c_int,  # adm_mode
        ctypes.c_char_p,  # sdk_version (nullable)
        ctypes.c_char_p,  # sdk_type (nullable)
        ctypes.c_char_p,  # extra_headers_json (nullable)
    ]

    # 0 = quiesced, -1 = a callback is still running (see reactor_ffi.h).
    lib.reactor_destroy.restype = ctypes.c_int
    lib.reactor_destroy.argtypes = [ctypes.c_void_p]

    lib.reactor_connect.restype = None
    lib.reactor_connect.argtypes = [
        ctypes.c_void_p,  # handle
        ctypes.c_char_p,  # session_id (nullable)
        ctypes.POINTER(ctypes.c_uint32),  # connection_id (nullable)
        ctypes.c_void_p,  # completion (nullable)
        ctypes.c_void_p,  # userdata
    ]

    lib.reactor_disconnect.restype = None
    lib.reactor_disconnect.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p]

    lib.reactor_reconnect.restype = None
    lib.reactor_reconnect.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p]

    lib.reactor_publish_track.restype = None
    lib.reactor_publish_track.argtypes = [
        ctypes.c_void_p,
        ctypes.c_char_p,
        ctypes.c_void_p,
        ctypes.c_void_p,
    ]

    lib.reactor_pause_track.restype = None
    lib.reactor_pause_track.argtypes = [
        ctypes.c_void_p,
        ctypes.c_char_p,
        ctypes.c_void_p,
        ctypes.c_void_p,
    ]

    lib.reactor_resume_track.restype = None
    lib.reactor_resume_track.argtypes = [
        ctypes.c_void_p,
        ctypes.c_char_p,
        ctypes.c_void_p,
        ctypes.c_void_p,
    ]

    lib.reactor_set_bitrate.restype = None
    lib.reactor_set_bitrate.argtypes = [
        ctypes.c_void_p,
        ctypes.c_int32,
        ctypes.c_int32,
        ctypes.c_int32,
        ctypes.c_void_p,
        ctypes.c_void_p,
    ]

    lib.reactor_set_track_bitrate.restype = None
    lib.reactor_set_track_bitrate.argtypes = [
        ctypes.c_void_p,
        ctypes.c_char_p,
        ctypes.c_int32,
        ctypes.c_int32,
        ctypes.c_void_p,
        ctypes.c_void_p,
    ]

    lib.reactor_request_clip.restype = None
    lib.reactor_request_clip.argtypes = [
        ctypes.c_void_p,
        ctypes.c_double,
        ctypes.c_void_p,
        ctypes.c_void_p,
    ]

    lib.reactor_request_recording.restype = None
    lib.reactor_request_recording.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p]

    lib.reactor_request_schema.restype = None
    lib.reactor_request_schema.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p]

    lib.reactor_get_stats.restype = None
    lib.reactor_get_stats.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p]

    lib.reactor_send_command.restype = None
    lib.reactor_send_command.argtypes = [
        ctypes.c_void_p,  # handle
        ctypes.c_char_p,  # name
        ctypes.c_char_p,  # args_json
        ctypes.c_char_p,  # uploads_json
        ctypes.c_void_p,  # completion fn ptr
        ctypes.c_void_p,  # userdata
    ]

    lib.reactor_upload_file.restype = None
    lib.reactor_upload_file.argtypes = [
        ctypes.c_void_p,
        ctypes.c_char_p,
        ctypes.c_void_p,
        ctypes.c_void_p,
    ]

    lib.reactor_upload_bytes.restype = None
    lib.reactor_upload_bytes.argtypes = [
        ctypes.c_void_p,  # handle
        ctypes.c_void_p,  # data
        ctypes.c_size_t,  # len
        ctypes.c_char_p,  # name
        ctypes.c_char_p,  # mime_type
        ctypes.c_void_p,  # completion fn ptr
        ctypes.c_void_p,  # userdata
    ]

    lib.reactor_status.restype = ctypes.c_char_p
    lib.reactor_status.argtypes = [ctypes.c_void_p]

    lib.reactor_session_id.restype = ctypes.c_void_p
    lib.reactor_session_id.argtypes = [ctypes.c_void_p]

    # All three return heap JSON to free with reactor_free_string, hence
    # c_void_p rather than c_char_p — ctypes converts the latter to bytes and
    # loses the pointer needed to release it. reactor_unpublish_track returns
    # null on success and a heap error object on failure, the same convention.
    lib.reactor_tracks.restype = ctypes.c_void_p
    lib.reactor_tracks.argtypes = [ctypes.c_void_p]

    lib.reactor_unpublish_track.restype = ctypes.c_void_p
    lib.reactor_unpublish_track.argtypes = [ctypes.c_void_p, ctypes.c_char_p]

    lib.reactor_paused_tracks.restype = ctypes.c_void_p
    lib.reactor_paused_tracks.argtypes = [ctypes.c_void_p]

    lib.reactor_free_string.restype = None
    lib.reactor_free_string.argtypes = [ctypes.c_void_p]

    lib.reactor_push_video_frame.restype = None
    lib.reactor_push_video_frame.argtypes = [
        ctypes.c_void_p,  # handle
        ctypes.c_char_p,  # track_name
        ctypes.c_void_p,  # data
        ctypes.c_uint32,  # width
        ctypes.c_uint32,  # height
    ]

    lib.reactor_push_video_frame_with_metadata.restype = None
    lib.reactor_push_video_frame_with_metadata.argtypes = [
        ctypes.c_void_p,  # handle
        ctypes.c_char_p,  # track_name
        ctypes.c_void_p,  # data
        ctypes.c_uint32,  # width
        ctypes.c_uint32,  # height
        ctypes.c_void_p,  # user_data (NULL = send untagged)
        ctypes.c_uint32,  # user_data_len
    ]

    lib.reactor_time_micros.restype = ctypes.c_int64
    lib.reactor_time_micros.argtypes = []

    lib.reactor_push_video_frame_with_metadata_at.restype = None
    lib.reactor_push_video_frame_with_metadata_at.argtypes = [
        ctypes.c_void_p,  # handle
        ctypes.c_char_p,  # track_name
        ctypes.c_void_p,  # data
        ctypes.c_uint32,  # width
        ctypes.c_uint32,  # height
        ctypes.c_void_p,  # user_data (NULL = send untagged)
        ctypes.c_uint32,  # user_data_len
        ctypes.c_int64,  # capture_time_us
    ]

    lib.reactor_push_video_frame_timed.restype = None
    lib.reactor_push_video_frame_timed.argtypes = [
        ctypes.c_void_p,  # handle
        ctypes.c_char_p,  # track_name
        ctypes.c_void_p,  # data
        ctypes.c_uint32,  # width
        ctypes.c_uint32,  # height
        ctypes.c_void_p,  # user_data (NULL = send untagged)
        ctypes.c_uint32,  # user_data_len
        ctypes.c_int,  # has_capture_time
        ctypes.c_int64,  # capture_time_us
        ctypes.c_double,  # binding_ms
        ctypes.c_uint64,  # generation (reactor_push_begin)
    ]

    lib.reactor_push_begin.restype = ctypes.c_uint64
    lib.reactor_push_begin.argtypes = [ctypes.c_void_p]

    lib.reactor_frame_delivered.restype = None
    lib.reactor_frame_delivered.argtypes = []

    lib.reactor_push_audio_frame.restype = None
    lib.reactor_push_audio_frame.argtypes = [
        ctypes.c_void_p,  # handle
        ctypes.c_char_p,  # track_name
        ctypes.c_void_p,  # data
        ctypes.c_uint32,  # samples_per_channel
        ctypes.c_uint32,  # sample_rate
        ctypes.c_uint32,  # num_channels
    ]

    _bind_stepped(lib)
    _bind_encoded_step_frames(lib)
    return lib


#: The functions stepped generation added. They arrived without an ABI bump —
#: adding a function changes nothing an older binding calls — so a library
#: reporting `ABI_VERSION` may still predate them. Bound only when present;
#: `has_stepped()` is what a caller asks before using one. Requiring the
#: result's error and file accessors also turns away an early build of this
#: surface whose `reactor_create_with_options` took one more parameter.
STEPPED_FUNCTIONS = (
    "reactor_create_with_options",
    "reactor_step",
    "reactor_clear_step_inputs",
    "reactor_step_result_step",
    "reactor_step_result_elapsed",
    "reactor_step_result_error",
    "reactor_step_result_file_count",
    "reactor_step_result_file_name",
    "reactor_step_result_file_content_type",
    "reactor_step_result_file_size",
    "reactor_step_result_track_count",
    "reactor_step_result_track_name",
    "reactor_step_result_track_kind",
    "reactor_step_result_track_rate",
    "reactor_step_result_frame_count",
    "reactor_step_result_frame_encoding",
    "reactor_step_result_frame_dtype",
    "reactor_step_result_frame_shape",
    "reactor_step_result_frame_data",
    "reactor_step_result_frame_metadata",
    "reactor_step_result_frame_capture_time_us",
    "reactor_step_result_free",
)


#: Values of a step frame's `encoding`, the wire's FrameEncoding.
FRAME_ENCODING_H264 = 2

#: Encoded input frames for a step, added after stepped generation and, like
#: it, without an ABI bump. Bound only when present; `has_encoded_step_frames()`
#: is what a caller asks before pushing one.
ENCODED_STEP_FUNCTIONS = ("reactor_push_step_encoded_video_frame",)


def has_encoded_step_frames(lib: object) -> bool:
    """Whether `lib` can buffer an encoded video frame for a step."""
    return all(hasattr(lib, name) for name in ENCODED_STEP_FUNCTIONS)


def _bind_encoded_step_frames(lib: ctypes.CDLL) -> None:
    if not has_encoded_step_frames(lib):
        return
    lib.reactor_push_step_encoded_video_frame.restype = None
    lib.reactor_push_step_encoded_video_frame.argtypes = [
        ctypes.c_void_p,  # handle
        ctypes.c_char_p,  # track_name
        ctypes.c_int,  # encoding (FRAME_ENCODING_*)
        ctypes.c_void_p,  # data
        ctypes.c_size_t,  # data_len
        ctypes.c_uint32,  # width of the decoded frame
        ctypes.c_uint32,  # height of the decoded frame
        ctypes.c_void_p,  # user_data (NULL = untagged)
        ctypes.c_uint32,  # user_data_len
        ctypes.c_int,  # has_capture_time
        ctypes.c_int64,  # capture_time_us
    ]


def has_stepped(lib: object) -> bool:
    """Whether `lib` exports every stepped-generation function."""
    return all(hasattr(lib, name) for name in STEPPED_FUNCTIONS)


def _bind_stepped(lib: ctypes.CDLL) -> None:
    if not has_stepped(lib):
        return

    lib.reactor_create_with_options.restype = ctypes.c_void_p
    lib.reactor_create_with_options.argtypes = [
        ctypes.c_char_p,  # api_url
        ctypes.c_char_p,  # model_name
        ctypes.c_char_p,  # jwt (nullable)
        ctypes.c_void_p,  # callbacks (nullable)
        ctypes.c_char_p,  # options_json (nullable)
        ctypes.POINTER(ctypes.c_void_p),  # error_out (nullable): heap JSON to free
    ]

    lib.reactor_step.restype = None
    lib.reactor_step.argtypes = [
        ctypes.c_void_p,  # handle
        ctypes.c_char_p,  # state_json (nullable)
        ctypes.c_char_p,  # commands_json (nullable)
        ctypes.c_void_p,  # completion (STEP_COMPLETION_FN)
        ctypes.c_void_p,  # userdata
    ]

    lib.reactor_clear_step_inputs.restype = None
    lib.reactor_clear_step_inputs.argtypes = [ctypes.c_void_p]

    result = ctypes.c_void_p
    lib.reactor_step_result_step.restype = ctypes.c_uint64
    lib.reactor_step_result_step.argtypes = [result]
    lib.reactor_step_result_elapsed.restype = ctypes.c_double
    lib.reactor_step_result_elapsed.argtypes = [result]
    # c_char_p: every string accessor borrows from the result, so ctypes copying
    # the bytes out is exactly right, and nothing is freed through them.
    lib.reactor_step_result_error.restype = ctypes.c_char_p
    lib.reactor_step_result_error.argtypes = [result]
    lib.reactor_step_result_file_count.restype = ctypes.c_uint32
    lib.reactor_step_result_file_count.argtypes = [result]
    lib.reactor_step_result_file_name.restype = ctypes.c_char_p
    lib.reactor_step_result_file_name.argtypes = [result, ctypes.c_uint32]
    lib.reactor_step_result_file_content_type.restype = ctypes.c_char_p
    lib.reactor_step_result_file_content_type.argtypes = [result, ctypes.c_uint32]
    lib.reactor_step_result_file_size.restype = ctypes.c_uint64
    lib.reactor_step_result_file_size.argtypes = [result, ctypes.c_uint32]
    lib.reactor_step_result_track_count.restype = ctypes.c_uint32
    lib.reactor_step_result_track_count.argtypes = [result]
    lib.reactor_step_result_track_name.restype = ctypes.c_char_p
    lib.reactor_step_result_track_name.argtypes = [result, ctypes.c_uint32]
    lib.reactor_step_result_track_kind.restype = ctypes.c_char_p
    lib.reactor_step_result_track_kind.argtypes = [result, ctypes.c_uint32]
    lib.reactor_step_result_track_rate.restype = ctypes.c_double
    lib.reactor_step_result_track_rate.argtypes = [result, ctypes.c_uint32]
    lib.reactor_step_result_frame_count.restype = ctypes.c_uint32
    lib.reactor_step_result_frame_count.argtypes = [result, ctypes.c_uint32]

    frame = [result, ctypes.c_uint32, ctypes.c_uint32]
    lib.reactor_step_result_frame_encoding.restype = ctypes.c_char_p
    lib.reactor_step_result_frame_encoding.argtypes = frame
    lib.reactor_step_result_frame_dtype.restype = ctypes.c_char_p
    lib.reactor_step_result_frame_dtype.argtypes = frame
    lib.reactor_step_result_frame_shape.restype = ctypes.POINTER(ctypes.c_uint32)
    lib.reactor_step_result_frame_shape.argtypes = [*frame, ctypes.POINTER(ctypes.c_uint32)]
    lib.reactor_step_result_frame_data.restype = ctypes.c_void_p
    lib.reactor_step_result_frame_data.argtypes = [*frame, ctypes.POINTER(ctypes.c_size_t)]
    lib.reactor_step_result_frame_metadata.restype = ctypes.c_void_p
    lib.reactor_step_result_frame_metadata.argtypes = [*frame, ctypes.POINTER(ctypes.c_size_t)]
    lib.reactor_step_result_frame_capture_time_us.restype = ctypes.c_int
    lib.reactor_step_result_frame_capture_time_us.argtypes = [
        *frame,
        ctypes.POINTER(ctypes.c_int64),
    ]
    lib.reactor_step_result_free.restype = None
    lib.reactor_step_result_free.argtypes = [result]


# Lazy singleton
_lib: ctypes.CDLL | None = None


def get_lib() -> ctypes.CDLL:
    global _lib
    if _lib is None:
        _lib = _load()
    return _lib


# ---------------------------------------------------------------------------
# C callback types
# ---------------------------------------------------------------------------

# (const char*, void*) -> void
ON_STRING_FN = ctypes.CFUNCTYPE(None, ctypes.c_char_p, ctypes.c_void_p)

# (const char*, const char*, void*) -> void  [on_track]
ON_TRACK_FN = ctypes.CFUNCTYPE(None, ctypes.c_char_p, ctypes.c_char_p, ctypes.c_void_p)

# (track, data, width, height, frame_id, timestamp_us, user_data, user_data_len, userdata)
ON_FRAME_FN = ctypes.CFUNCTYPE(
    None,
    ctypes.c_char_p,  # track name ("" = unattributable, never NULL)
    ctypes.c_void_p,  # data (BGRA bytes)
    ctypes.c_uint32,  # width
    ctypes.c_uint32,  # height
    ctypes.c_uint64,  # frame_id   (0 = no metadata)
    ctypes.c_uint64,  # timestamp_us (0 = no metadata)
    ctypes.c_void_p,  # user_data  (NULL = no metadata)
    ctypes.c_uint32,  # user_data_len
    ctypes.c_void_p,  # userdata
)

# (const char*, const int16_t*, uint32_t, uint32_t, uint32_t, void*) -> void  [on_audio]
ON_AUDIO_FN = ctypes.CFUNCTYPE(
    None,
    ctypes.c_char_p,  # track name
    ctypes.c_void_p,
    ctypes.c_uint32,
    ctypes.c_uint32,
    ctypes.c_uint32,
    ctypes.c_void_p,
)

# (int, const char*, const char*, void*) -> void  [completion]
COMPLETION_FN = ctypes.CFUNCTYPE(
    None, ctypes.c_int, ctypes.c_char_p, ctypes.c_char_p, ctypes.c_void_p
)


# (int ok, ReactorStepResult* result, const char* error_json, void*) -> void.
# `result` is c_void_p, not a pointer type ctypes would wrap: it arrives owned,
# and has to be handed back to reactor_step_result_free as the same address.
STEP_COMPLETION_FN = ctypes.CFUNCTYPE(
    None, ctypes.c_int, ctypes.c_void_p, ctypes.c_char_p, ctypes.c_void_p
)


class ReactorCallbacks(ctypes.Structure):
    _fields_ = [
        ("on_status", ON_STRING_FN),
        ("on_error", ON_STRING_FN),
        ("on_message", ON_STRING_FN),
        ("on_runtime_message", ON_STRING_FN),
        ("on_track", ON_TRACK_FN),
        ("on_capabilities", ON_STRING_FN),
        ("on_session_id", ON_STRING_FN),
        ("on_frame", ON_FRAME_FN),
        ("on_audio", ON_AUDIO_FN),
        ("userdata", ctypes.c_void_p),
    ]
