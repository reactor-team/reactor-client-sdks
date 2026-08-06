"""
Reactor Python SDK — ctypes wrapper over libreactor_ffi.

Example::

    import asyncio
    from reactor import Reactor

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

from .client import Clip, FileRef, Reactor, ReactorError, ReactorFFIError

__all__ = [
    "Reactor",
    "Clip",
    "FileRef",
    "ReactorError",
    "ReactorFFIError",
    "__version__",
]
