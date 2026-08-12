"""
Minimal end-to-end example: connect, wait for ready, send a command, disconnect.

Run:
    REACTOR_MODEL=my-model REACTOR_JWT=<token> python examples/main.py
"""

from __future__ import annotations

import asyncio
import sys

sys.path.insert(0, ".")  # allow `from reactor_sdk import ...` from repo root

from examples.reactor_client import make_reactor
from reactor_sdk import ReactorError


async def main() -> None:
    reactor = make_reactor()

    ready = asyncio.Event()

    def on_status(status: str) -> None:
        print(f"[status] {status}")
        if status == "ready":
            ready.set()

    def on_error(err: ReactorError) -> None:
        print(f"[error] {err}")

    def on_message(msg: object) -> None:
        print(f"[message] {msg}")

    def on_track(name: str, mid: str | None) -> None:
        print(f"[track] name={name!r} mid={mid!r}")

    reactor.on("status_changed", on_status)
    reactor.on("error", on_error)
    reactor.on("message", on_message)
    reactor.on("track_received", on_track)

    print("Connecting …")
    await reactor.connect()

    print("Waiting for ready …")
    await asyncio.wait_for(ready.wait(), timeout=60)

    print("Sending hello command …")
    reactor.send_command("hello", {"text": "Hello from Python SDK!"})

    await asyncio.sleep(5)

    print("Disconnecting …")
    await reactor.disconnect()
    reactor.close()
    print("Done.")


if __name__ == "__main__":
    asyncio.run(main())
