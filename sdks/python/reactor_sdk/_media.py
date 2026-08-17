"""Frame conversions and handler introspection, shared by `client` and `track`.

Split out of `client` so that `track` can use it without importing `client`, which
imports `track` — the same helpers, in the one place both sides can reach.
"""

from __future__ import annotations

import inspect
from collections.abc import Callable
from typing import Any


def _numpy() -> Any:
    """Import numpy, or explain why it is needed.

    Imported on use rather than at module scope so it stays optional: only the
    array-shaped paths need it, and the rest of the SDK has no dependencies.
    """
    try:
        import numpy as np
    except ModuleNotFoundError as exc:  # pragma: no cover - depends on the environment
        raise ModuleNotFoundError(
            "this path delivers or accepts numpy arrays, and numpy is not installed. "
            'Install it, or use the raw-bytes forms — on("frame", ...) / '
            'on("audio", ...) to receive, and bytes with explicit dimensions to send.'
        ) from exc
    return np


def _positional_arity(func: Callable, maximum: int) -> int:
    """How many of `maximum` positional arguments `func` is willing to take.

    Decided once, when the handler is registered, rather than per frame.

    Falls back to one on anything it cannot read — a builtin, a C function, an exotic
    callable. One is the shape `on_frame` has always had, so the fallback is the
    compatible direction rather than a guess.
    """
    try:
        parameters = inspect.signature(func).parameters.values()
    except (TypeError, ValueError):
        return 1

    count = 0
    for parameter in parameters:
        if parameter.kind is inspect.Parameter.VAR_POSITIONAL:
            # *args takes whatever there is.
            return maximum
        if parameter.kind in (
            inspect.Parameter.POSITIONAL_ONLY,
            inspect.Parameter.POSITIONAL_OR_KEYWORD,
        ):
            count += 1

    # Never zero: a handler that takes nothing is a mistake worth surfacing as the
    # TypeError it is, rather than silently never being given the frame.
    return max(1, min(count, maximum))


def _bgra_to_rgb_array(bgra: bytes, width: int, height: int) -> Any:
    """Turn a BGRA frame into an RGB ``numpy`` array of shape ``(height, width, 3)``.

    Two conversions in one: drop the alpha channel, and reverse BGR to RGB. The slice
    ``[..., 2::-1]`` does both — it walks the first three bytes backwards, so B, G, R, A
    becomes R, G, B.
    """
    np = _numpy()
    frame = np.frombuffer(bgra, dtype=np.uint8).reshape(height, width, 4)
    return frame[..., 2::-1]


def _rgb_array_to_bgra(frame: Any) -> bytes:
    """Turn an RGB ``(h, w, 3)`` array into BGRA bytes — the inverse of the above.

    The alpha channel the wire format requires is added opaque, since an RGB array
    carries none.
    """
    np = _numpy()
    height, width, _ = frame.shape
    bgra = np.empty((height, width, 4), dtype=np.uint8)
    bgra[..., :3] = frame[..., ::-1]  # R, G, B → B, G, R
    bgra[..., 3] = 255
    return bgra.tobytes()


def _pcm_to_array(pcm: bytes, num_samples: int, num_channels: int) -> Any:
    """Turn interleaved i16 PCM into a ``numpy`` array of ``(samples, channels)``.

    Two-dimensional even when mono, so a handler's indexing does not have to change
    with the channel count.
    """
    np = _numpy()
    samples = np.frombuffer(pcm, dtype=np.int16, count=num_samples)
    channels = max(1, num_channels)
    return samples.reshape(-1, channels)


def _is_array(data: Any) -> bool:
    """Whether `data` is array-shaped rather than a buffer of bytes.

    Duck-typed on ``.shape`` and ``.dtype`` rather than on ``isinstance``, so numpy
    never has to be imported to answer the question — and so anything array-like
    enough (a torch tensor's ``.numpy()``, a masked array) works too.
    """
    return hasattr(data, "shape") and hasattr(data, "dtype")
