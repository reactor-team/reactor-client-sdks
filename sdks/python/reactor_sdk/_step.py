"""Stepped generation: the commands a step takes and the result it answers with.

A session created with ``generation="stepped"`` does not stream. Each
:meth:`Reactor.step` sends the model's state, a list of commands and every input
frame pushed since the previous step, and resolves with what the model produced
on each output track — a :class:`StepResult`.

The result is copied out of the native library once, when the step completes,
and the native result is freed straight away: nothing here borrows from it.
"""

from __future__ import annotations

import ctypes
import math
from collections.abc import Mapping
from dataclasses import dataclass, field
from functools import cached_property
from typing import TYPE_CHECKING, Any

from ._media import _numpy
from .errors import DecodeError
from .track import TrackKind

if TYPE_CHECKING:  # pragma: no cover - types only
    import numpy as np

#: Element sizes of the dtypes a RAW frame can declare. A frame whose byte count
#: disagrees with its shape is refused when the result arrives, rather than when
#: someone first reads `array` — by then the step has already succeeded.
_DTYPE_SIZES = {
    "uint8": 1,
    "int8": 1,
    "uint16": 2,
    "int16": 2,
    "uint32": 4,
    "int32": 4,
    "float16": 2,
    "float32": 4,
    "float64": 8,
}


@dataclass(frozen=True)
class StepCommand:
    """One command of a step, run after the step's state, in order.

    ``data`` is the command's arguments as a JSON-serialisable mapping, or None
    for a command that takes none::

        StepCommand("set_prompt", {"prompt": "a red fox"})
    """

    command: str
    data: Mapping[str, Any] | None = None

    def _wire(self) -> dict[str, Any]:
        if not isinstance(self.command, str) or not self.command:
            raise ValueError(f"a step command needs a non-empty name, got {self.command!r}")
        if self.data is not None and not isinstance(self.data, Mapping):
            raise TypeError(
                f"the data of step command {self.command!r} must be a mapping or None, "
                f"got {type(self.data).__name__}"
            )
        return {"command": self.command, "data": None if self.data is None else dict(self.data)}


def _wire_commands(commands: Any) -> list[dict[str, Any]]:
    """Turn `commands` into the ``[{command, data}]`` list the C ABI takes.

    Each entry may be a :class:`StepCommand` or a mapping with ``command`` and an
    optional ``data`` — and nothing else, since a misspelt ``data`` would run the
    command with no arguments and report success.
    """
    if isinstance(commands, (str, bytes, Mapping)):
        raise TypeError("commands must be a sequence of StepCommand or {command, data} mappings")
    wired = []
    for i, entry in enumerate(commands):
        if isinstance(entry, StepCommand):
            wired.append(entry._wire())
        elif isinstance(entry, Mapping):
            unknown = set(entry) - {"command", "data"}
            if unknown:
                raise ValueError(
                    f"commands[{i}] has unknown keys {sorted(unknown)}; "
                    f'expected "command" and "data"'
                )
            if "command" not in entry:
                raise ValueError(f'commands[{i}] needs a "command"')
            wired.append(StepCommand(entry["command"], entry.get("data"))._wire())
        else:
            raise TypeError(
                f"commands[{i}] must be a StepCommand or a mapping, got {type(entry).__name__}"
            )
    return wired


@dataclass(frozen=True)
class StepFrame:
    """One frame a step produced on an output track.

    ``array`` is the frame as a ``numpy`` array — ``(height, width, 3)`` ``uint8``
    RGB for video, ``(channels, samples)`` ``int16`` PCM for audio — built from
    ``data``, ``dtype`` and ``shape``, the bytes and description the runtime sent.
    It is a read-only view of ``data``; ``.copy()`` it to write into it.
    """

    data: bytes
    dtype: str
    shape: tuple[int, ...]
    #: How the frame travelled on the wire — ``"raw"`` today. ``data`` is
    #: already decoded whatever this says.
    encoding: str = "raw"
    #: What the sender attached to the frame, or None when it attached nothing.
    metadata: bytes | None = None
    #: When the sender says the frame was captured, in microseconds, if it said.
    capture_time_us: int | None = None

    def __post_init__(self) -> None:
        size = _DTYPE_SIZES.get(self.dtype)
        if size is not None and len(self.data) != math.prod(self.shape) * size:
            raise DecodeError(
                f"a step frame of shape {self.shape} and dtype {self.dtype} needs "
                f"{math.prod(self.shape) * size} bytes, got {len(self.data)}",
                operation="step",
            )

    @cached_property
    def array(self) -> np.ndarray:
        np = _numpy()
        # Little-endian on the wire, whatever this machine is.
        dtype = np.dtype(self.dtype).newbyteorder("<")
        return np.frombuffer(self.data, dtype=dtype).reshape(self.shape)


@dataclass(frozen=True)
class StepTrackOutput:
    """The frames one output track produced in a step, in production order."""

    name: str
    kind: TrackKind
    frames: tuple[StepFrame, ...] = ()
    #: The rate the track's frames play at, as the runtime reported it: frames
    #: per second for video, samples per second for audio. 0.0 when the runtime
    #: did not say.
    rate: float = 0.0


