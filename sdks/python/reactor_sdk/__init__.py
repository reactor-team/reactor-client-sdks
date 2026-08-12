"""
Reactor Python SDK — ctypes wrapper over libreactor_ffi.

Example::

    import asyncio
    from reactor_sdk import Reactor

    async def main():
        async with Reactor("https://api.reactor.inc", "my-model", jwt="...") as r:
            r.on("message", lambda msg: print("msg:", msg))
            await r.connect()
            r.send_command("hello", {"text": "hi"})
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
    CommandResult,
    FileRef,
    MessageScope,
    Reactor,
    ReactorError,
    ReactorFFIError,
    ReactorStatus,
)

__all__ = [
    "Reactor",
    "ReactorStatus",
    "MessageScope",
    "Clip",
    "FileRef",
    "CommandResult",
    "ReactorError",
    "ReactorFFIError",
    "AuthError",
    "fetch_jwt",
    "DEFAULT_API_URL",
    "LOCAL_API_URL",
    "__version__",
]
