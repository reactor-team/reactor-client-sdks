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
`02_upload_image.py` spells out its own upload and conditioning for that reason,
rather than calling a helper that would hide both.

Docs:
  Authentication (API keys, session-scoped tokens)
      https://docs.reactor.inc/authentication
  Commands and messages
      https://docs.reactor.inc/concepts/commands-and-messages
  Model API reference — every model's tracks, commands and messages
      https://docs.reactor.inc/model-api-reference/overview
  Python SDK reference
      https://docs.reactor.inc/sdk-reference/python/reactor
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
EDIT_PROMPT = "make it look like a watercolour painting"

#: What each model needs before it produces anything — in order, because for
#: some of them it is more than one command. Taken from the models' own
#: manifests (`reactor.yaml`, `model_behaviour.md` in reactor-models), which is
#: where to check when an example connects and no frame ever arrives.
#:
#: Anything not listed falls back to `set_prompt` alone, the common case. A
#: model's published schema says which commands it takes and in what order they
#: are valid — Helios' is at
#: https://docs.reactor.inc/model-api-reference/helios/schema
BOOTSTRAP: dict[str, list[tuple[str, dict[str, Any]]]] = {
    # Helios stays in WAITING and emits nothing until `start`, and `start`
    # refuses without a prompt — so the order here is the contract, not a
    # preference.
    "helios": [("set_prompt", {"prompt": PROMPT}), ("start", {})],
    # sana-streaming edits the live `camera` track rather than generating from
    # scratch, so its prompt is an edit instruction and optional — with none set
    # the output stays close to the input. `start` is not optional.
    "sana-streaming": [("set_prompt", {"prompt": EDIT_PROMPT}), ("start", {})],
}

_FALLBACK: list[tuple[str, dict[str, Any]]] = [("set_prompt", {"prompt": PROMPT})]


def parse(
    description: str | None,
    add: Callable[[argparse.ArgumentParser], None] | None = None,
    default_model: str = DEFAULT_MODEL,
) -> argparse.Namespace:
    """Options from flags, then the environment, then the example's own default.

    `add` receives the parser so an example can declare its own flags.
    `default_model` is for the examples that need a particular kind of model —
    one with an input track, say — rather than any model at all.
    """
    parser = argparse.ArgumentParser(
        description=description,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--model",
        default=os.environ.get("REACTOR_MODEL", default_model),
        help=f"model to connect to (env REACTOR_MODEL, default {default_model})",
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


async def bootstrap(reactor: Reactor, model: str) -> None:
    """Give this model the minimum it needs before it generates anything.

    `send_command` correlates each command with its reply and returns it, or
    `None` when the handler acknowledged the command without answering — which
    is the usual case, since most models answer with a message instead. Register
    `on_message` to see those.
    """
    for command, data in BOOTSTRAP.get(model, _FALLBACK):
        reply = await reactor.send_command(command, data)
        print(f"bootstrap: {command} {data} -> {reply}")


def frame(seq: int, width: int, height: int) -> bytes:
    """A solid BGRA frame whose colour follows `seq`.

    Test data, not a lesson. The colour has to change frame to frame: an encoder
    fed identical frames sends almost nothing, and an example that pushes
    nothing looks exactly like an example that is broken.
    """
    pixel = bytes([seq * 7 % 256, seq * 13 % 256, seq * 29 % 256, 255])
    return pixel * (width * height)