@dataclass(frozen=True)
class StepFile:
    """An extra file the model kept with a step.

    The result names the file; it does not carry it. Fetch its bytes over HTTP
    from the session's step results, ``GET /sessions/{id}/steps/{index}``, which
    lists each file with a download URL (and answers 202 while the step's files
    are still being stored).
    """

    #: The file's name, one path segment.
    name: str
    #: The file's media type, guessed from its name.
    content_type: str
    #: The file's size in bytes.
    size: int


@dataclass(frozen=True)
class StepResult:
    """What one step produced: its index in the session and, per output track,
    the frames it generated::

        result = await reactor.step(state={"prompt": "a red fox"})
        if result.error is not None:
            print("the model failed the step:", result.error)
        for frame in result.track("main_video").frames:
            show(frame.array)
    """

    #: The step's number in the session, counted from 1: the ``step`` of the
    #: session's step results (``GET /sessions/{id}/steps/{step}``).
    step: int
    tracks: tuple[StepTrackOutput, ...] = field(default=())
    #: Seconds the step's ``generate()`` took, or the time the model reported
    #: for the step.
    elapsed: float = 0.0
    #: The extra files the model kept with the step, in the order the runtime
    #: listed them. Empty when it kept none or the result was not saved.
    files: tuple[StepFile, ...] = field(default=())
    #: Why the step failed, when it ran and the model reported a failure; None
    #: when it worked. A step that ran and failed still returns its result — only
    #: a refused one raises.
    error: str | None = None

    def track(self, name: str) -> StepTrackOutput:
        """The output of the track called `name`.

        Raises `KeyError` naming the tracks that did produce output: a model can
        legitimately produce nothing on a track in a step, and that is worth
        telling apart from a typo.
        """
        for track in self.tracks:
            if track.name == name:
                return track
        produced = ", ".join(repr(t.name) for t in self.tracks) or "none"
        raise KeyError(f"step {self.step} produced no output on {name!r} (tracks: {produced})")


def _read_step_result(lib: Any, ptr: int) -> StepResult:
    """Copy a native `ReactorStepResult` into a :class:`StepResult`.

    Does not free it: the caller does, whatever this raises.
    """
    handle = ctypes.c_void_p(ptr)
    tracks = []
    for t in range(lib.reactor_step_result_track_count(handle)):
        name = (lib.reactor_step_result_track_name(handle, t) or b"").decode()
        kind = TrackKind((lib.reactor_step_result_track_kind(handle, t) or b"").decode())
        frames = []
        for f in range(lib.reactor_step_result_frame_count(handle, t)):
            ndim = ctypes.c_uint32(0)
            shape_ptr = lib.reactor_step_result_frame_shape(handle, t, f, ctypes.byref(ndim))
            shape = tuple(shape_ptr[i] for i in range(ndim.value)) if shape_ptr else ()

            length = ctypes.c_size_t(0)
            data_ptr = lib.reactor_step_result_frame_data(handle, t, f, ctypes.byref(length))
            data = ctypes.string_at(data_ptr, length.value) if data_ptr and length.value else b""

            md_len = ctypes.c_size_t(0)
            md_ptr = lib.reactor_step_result_frame_metadata(handle, t, f, ctypes.byref(md_len))
            metadata = ctypes.string_at(md_ptr, md_len.value) if md_ptr else None

            captured = ctypes.c_int64(0)
            has_time = lib.reactor_step_result_frame_capture_time_us(
                handle, t, f, ctypes.byref(captured)
            )

            frames.append(
                StepFrame(
                    data=data,
                    dtype=(lib.reactor_step_result_frame_dtype(handle, t, f) or b"").decode(),
                    shape=shape,
                    encoding=(lib.reactor_step_result_frame_encoding(handle, t, f) or b"").decode(),
                    metadata=metadata,
                    capture_time_us=captured.value if has_time else None,
                )
            )
        tracks.append(
            StepTrackOutput(
                name=name,
                kind=kind,
                frames=tuple(frames),
                rate=float(lib.reactor_step_result_track_rate(handle, t)),
            )
        )
    files = tuple(
        StepFile(
            name=(lib.reactor_step_result_file_name(handle, i) or b"").decode(),
            content_type=(lib.reactor_step_result_file_content_type(handle, i) or b"").decode(),
            size=int(lib.reactor_step_result_file_size(handle, i)),
        )
        for i in range(lib.reactor_step_result_file_count(handle))
    )
    # c_char_p restype: ctypes copies the borrowed string out; nothing to free.
    error = lib.reactor_step_result_error(handle)
    return StepResult(
        step=int(lib.reactor_step_result_step(handle)),
        tracks=tuple(tracks),
        elapsed=float(lib.reactor_step_result_elapsed(handle)),
        files=files,
        error=None if error is None else error.decode(errors="replace"),
    )
