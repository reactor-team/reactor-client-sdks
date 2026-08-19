"""The `REACTOR_SHOW=1` window — the one thing worth sharing between examples.

Everything else lives in the examples themselves, spelled out: which command a
model needs, which track it emits on, which prompt to send. That is what a reader
opened the file for, and a helper that answers it is a helper that hides it.

This is the exception because it is the opposite kind of code: ~140 lines of
pygame that teach nothing about the SDK, identical seven times over, and needed
only by someone who asked to see the frames.
"""

from __future__ import annotations

import asyncio
import threading
import time

# ── the window ────────────────────────────────────────────────────────────────

#: Tiles scale down to this width — two 1280-wide outputs do not fit a laptop.
MAX_TILE_WIDTH = 640


class Display:
    """A window showing frames as they arrive. One tile per stream.

    Submitted from the SDK's delivery thread, drawn from the event loop — the
    thread the window belongs to. Only the newest frame per tile is kept.
    """

    def __init__(self, title: str, tiles: int = 1) -> None:
        import pygame  # here, so no window means no dependency

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
        """True once the window is closed, so an example can stop early."""
        return self._closed

    def submit(self, data: bytes, width: int, height: int, tile: int = 0) -> None:
        with self._lock:
            self._latest[tile] = (data, width, height)

    async def hold(self, seconds: float) -> None:
        """Sleep `seconds`, drawing while it lasts. Returns early if closed."""
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
    """What `window()` returns when the flag is off: the same API, doing nothing."""

    closed = False

    def submit(self, data: bytes, width: int, height: int, tile: int = 0) -> None:
        pass

    async def hold(self, seconds: float) -> None:
        await asyncio.sleep(seconds)


def window(title: str, tiles: int = 1, *, enabled: bool = True) -> Display | _NoDisplay:
    """A window when `enabled`, a same-API stand-in when not — so an example has
    one code path either way and pygame is needed only when asked for."""
    if not enabled:
        return _NoDisplay()
    try:
        return Display(title, tiles)
    except ImportError as exc:  # pragma: no cover - depends on the environment
        raise SystemExit("REACTOR_SHOW needs pygame: pip install pygame") from exc
