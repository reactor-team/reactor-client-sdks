"""Shared plumbing for the examples — and nothing more than that.

Two things live here, and the split is deliberate:

* **Options** — reading the model, the URL and the credentials from flags or the
  environment. Boilerplate, identical in every language.
* **The bootstrap** — the minimum command a model needs before it generates
  anything, plus the throwaway frames two examples push. Model trivia and test
  data: which command, which prompt, which pixels.

SDK mechanics stay in the examples, even where that means repeating them.
Connecting, wiring events and tearing down are what a reader opened the file to
see, and a helper that hides them leaves six files that teach nothing.

One exception, because a rule with no exception would be a lie: when what a
model demands *is itself* an SDK capability, it belongs in the example.
`03_publish_track.py` uploads its own conditioning image for that reason.
"""

from __future__ import annotations

import argparse
import os
from collections.abc import Callable
from typing import Any

from reactor_sdk import DEFAULT_API_URL, Reactor

#: Text-to-video model used by every example that only needs video out.
DEFAULT_MODEL = "helios"

PROMPT = "a forest at dawn, sunbeams through the canopy"

#: The minimum command a model needs before it produces anything, by model name.
#: Anything not listed falls back to `set_prompt`, which is the common case.
BOOTSTRAP: dict[str, tuple[str, dict[str, Any]]] = {
    "helios": ("set_prompt", {"prompt": PROMPT}),
}

_FALLBACK: tuple[str, dict[str, Any]] = ("set_prompt", {"prompt": PROMPT})


def parse(
    description: str | None,
    add: Callable[[argparse.ArgumentParser], None] | None = None,
) -> argparse.Namespace:
    """Options from flags, defaulting to the environment.

    `add` receives the parser so an example can declare its own flags.
    """
    parser = argparse.ArgumentParser(
        description=description,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--model",
        default=os.environ.get("REACTOR_MODEL", DEFAULT_MODEL),
        help=f"model to connect to (env REACTOR_MODEL, default {DEFAULT_MODEL})",
    )
    parser.add_argument(
        "--api-url",
        default=os.environ.get("REACTOR_API_URL", DEFAULT_API_URL),
        help="coordinator base URL (env REACTOR_API_URL)",
    )
    parser.add_argument(
        "--api-key",
        default=os.environ.get("REACTOR_API_KEY"),
        help="API key, exchanged for a session-scoped token (env REACTOR_API_KEY)",
    )
    parser.add_argument(
        "--jwt",
        default=os.environ.get("REACTOR_JWT"),
        help="a token to use as-is, instead of an API key (env REACTOR_JWT)",
    )
    parser.add_argument(
        "--local",
        action="store_true",
        default=os.environ.get("REACTOR_LOCAL") == "1",
        help="talk to a local runtime instead of the cloud (env REACTOR_LOCAL=1)",
    )
    parser.add_argument(
        "--seconds",
        type=float,
        default=15.0,
        help="how long to keep the session open (default: 15)",
    )
    if add is not None:
        add(parser)
    args = parser.parse_args()
    if not args.local and not (args.api_key or args.jwt):
        parser.error("pass --api-key (or REACTOR_API_KEY), or --local for a local runtime")
    return args


async def bootstrap(reactor: Reactor, model: str) -> dict[str, Any] | None:
    """Send the one command this model needs before it generates anything.

    Returns the model's reply, which `send_command` correlates for you — `None`
    when the handler acknowledged the command without answering.
    """
    command, data = BOOTSTRAP.get(model, _FALLBACK)
    print(f"bootstrap: {command} {data}")
    return await reactor.send_command(command, data)


def frame(seq: int, width: int, height: int) -> bytes:
    """A solid BGRA frame whose colour follows `seq`.

    Test data, not a lesson. The colour has to change frame to frame: an encoder
    fed identical frames sends almost nothing, and an example that pushes
    nothing looks exactly like an example that is broken.
    """
    pixel = bytes([seq * 7 % 256, seq * 13 % 256, seq * 29 % 256, 255])
    return pixel * (width * height)
