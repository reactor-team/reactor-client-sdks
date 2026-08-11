#!/usr/bin/env python3
"""
Pygame example application for the Reactor Python SDK.

This example demonstrates:
- Connecting to a Reactor model (local or remote)
- Displaying the video stream using pygame
- Using the dynamic ReactorController UI
- Sending commands based on model capabilities

Usage:
    # Local development
    python main.py --local

    # Remote with API key
    python main.py --api-key REACTOR_API_KEY --model your-model-name

Controls:
    - ESC: Quit
    - Mouse: Interact with controller UI
    - Scroll: Scroll controller panel
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import sys
import time
from typing import Any

import numpy as np
import pygame
from numpy.typing import NDArray

# Add parent directories to path for development
sys.path.insert(0, str(__file__).rsplit("/", 3)[0] + "/src")

from audio import AudioPlayer
from controller import ReactorController

from reactor_sdk import Reactor, ReactorStatus

# =============================================================================
# Configuration
# =============================================================================

WINDOW_WIDTH = 1280
WINDOW_HEIGHT = 720
VIDEO_WIDTH = 960  # Leave space for controller
CONTROLLER_WIDTH = WINDOW_WIDTH - VIDEO_WIDTH
FPS = 60

# Colors
BG_COLOR = (30, 30, 30)
STATUS_COLORS = {
    ReactorStatus.DISCONNECTED: (200, 60, 60),
    ReactorStatus.CONNECTING: (200, 200, 60),
    ReactorStatus.WAITING: (200, 200, 60),
    ReactorStatus.READY: (60, 200, 60),
}

# Logging
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
)
logger = logging.getLogger(__name__)

# Suppress noisy aiortc codec warnings (initial decode errors are normal in WebRTC)
logging.getLogger("aiortc.codecs.vpx").setLevel(logging.ERROR)
logging.getLogger("aiortc.codecs.h264").setLevel(logging.ERROR)
logging.getLogger("aioice.ice").setLevel(logging.WARNING)


#: Joining a session by id needs a coordinator that can look one up. The local runtime
#: cannot: the SDK caches the session it created in this process and there is no lookup
#: by id in the local protocol, so a second process gets "no cached local session".
#: Refusing up front beats surfacing that from inside the FFI.
LOCAL_JOIN_MESSAGE = (
    "--session-id cannot be combined with --local: the local runtime has no way to "
    "look up a session created by another process. Point both processes at a real "
    "coordinator to share a session, or drop --session-id to create one here."
)


# =============================================================================
# Application
# =============================================================================


class ReactorApp:
    """
    Pygame application for Reactor video streaming.

    Displays the video stream and provides a dynamic controller UI
    for interacting with the model.
    """

    def __init__(
        self,
        model_name: str,
        api_key: str | None = None,
        local: bool = False,
        api_url: str | None = None,
        session_id: str | None = None,
    ) -> None:
        """
        Initialize the application.

        Args:
            model_name: Name of the model to connect to.
            api_key: Reactor API key for authentication.
            local: If True, connect to local API.
            api_url: Custom Reactor API URL.
            session_id: Join this existing session instead of creating one.
        """
        self.model_name = model_name
        self.api_key = api_key
        self.local = local
        self.api_url = api_url
        self.session_id = session_id

        # State
        self.running = False
        self.current_frame: NDArray[np.uint8] | None = None
        self.frame_lock = asyncio.Lock()
        self.frames_received = 0
        self.frames_rendered = 0
        self.last_fps_time = 0.0

        # Pygame
        self.screen: pygame.Surface | None = None
        self.clock: pygame.time.Clock | None = None
        self.font: pygame.font.Font | None = None
        self.font_small: pygame.font.Font | None = None

        # Reactor
        self.reactor: Reactor | None = None
        self.controller: ReactorController | None = None

        # The SDK opens no audio device, so playing what arrives is up to us.
        self.audio = AudioPlayer()

        # Metadata from the most recent tagged frame, and how many have carried one.
        self.frame_meta: dict[str, Any] | None = None
        self.frames_tagged = 0

    async def run(self) -> None:
        """Run the application."""
        try:
            self._init_pygame()
            self._init_reactor()
            self.audio.start()
            await self._connect()
            await self._main_loop()
        except KeyboardInterrupt:
            logger.info("Interrupted by user")
        except Exception as e:
            logger.exception(f"Application error: {e}")
        finally:
            await self._cleanup()

    def _init_pygame(self) -> None:
        """Initialize pygame."""
        pygame.init()
        pygame.display.set_caption(f"Reactor - {self.model_name}")

        self.screen = pygame.display.set_mode((WINDOW_WIDTH, WINDOW_HEIGHT))
        self.clock = pygame.time.Clock()

        pygame.font.init()
        self.font = pygame.font.SysFont("monospace", 16, bold=True)
        self.font_small = pygame.font.SysFont("monospace", 12)

    def _init_reactor(self) -> None:
        """Initialize the Reactor instance."""
        self.reactor = Reactor(
            model_name=self.model_name,
            api_key=self.api_key,
            api_url=self.api_url or "https://api.reactor.inc",
            local=self.local,
        )

        # Set up event handlers using decorators
        @self.reactor.on_frame
        def handle_frame(frame: NDArray[np.uint8]) -> None:
            self.current_frame = frame
            self.frames_received += 1

        @self.reactor.on_status
        def handle_status(status: ReactorStatus) -> None:
            logger.info(f"Status changed: {status.value}")

        @self.reactor.on_error
        def handle_error(error: object) -> None:
            logger.error(f"Reactor error: {error}")

        # `on_frame` hands over the image as an array and nothing else, which is what
        # the renderer wants. The metadata trailer comes through `on("frame")`, the other
        # shape of the same event — so both are registered, each for the part it gives.
        # See examples/metadata_publisher.py for the process that attaches these.
        self.reactor.on("frame", self._note_frame_metadata)

        # Received audio, straight to the speaker. Registered with `on` rather than a
        # decorator because the payload is the raw PCM plus its format, and this
        # handler runs on the SDK's audio delivery thread — see audio.py.
        self.reactor.on(
            "audio",
            lambda pcm, _samples, rate, channels: self.audio.submit(pcm, rate, channels),
        )

        # Create controller
        self.controller = ReactorController(
            reactor=self.reactor,
            x=VIDEO_WIDTH,
            y=0,
            width=CONTROLLER_WIDTH,
            height=WINDOW_HEIGHT,
        )

    def _note_frame_metadata(
        self,
        _bgra: bytes,
        _width: int,
        _height: int,
        frame_id: int,
        timestamp_us: int,
        user_data: bytes,
    ) -> None:
        """Keep the tag from the newest frame, for the overlay to draw.

        Runs on the SDK's frame delivery thread, so it does the least it can: decode and
        store. Anything slower here costs frames, because the SDK keeps only the newest
        while this is running.

        A frame with no trailer arrives with everything zeroed and empty, which is normal
        — a sender that does not tag frames, or a peer that did not negotiate metadata.
        """
        if not user_data and not frame_id and not timestamp_us:
            return

        meta: dict[str, Any] = {"frame_id": frame_id, "timestamp_us": timestamp_us}
        if user_data:
            try:
                decoded = json.loads(user_data)
                meta["tag"] = decoded if isinstance(decoded, dict) else {"value": decoded}
            except (ValueError, UnicodeDecodeError):
                # The bytes are the sender's business and need not be JSON. Show
                # something rather than nothing.
                meta["tag"] = {"raw": user_data.decode(errors="replace")[:60]}

        self.frame_meta = meta
        self.frames_tagged += 1

    async def _connect(self) -> None:
        """Connect to the Reactor, joining an existing session when one was named."""
        if self.session_id:
            logger.info("Joining session %s...", self.session_id)
        else:
            logger.info("Connecting to Reactor...")
        try:
            await self.reactor.connect(session_id=self.session_id)
            logger.info("Connected to session %s", self.reactor.get_session_id())
        except Exception as e:
            logger.error(f"Failed to connect: {e}")
            raise

    async def _cleanup(self) -> None:
        """Clean up resources."""
        self.audio.stop()

        if self.reactor:
            await self.reactor.disconnect()

        pygame.quit()

    async def _main_loop(self) -> None:
        """Main application loop."""
        self.running = True
        frame_duration = 1.0 / FPS

        while self.running:
            loop_start = asyncio.get_event_loop().time()

            # Handle events (non-blocking)
            for event in pygame.event.get():
                if event.type == pygame.QUIT:
                    self.running = False
                elif event.type == pygame.KEYDOWN and event.key == pygame.K_ESCAPE:
                    self.running = False
                else:
                    if self.controller:
                        self.controller.handle_event(event)

            # Update
            if self.controller:
                self.controller.update()

            # Render
            self._render()

            # Update pygame display
            pygame.display.flip()

            # Async-friendly frame rate limiting
            # This yields to the event loop properly, allowing WebRTC frames to be processed
            elapsed = asyncio.get_event_loop().time() - loop_start
            sleep_time = max(0, frame_duration - elapsed)
            await asyncio.sleep(sleep_time)

    def _render(self) -> None:
        """Render the application."""
        if self.screen is None:
            return

        # Clear screen
        self.screen.fill(BG_COLOR)

        # Render video
        self._render_video()
        self._render_metadata()

        # Render controller
        if self.controller:
            self.controller.render(self.screen)

        # Render status bar
        self._render_status()

    def _render_video(self) -> None:
        """Render the video frame."""
        if self.screen is None:
            return

        video_rect = pygame.Rect(0, 0, VIDEO_WIDTH, WINDOW_HEIGHT)

        if self.current_frame is not None:
            try:
                # Convert numpy frame to pygame surface
                # Frame is RGB (H, W, 3), need to transpose for pygame (W, H, 3)
                frame = self.current_frame

                # Scale to fit video area while maintaining aspect ratio
                frame_h, frame_w = frame.shape[:2]
                scale_w = VIDEO_WIDTH / frame_w
                scale_h = WINDOW_HEIGHT / frame_h
                scale = min(scale_w, scale_h)

                new_w = int(frame_w * scale)
                new_h = int(frame_h * scale)

                # Create surface from frame
                # pygame.surfarray expects (width, height, channels)
                frame_transposed = np.transpose(frame, (1, 0, 2))
                surface = pygame.surfarray.make_surface(frame_transposed)

                # Scale surface
                surface = pygame.transform.scale(surface, (new_w, new_h))

                # Center in video area
                x = (VIDEO_WIDTH - new_w) // 2
                y = (WINDOW_HEIGHT - new_h) // 2

                self.screen.blit(surface, (x, y))

            except Exception as e:
                logger.error(f"Error rendering frame: {e}")
                self._render_no_video(video_rect)
        else:
            self._render_no_video(video_rect)

    def _render_metadata(self) -> None:
        """Draw the newest frame's metadata over the video.

        Nothing is drawn when no frame has carried a trailer, which keeps the untagged
        case looking like it did before rather than showing an empty box.
        """
        if self.screen is None or self.font_small is None or self.frame_meta is None:
            return

        meta = self.frame_meta
        lines = [f"frame_id {meta['frame_id']}  ·  tagged {self.frames_tagged}"]

        sent_us = meta["timestamp_us"]
        if sent_us:
            # The sender's clock, so this is only meaningful between machines whose time
            # agrees — which is why it is labelled as the trip rather than the latency.
            trip_ms = time.time() * 1000.0 - sent_us / 1000.0
            lines.append(f"trip {trip_ms:+.0f} ms")

        tag = meta.get("tag")
        if isinstance(tag, dict):
            lines += [f"{k}: {v}" for k, v in tag.items()]

        padding = 8
        rendered = [self.font_small.render(line, True, (230, 230, 230)) for line in lines]
        width = max(surface.get_width() for surface in rendered) + padding * 2
        height = sum(surface.get_height() for surface in rendered) + padding * 2

        # Sit below the status bar, top-left of the video area.
        box = pygame.Rect(10, 30, width, height)

        # Translucent backing, so the text stays readable over any frame content.
        backing = pygame.Surface(box.size, pygame.SRCALPHA)
        backing.fill((0, 0, 0, 160))
        self.screen.blit(backing, box.topleft)
        pygame.draw.rect(self.screen, (90, 90, 90), box, width=1)

        y = box.top + padding
        for surface in rendered:
            self.screen.blit(surface, (box.left + padding, y))
            y += surface.get_height()

    def _render_no_video(self, rect: pygame.Rect) -> None:
        """Render placeholder when no video is available."""
        if self.screen is None or self.font is None:
            return

        # Dark background
        pygame.draw.rect(self.screen, (20, 20, 20), rect)

        # Status text
        status = self.reactor.get_status() if self.reactor else ReactorStatus.DISCONNECTED
        if status == ReactorStatus.READY:
            text = "Waiting for video..."
        elif status == ReactorStatus.CONNECTING:
            text = "Connecting..."
        else:
            text = "Disconnected"

        text_surface = self.font.render(text, True, (150, 150, 150))
        text_rect = text_surface.get_rect(center=rect.center)
        self.screen.blit(text_surface, text_rect)

    def _render_status(self) -> None:
        """Render the status bar."""
        if self.screen is None or self.font_small is None or self.reactor is None:
            return

        current_time = time.time()

        status = self.reactor.get_status()
        session_id = self.reactor.get_session_id()

        # Status indicator
        status_color = STATUS_COLORS.get(status, (150, 150, 150))
        pygame.draw.circle(self.screen, status_color, (15, 15), 6)

        # Status text with frame stats
        status_text = f"{status.value.upper()}"
        if session_id:
            status_text += f" | Session: {session_id[:8]}..."

        # Calculate FPS every second
        if current_time - self.last_fps_time >= 1.0:
            self.last_fps_time = current_time
            self.frames_rendered = 0
            self.frames_received = 0

        status_text += f" | Frames: {self.frames_received}"
        self.frames_rendered += 1

        text_surface = self.font_small.render(status_text, True, (200, 200, 200))
        self.screen.blit(text_surface, (30, 8))


# =============================================================================
# Main
# =============================================================================


def parse_args() -> argparse.Namespace:
    """Parse command line arguments."""
    parser = argparse.ArgumentParser(
        description="Reactor pygame example application",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )

    parser.add_argument(
        "--model",
        "-m",
        default="hy-world",
        help="Name of the model to connect to (default: example-model)",
    )

    parser.add_argument(
        "--api-key",
        "-k",
        help="Reactor API key for authentication (required unless --local)",
    )

    parser.add_argument(
        "--local",
        "-l",
        action="store_true",
        help="Connect to local coordinator at localhost:8080",
    )

    parser.add_argument(
        "--api-url",
        "-c",
        help="Custom Reactor API URL",
    )

    parser.add_argument(
        "--session-id",
        "-s",
        metavar="ID",
        help="Join this existing session instead of creating a new one",
    )

    parser.add_argument(
        "--verbose",
        "-v",
        action="store_true",
        help="Enable verbose logging",
    )

    return parser.parse_args()


async def main() -> None:
    """Main entry point."""
    args = parse_args()

    if args.verbose:
        logging.getLogger().setLevel(logging.DEBUG)
        logging.getLogger("reactor_sdk").setLevel(logging.DEBUG)

    # Validate arguments
    if not args.local and not args.api_key:
        logger.error("Either --local or --api-key must be provided")
        sys.exit(1)

    if args.session_id and args.local:
        logger.error(LOCAL_JOIN_MESSAGE)
        sys.exit(1)

    # Create and run app
    app = ReactorApp(
        model_name=args.model,
        api_key=args.api_key,
        local=args.local,
        api_url=args.api_url,
        session_id=args.session_id,
    )

    await app.run()


if __name__ == "__main__":
    asyncio.run(main())
