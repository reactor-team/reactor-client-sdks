"""
Reactor Python SDK — ctypes wrapper over libreactor_ffi.

Example::

    import asyncio
    from reactor_sdk import Reactor

    async def main():
        async with Reactor("https://api.reactor.inc", "my-model", jwt="...") as r:
            r.on("message", lambda msg: print("msg:", msg))
            await r.connect()
            await r.send_command("hello", {"text": "hi"})
            await asyncio.sleep(10)

    asyncio.run(main())
"""

from importlib.metadata import PackageNotFoundError, version

try:
    __version__: str = version("reactor-sdk")
except PackageNotFoundError:
    __version__ = "0.0.0.dev"

from ._auth import AuthError, fetch_jwt
from ._recording import download_clip
from .client import (
    DEFAULT_API_URL,
    LOCAL_API_URL,
    Clip,
    FileRef,
    Reactor,
    ReactorStatus,
)
from .errors import (
    AbortedError,
    BadRequestError,
    ConflictError,
    DecodeError,
    DisconnectedError,
    InvalidStateError,
    MessageTooLargeError,
    NetworkError,
    NotFoundError,
    RateLimitedError,
    ReactorError,
    RequestTimeoutError,
    ServerError,
    SessionTerminalError,
    TransportError,
    UnauthorizedError,
    VersionMismatchError,
)
from .track import Track, TrackDirection, TrackKind, TrackList

__all__ = [
    "Reactor",
    "ReactorStatus",
    "Track",
    "TrackKind",
    "TrackDirection",
    "TrackList",
    "Clip",
    "FileRef",
    # Failures, and the `on_error` event payload — the same class. `ReactorError`
    # is the base of every one below it, so catching it still catches everything.
    "ReactorError",
    "NetworkError",
    "UnauthorizedError",
    "NotFoundError",
    "DisconnectedError",
    "ConflictError",
    "RateLimitedError",
    "BadRequestError",
    "ServerError",
    "VersionMismatchError",
    "DecodeError",
    "InvalidStateError",
    "SessionTerminalError",
    "MessageTooLargeError",
    "TransportError",
    "RequestTimeoutError",
    "AbortedError",
    "AuthError",
    "fetch_jwt",
    "download_clip",
    "DEFAULT_API_URL",
    "LOCAL_API_URL",
    "__version__",
]
