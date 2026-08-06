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


def _load() -> ctypes.CDLL:
    lib = ctypes.CDLL(_find_lib())

    # void*-returning functions need explicit restype
    lib.reactor_create.restype = ctypes.c_void_p
    lib.reactor_create.argtypes = [
        ctypes.c_char_p,   # api_url
        ctypes.c_char_p,   # model_name
        ctypes.c_char_p,   # jwt (nullable)
        ctypes.c_int,      # local
        ctypes.c_void_p,   # callbacks (nullable)
    ]

    lib.reactor_create_with_adm.restype = ctypes.c_void_p
    lib.reactor_create_with_adm.argtypes = [
        ctypes.c_char_p,
        ctypes.c_char_p,
        ctypes.c_char_p,
        ctypes.c_int,
        ctypes.c_void_p,
        ctypes.c_int,      # adm_mode
    ]

    lib.reactor_destroy.restype = None
    lib.reactor_destroy.argtypes = [ctypes.c_void_p]

    lib.reactor_connect.restype = None
    lib.reactor_connect.argtypes = [
        ctypes.c_void_p,   # handle
        ctypes.c_char_p,   # session_id (nullable)
        ctypes.c_void_p,   # completion (nullable)
        ctypes.c_void_p,   # userdata
    ]

    lib.reactor_disconnect.restype = None
    lib.reactor_disconnect.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p]

    lib.reactor_reconnect.restype = None
    lib.reactor_reconnect.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p]

    lib.reactor_publish_track.restype = None
    lib.reactor_publish_track.argtypes = [
        ctypes.c_void_p, ctypes.c_char_p, ctypes.c_void_p, ctypes.c_void_p
    ]

    lib.reactor_pause_track.restype = None
    lib.reactor_pause_track.argtypes = [
        ctypes.c_void_p, ctypes.c_char_p, ctypes.c_void_p, ctypes.c_void_p
    ]

    lib.reactor_resume_track.restype = None
    lib.reactor_resume_track.argtypes = [
        ctypes.c_void_p, ctypes.c_char_p, ctypes.c_void_p, ctypes.c_void_p
    ]

    lib.reactor_request_clip.restype = None
    lib.reactor_request_clip.argtypes = [
        ctypes.c_void_p, ctypes.c_double, ctypes.c_void_p, ctypes.c_void_p
    ]

    lib.reactor_request_recording.restype = None
    lib.reactor_request_recording.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p]

    lib.reactor_upload_file.restype = None
    lib.reactor_upload_file.argtypes = [
        ctypes.c_void_p, ctypes.c_char_p, ctypes.c_void_p, ctypes.c_void_p
    ]

    lib.reactor_send_command.restype = ctypes.c_int
    lib.reactor_send_command.argtypes = [
        ctypes.c_void_p, ctypes.c_char_p, ctypes.c_char_p
    ]

    lib.reactor_send_runtime_command.restype = ctypes.c_int
    lib.reactor_send_runtime_command.argtypes = [
        ctypes.c_void_p, ctypes.c_char_p, ctypes.c_char_p
    ]

    lib.reactor_unpublish_track.restype = ctypes.c_int
    lib.reactor_unpublish_track.argtypes = [ctypes.c_void_p, ctypes.c_char_p]

    lib.reactor_status.restype = ctypes.c_char_p
    lib.reactor_status.argtypes = [ctypes.c_void_p]

    lib.reactor_session_id.restype = ctypes.c_void_p
    lib.reactor_session_id.argtypes = [ctypes.c_void_p]

    lib.reactor_free_string.restype = None
    lib.reactor_free_string.argtypes = [ctypes.c_void_p]

    lib.reactor_push_video_frame.restype = None
    lib.reactor_push_video_frame.argtypes = [
        ctypes.c_void_p,    # handle
        ctypes.c_char_p,    # track_name
        ctypes.c_void_p,    # data
        ctypes.c_uint32,    # width
        ctypes.c_uint32,    # height
    ]

    lib.reactor_push_audio_frame.restype = None
    lib.reactor_push_audio_frame.argtypes = [
        ctypes.c_void_p,    # handle
        ctypes.c_char_p,    # track_name
        ctypes.c_void_p,    # data
        ctypes.c_uint32,    # samples_per_channel
        ctypes.c_uint32,    # sample_rate
        ctypes.c_uint32,    # num_channels
    ]

    return lib


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

# (const uint8_t*, uint32_t, uint32_t, void*) -> void  [on_frame]
ON_FRAME_FN = ctypes.CFUNCTYPE(
    None, ctypes.c_void_p, ctypes.c_uint32, ctypes.c_uint32, ctypes.c_void_p
)

# (const int16_t*, uint32_t, uint32_t, uint32_t, void*) -> void  [on_audio]
ON_AUDIO_FN = ctypes.CFUNCTYPE(
    None,
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


class ReactorCallbacks(ctypes.Structure):
    _fields_ = [
        ("on_status",           ON_STRING_FN),
        ("on_error",            ON_STRING_FN),
        ("on_message",          ON_STRING_FN),
        ("on_runtime_message",  ON_STRING_FN),
        ("on_track",            ON_TRACK_FN),
        ("on_capabilities",     ON_STRING_FN),
        ("on_session_id",       ON_STRING_FN),
        ("on_frame",            ON_FRAME_FN),
        ("on_audio",            ON_AUDIO_FN),
        ("userdata",            ctypes.c_void_p),
    ]
