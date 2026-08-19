"""Shared plumbing for the examples — and nothing more than that.

Three things live here, and the split is deliberate:

* **Options** — reading the model, the URL and the credentials from flags or the
  environment. Boilerplate, identical in every language.
* **The bootstrap** — the minimum command a model needs before it generates
  anything, the names of its tracks, plus the throwaway frames two examples push.
  Model trivia and test data: which command, which prompt, which track, which
  pixels.
* **The window** — `--show` puts the frames on screen. Every example receives
  video, and a frame counter proves that something arrived, not that it was the
  right something. Plumbing, and the same plumbing seven times over, so it lives
  here and each example spends two lines on it.

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
import asyncio
import os
import threading
import time
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

#: Track names, per model, from the same published schemas. A model declares
#: these; an app knows them the way it knows its command names, so the examples
#: ask for them by name rather than groping for "the one recvonly video track" —
#: which stops working the moment a model declares two.
#:
#: `reactor.tracks` lists what the session declared, if you would rather discover
#: them at runtime than write them down.
TRACKS: dict[str, dict[str, str]] = {
    "helios": {"output": "main_video"},
    # `camera` is what SANA reads the client stream from; `main_video` is the
    # edited result coming back.
    "sana-streaming": {"output": "main_video", "input": "camera"},
}

_FALLBACK_TRACKS = {"output": "main_video", "input": "camera"}


def track_name(model: str, direction: str) -> str:
    """The name of this model's `output` or `input` track."""
    return TRACKS.get(model, _FALLBACK_TRACKS).get(direction, _FALLBACK_TRACKS[direction])


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
    parser.add_argument(
        "--show",
        action="store_true",
        help="show the video in a window (needs pygame: pip install pygame)",
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


# ── the window ────────────────────────────────────────────────────────────────

#: Tiles are scaled down to this width. A model's output can be 1280 wide, and
#: two of those side by side do not fit on a laptop.
MAX_TILE_WIDTH = 640


class Display:
    """A window showing frames as they arrive. One tile per stream.

    Opt-in: `common.display()` returns a do-nothing stand-in unless `--show` was
    passed, so an example has one code path either way and needs pygame only when
    it is actually asked for a window.

    Frames are submitted from the SDK's delivery thread and drawn from the event
    loop, because that is the thread the window belongs to. Only the newest frame
    per tile is kept: a window that fell behind should show what is happening
    now, not work through a backlog.
    """

    def __init__(self, title: str, tiles: int = 1) -> None:
        import pygame  # imported here so no window means no dependency

        self._pygame = pygame
        self._title = title
        self._tiles = tiles
        self._lock = threading.Lock()
        self._latest: dict[int, tuple[bytes, int, int]] = {}
        self._screen = None
        self._box: tuple[int, int] | None = None
        self._closed = False
        self._drawn = 0
        self._last_caption = 0.0

    @property
    def closed(self) -> bool:
        """True once the window has been closed, so an example can stop early."""
        return self._closed

    def submit(self, data: bytes, width: int, height: int, tile: int = 0) -> None:
        with self._lock:
            self._latest[tile] = (data, width, height)

    async def hold(self, seconds: float) -> None:
        """Keep the session open for `seconds`, drawing while it lasts.

        Returns early if the window is closed — the same thing every media app
        does when its window goes away.
        """
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline and not self._closed:
            self._draw()
            await asyncio.sleep(1 / 60)

    def _draw(self) -> None:
        pygame = self._pygame
        with self._lock:
            frames = dict(self._latest)
        if not frames:
            return

        if self._screen is None:
            _, width, height = next(iter(frames.values()))
            scale = min(1.0, MAX_TILE_WIDTH / width)
            self._box = (int(width * scale), int(height * scale))
            pygame.init()
            pygame.display.set_caption(self._title)
            self._screen = pygame.display.set_mode((self._box[0] * self._tiles, self._box[1]))

        for event in pygame.event.get():
            if event.type == pygame.QUIT:
                self._closed = True
                pygame.quit()
                self._screen = None
                return

        box = self._box
        assert box is not None
        for tile, (data, width, height) in frames.items():
            surface = pygame.image.frombuffer(data, (width, height), "BGRA")
            if (width, height) != box:
                surface = pygame.transform.scale(surface, box)
            self._screen.blit(surface, (tile * box[0], 0))
        pygame.display.flip()

        self._drawn += 1
        now = time.monotonic()
        if now - self._last_caption >= 1.0:
            fps = self._drawn / (now - self._last_caption) if self._last_caption else 0.0
            if fps:
                pygame.display.set_caption(f"{self._title} — {fps:.0f} fps drawn")
            self._drawn = 0
            self._last_caption = now


class _NoDisplay:
    """What `display()` returns without `--show`: the same API, doing nothing."""

    closed = False

    def submit(self, data: bytes, width: int, height: int, tile: int = 0) -> None:
        pass

    async def hold(self, seconds: float) -> None:
        await asyncio.sleep(seconds)


def display(args: argparse.Namespace, title: str, tiles: int = 1) -> Display | _NoDisplay:
    """A window if `--show` was passed, a stand-in with the same API if not."""
    if not args.show:
        return _NoDisplay()
    try:
        return Display(title, tiles)
    except ImportError as exc:  # pragma: no cover - depends on the environment
        raise SystemExit("--show needs pygame: pip install pygame") from exc


def frame(seq: int, width: int, height: int) -> bytes:
    """A solid BGRA frame whose colour follows `seq`.

    Test data, not a lesson. The colour has to change frame to frame: an encoder
    fed identical frames sends almost nothing, and an example that pushes
    nothing looks exactly like an example that is broken.
    """
    pixel = bytes([seq * 7 % 256, seq * 13 % 256, seq * 29 % 256, 255])
    return pixel * (width * height)
