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
from .client import (
    DEFAULT_API_URL,
    LOCAL_API_URL,
    Clip,
    FileRef,
    Reactor,
    ReactorError,
    ReactorStatus,
)
from .errors import (
    AbortedError,
    BadRequestError,
    ConflictError,
    DecodeError,
    InvalidStateError,
    MessageTooLargeError,
    NetworkError,
    NotFoundError,
    PeerError,
    RateLimitedError,
    ReactorFFIError,
    RequestTimeoutError,
    ServerError,
    SessionTerminalError,
    UnauthorizedError,
    VersionMismatchError,
)
from .track import Track, TrackDirection, TrackKind

__all__ = [
    "Reactor",
    "ReactorStatus",
    "Track",
    "TrackKind",
    "TrackDirection",
    "Clip",
    "FileRef",
    "ReactorError",
    # Failures. `ReactorFFIError` is the base of every one below it, so catching
    # it still catches everything.
    "ReactorFFIError",
    "NetworkError",
    "UnauthorizedError",
    "NotFoundError",
    "ConflictError",
    "RateLimitedError",
    "BadRequestError",
    "ServerError",
    "VersionMismatchError",
    "DecodeError",
    "InvalidStateError",
    "SessionTerminalError",
    "MessageTooLargeError",
    "PeerError",
    "RequestTimeoutError",
    "AbortedError",
    "AuthError",
    "fetch_jwt",
    "DEFAULT_API_URL",
    "LOCAL_API_URL",
    "__version__",
]
