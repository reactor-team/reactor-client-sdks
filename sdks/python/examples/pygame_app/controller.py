# Copyright (c) 2026 Reactor Technologies, Inc. All rights reserved.

"""
ReactorController - Dynamic UI controller for pygame.

Builds UI controls dynamically from the model's command schema,
received via the data channel runtime message (modelCapabilities).
Mirrors the JS SDK's ReactorController.tsx.
"""

from __future__ import annotations

import asyncio
import logging
import time
from dataclasses import dataclass, field
from typing import Any

import pygame

from reactor import MessageScope, Reactor, ReactorStatus

logger = logging.getLogger(__name__)


COLORS = {
    "panel": (255, 255, 255),
    "border": (221, 221, 221),
    "text": (51, 51, 51),
    "text_light": (102, 102, 102),
    "text_muted": (136, 136, 136),
    "primary": (0, 123, 255),
    "slider_track": (200, 200, 200),
    "slider_fill": (0, 123, 255),
    "slider_thumb": (255, 255, 255),
    "checkbox_checked": (0, 123, 255),
    "input_bg": (255, 255, 255),
    "input_border": (204, 204, 204),
    "header_bg": (240, 240, 240),
    "expand_arrow": (153, 153, 153),
}


# =============================================================================
# UI Element Classes
# =============================================================================


@dataclass
class UIElement:
    rect: pygame.Rect
    param_name: str
    param_schema: dict[str, Any]
    value: Any = None


@dataclass
class SliderElement(UIElement):
    min_value: float = 0.0
    max_value: float = 1.0
    is_integer: bool = False
    dragging: bool = False


@dataclass
class TextInputElement(UIElement):
    text: str = ""
    focused: bool = False


@dataclass
class CheckboxElement(UIElement):
    checked: bool = False


@dataclass
class DropdownElement(UIElement):
    options: list[str] = field(default_factory=list)
    selected_index: int = 0


@dataclass
class CommandUI:
    name: str
    description: str
    schema: dict[str, Any]
    elements: list[UIElement] = field(default_factory=list)
    expanded: bool = False
    rect: pygame.Rect = field(default_factory=lambda: pygame.Rect(0, 0, 0, 0))
    header_rect: pygame.Rect = field(default_factory=lambda: pygame.Rect(0, 0, 0, 0))
    button_rect: pygame.Rect | None = None


# =============================================================================
# ReactorController
# =============================================================================


class ReactorController:
    """
    Dynamic UI controller that requests and receives command schemas
    via the data channel (runtime scope), then builds pygame controls.

    Flow (mirrors JS SDK ReactorController.tsx):
    1. On READY → send ``requestCapabilities`` on the runtime channel
    2. Listen for ``modelCapabilities`` runtime message with command list
    3. Build UI from the received command schemas
    4. Retry every 5s if capabilities haven't arrived
    """

    def __init__(
        self,
        reactor: Reactor,
        x: int,
        y: int,
        width: int,
        height: int,
    ) -> None:
        self.reactor = reactor
        self.rect = pygame.Rect(x, y, width, height)
        self.commands: dict[str, CommandUI] = {}
        self.scroll_offset = 0
        self.max_scroll = 0

        pygame.font.init()
        self.font = pygame.font.SysFont("monospace", 12)
        self.font_bold = pygame.font.SysFont("monospace", 12, bold=True)
        self.font_title = pygame.font.SysFont("monospace", 14, bold=True)
        self.font_desc = pygame.font.SysFont("monospace", 10)

        self._capabilities_received = False
        self._last_request_time = 0.0

        def on_runtime_message(message: Any) -> None:
            if (
                isinstance(message, dict)
                and message.get("type") == "modelCapabilities"
                and isinstance(message.get("data"), dict)
                and "commands" in message["data"]
            ):
                logger.debug("Received modelCapabilities from data channel")
                self._capabilities_received = True
                self._parse_commands(message["data"]["commands"])

        reactor.on("runtime_message", on_runtime_message)

        @reactor.on_status(ReactorStatus.DISCONNECTED)
        def on_disconnected(status: ReactorStatus) -> None:
            self.commands.clear()
            self._capabilities_received = False

        @reactor.on_status(ReactorStatus.READY)
        def on_ready(status: ReactorStatus) -> None:
            self._request_capabilities()

    def _request_capabilities(self) -> None:
        """Send requestCapabilities on the runtime data channel."""
        now = time.time()
        if now - self._last_request_time < 1.0:
            return
        self._last_request_time = now
        logger.debug("Requesting capabilities via data channel")
        asyncio.create_task(
            self.reactor.send_command("requestCapabilities", {}, MessageScope.RUNTIME)
        )

    def _parse_commands(self, commands_list: list[dict[str, Any]]) -> None:
        """
        Parse the commands array from the modelCapabilities message.

        The runtime sends commands as a list of ``{name, description, schema}``
        matching the proto Command message shape.
        """
        self.commands.clear()

        for cmd in commands_list:
            name = cmd.get("name", "")
            desc = cmd.get("description", "")
            schema = cmd.get("schema") or {}

            command_ui = CommandUI(name=name, description=desc, schema=schema, expanded=False)

            for param_name, param_schema in schema.items():
                element = self._create_element(param_name, param_schema)
                if element:
                    command_ui.elements.append(element)

            self.commands[name] = command_ui

        self._layout_commands()
        logger.info("Loaded %d commands from modelCapabilities", len(self.commands))

    # ─────────────────────────────────────────────────────────────────────
    # Element Factory
    # ─────────────────────────────────────────────────────────────────────

    def _create_element(
        self,
        param_name: str,
        param_schema: dict[str, Any],
    ) -> UIElement | None:
        param_type = param_schema.get("type", "string")
        dummy = pygame.Rect(0, 0, 0, 0)

        if param_type in ("number", "integer"):
            min_val = param_schema.get("minimum")
            max_val = param_schema.get("maximum")
            if min_val is not None and max_val is not None:
                return SliderElement(
                    rect=dummy,
                    param_name=param_name,
                    param_schema=param_schema,
                    value=min_val,
                    min_value=float(min_val),
                    max_value=float(max_val),
                    is_integer=(param_type == "integer"),
                )
            return TextInputElement(
                rect=dummy,
                param_name=param_name,
                param_schema=param_schema,
                value=0,
                text="0",
            )

        if param_type == "string":
            enum_values = param_schema.get("enum")
            if enum_values:
                return DropdownElement(
                    rect=dummy,
                    param_name=param_name,
                    param_schema=param_schema,
                    value=enum_values[0],
                    options=enum_values,
                )
            return TextInputElement(
                rect=dummy,
                param_name=param_name,
                param_schema=param_schema,
                value="",
                text="",
            )

        if param_type == "boolean":
            return CheckboxElement(
                rect=dummy,
                param_name=param_name,
                param_schema=param_schema,
                value=False,
                checked=False,
            )

        return None

    # ─────────────────────────────────────────────────────────────────────
    # Layout
    # ─────────────────────────────────────────────────────────────────────

    def _layout_commands(self) -> None:
        y = self.rect.y + 50
        pad = 8
        elem_h = 30

        for cmd_ui in self.commands.values():
            header_h = 40
            cmd_ui.header_rect = pygame.Rect(
                self.rect.x + pad,
                y - self.scroll_offset,
                self.rect.width - pad * 2,
                header_h,
            )
            cmd_ui.rect = pygame.Rect(
                self.rect.x + pad,
                y - self.scroll_offset,
                self.rect.width - pad * 2,
                header_h,
            )
            y += header_h

            if cmd_ui.expanded:
                for element in cmd_ui.elements:
                    element.rect = pygame.Rect(
                        self.rect.x + pad * 2,
                        y - self.scroll_offset,
                        self.rect.width - pad * 4,
                        elem_h,
                    )
                    y += elem_h + 4

                has_slider = any(isinstance(e, SliderElement) for e in cmd_ui.elements)
                if not has_slider:
                    cmd_ui.button_rect = pygame.Rect(
                        self.rect.x + pad * 2,
                        y - self.scroll_offset,
                        100,
                        30,
                    )
                    y += 40
                else:
                    cmd_ui.button_rect = None

                cmd_ui.rect.height = y - self.scroll_offset - cmd_ui.rect.y

            y += pad

        self.max_scroll = max(0, y - self.rect.y - self.rect.height)

    # ─────────────────────────────────────────────────────────────────────
    # Render
    # ─────────────────────────────────────────────────────────────────────

    def render(self, surface: pygame.Surface) -> None:
        clip = self.rect.copy()
        surface.set_clip(clip)

        pygame.draw.rect(surface, COLORS["panel"], self.rect)
        pygame.draw.rect(surface, COLORS["border"], self.rect, 1)

        title = self.font_title.render("Reactor Commands", True, COLORS["text"])
        surface.blit(title, (self.rect.x + 12, self.rect.y + 12))

        if not self.commands:
            surface.blit(
                self.font.render("Waiting for commands schema...", True, COLORS["text_muted"]),
                (self.rect.x + 12, self.rect.y + 50),
            )
            surface.set_clip(None)
            return

        for cmd_ui in self.commands.values():
            self._render_command(surface, cmd_ui)

        surface.set_clip(None)

    def _render_command(self, surface: pygame.Surface, cmd: CommandUI) -> None:
        if cmd.header_rect.bottom < self.rect.top or cmd.header_rect.top > self.rect.bottom:
            return

        pygame.draw.rect(surface, COLORS["header_bg"], cmd.header_rect)
        pygame.draw.rect(surface, COLORS["border"], cmd.header_rect, 1)

        name_text = self.font_bold.render(cmd.name, True, COLORS["text"])
        surface.blit(name_text, (cmd.header_rect.x + 12, cmd.header_rect.y + 5))

        if cmd.description:
            desc = self.font_desc.render(cmd.description[:40], True, COLORS["text_muted"])
            surface.blit(desc, (cmd.header_rect.x + 12, cmd.header_rect.y + 22))

        arrow = "▼" if cmd.expanded else "▶"
        surface.blit(
            self.font.render(arrow, True, COLORS["expand_arrow"]),
            (cmd.header_rect.right - 24, cmd.header_rect.y + 12),
        )

        if not cmd.expanded:
            return

        for element in cmd.elements:
            if element.rect.bottom < self.rect.top or element.rect.top > self.rect.bottom:
                continue
            if isinstance(element, SliderElement):
                self._render_slider(surface, element)
            elif isinstance(element, TextInputElement):
                self._render_text_input(surface, element)
            elif isinstance(element, CheckboxElement):
                self._render_checkbox(surface, element)
            elif isinstance(element, DropdownElement):
                self._render_dropdown(surface, element)

        if cmd.button_rect:
            pygame.draw.rect(surface, COLORS["primary"], cmd.button_rect)
            btn = self.font_bold.render("Execute", True, (255, 255, 255))
            surface.blit(btn, btn.get_rect(center=cmd.button_rect.center))

    def _render_slider(self, surface: pygame.Surface, el: SliderElement) -> None:
        label = f"{el.param_name} ({el.min_value:.1f} - {el.max_value:.1f})"
        surface.blit(self.font.render(label, True, COLORS["text_light"]), (el.rect.x, el.rect.y))

        track = pygame.Rect(el.rect.x, el.rect.y + 16, el.rect.width - 60, 4)
        pygame.draw.rect(surface, COLORS["slider_track"], track)

        progress = (el.value - el.min_value) / max(el.max_value - el.min_value, 1e-9)
        fill_w = int(track.width * progress)
        pygame.draw.rect(surface, COLORS["slider_fill"], pygame.Rect(track.x, track.y, fill_w, 4))

        thumb_x = track.x + fill_w
        pygame.draw.circle(surface, COLORS["slider_thumb"], (thumb_x, track.centery), 8)
        pygame.draw.circle(surface, COLORS["slider_fill"], (thumb_x, track.centery), 8, 2)

        val_str = f"{int(el.value)}" if el.is_integer else f"{el.value:.2f}"
        surface.blit(
            self.font.render(val_str, True, COLORS["text_muted"]), (track.right + 8, el.rect.y + 10)
        )

    def _render_text_input(self, surface: pygame.Surface, el: TextInputElement) -> None:
        surface.blit(
            self.font.render(el.param_name, True, COLORS["text_light"]), (el.rect.x, el.rect.y)
        )
        box = pygame.Rect(el.rect.x, el.rect.y + 14, el.rect.width, 18)
        pygame.draw.rect(surface, COLORS["input_bg"], box)
        border = COLORS["primary"] if el.focused else COLORS["input_border"]
        pygame.draw.rect(surface, border, box, 1)
        surface.blit(self.font.render(el.text, True, COLORS["text"]), (box.x + 4, box.y + 2))

    def _render_checkbox(self, surface: pygame.Surface, el: CheckboxElement) -> None:
        box = pygame.Rect(el.rect.x, el.rect.y + 8, 14, 14)
        pygame.draw.rect(surface, COLORS["input_bg"], box)
        pygame.draw.rect(surface, COLORS["input_border"], box, 1)
        if el.checked:
            pygame.draw.rect(
                surface, COLORS["checkbox_checked"], pygame.Rect(box.x + 3, box.y + 3, 8, 8)
            )
        surface.blit(
            self.font.render(el.param_name, True, COLORS["text_light"]),
            (box.right + 8, el.rect.y + 8),
        )

    def _render_dropdown(self, surface: pygame.Surface, el: DropdownElement) -> None:
        surface.blit(
            self.font.render(el.param_name, True, COLORS["text_light"]), (el.rect.x, el.rect.y)
        )
        box = pygame.Rect(el.rect.x, el.rect.y + 14, el.rect.width, 18)
        pygame.draw.rect(surface, COLORS["input_bg"], box)
        pygame.draw.rect(surface, COLORS["input_border"], box, 1)
        val = el.options[el.selected_index] if el.options else "Select..."
        surface.blit(self.font.render(val, True, COLORS["text"]), (box.x + 4, box.y + 2))
        surface.blit(self.font.render("▼", True, COLORS["text_muted"]), (box.right - 16, box.y + 2))

    # ─────────────────────────────────────────────────────────────────────
    # Event Handling
    # ─────────────────────────────────────────────────────────────────────

    def handle_event(self, event: pygame.event.Event) -> bool:
        if event.type == pygame.MOUSEBUTTONDOWN:
            if event.button == 1:
                return self._handle_click(event.pos)
            if event.button == 4:
                self.scroll_offset = max(0, self.scroll_offset - 20)
                self._layout_commands()
                return True
            if event.button == 5:
                self.scroll_offset = min(self.max_scroll, self.scroll_offset + 20)
                self._layout_commands()
                return True
        elif event.type == pygame.MOUSEBUTTONUP and event.button == 1:
            self._handle_mouse_up()
        elif event.type == pygame.MOUSEMOTION:
            return self._handle_mouse_motion(event.pos)
        elif event.type == pygame.KEYDOWN:
            return self._handle_key(event)
        return False

    def _handle_click(self, pos: tuple[int, int]) -> bool:
        if not self.rect.collidepoint(pos):
            return False

        for cmd_ui in self.commands.values():
            if cmd_ui.header_rect.collidepoint(pos):
                cmd_ui.expanded = not cmd_ui.expanded
                self._layout_commands()
                return True
            if cmd_ui.button_rect and cmd_ui.button_rect.collidepoint(pos):
                self._execute_command(cmd_ui)
                return True
            if cmd_ui.expanded:
                for element in cmd_ui.elements:
                    if self._handle_element_click(element, pos, cmd_ui):
                        return True
        return False

    def _handle_element_click(
        self,
        element: UIElement,
        pos: tuple[int, int],
        cmd_ui: CommandUI,
    ) -> bool:
        if not element.rect.collidepoint(pos):
            return False

        if isinstance(element, SliderElement):
            element.dragging = True
            self._update_slider(element, pos[0])
            self._execute_command(cmd_ui)
            return True
        if isinstance(element, CheckboxElement):
            element.checked = not element.checked
            element.value = element.checked
            return True
        if isinstance(element, TextInputElement):
            for cmd in self.commands.values():
                for el in cmd.elements:
                    if isinstance(el, TextInputElement):
                        el.focused = False
            element.focused = True
            return True
        if isinstance(element, DropdownElement) and element.options:
            element.selected_index = (element.selected_index + 1) % len(element.options)
            element.value = element.options[element.selected_index]
            return True
        return False

    def _handle_mouse_up(self) -> None:
        for cmd_ui in self.commands.values():
            for el in cmd_ui.elements:
                if isinstance(el, SliderElement):
                    el.dragging = False

    def _handle_mouse_motion(self, pos: tuple[int, int]) -> bool:
        for cmd_ui in self.commands.values():
            for el in cmd_ui.elements:
                if isinstance(el, SliderElement) and el.dragging:
                    self._update_slider(el, pos[0])
                    self._execute_command(cmd_ui)
                    return True
        return False

    def _update_slider(self, el: SliderElement, mouse_x: int) -> None:
        start = el.rect.x
        end = el.rect.x + el.rect.width - 60
        progress = max(0.0, min(1.0, (mouse_x - start) / max(end - start, 1)))
        value = el.min_value + progress * (el.max_value - el.min_value)
        el.value = round(value) if el.is_integer else value

    def _handle_key(self, event: pygame.event.Event) -> bool:
        for cmd_ui in self.commands.values():
            for el in cmd_ui.elements:
                if isinstance(el, TextInputElement) and el.focused:
                    if event.key == pygame.K_BACKSPACE:
                        el.text = el.text[:-1]
                    elif event.key == pygame.K_RETURN:
                        el.focused = False
                    elif event.unicode and event.unicode.isprintable():
                        el.text += event.unicode
                    self._sync_text_value(el)
                    return True
        return False

    @staticmethod
    def _sync_text_value(el: TextInputElement) -> None:
        """Keep value in sync with the displayed text."""
        try:
            if el.param_schema.get("type") in ("number", "integer"):
                el.value = float(el.text) if el.text else 0
            else:
                el.value = el.text
        except ValueError:
            el.value = el.text

    def _execute_command(self, cmd_ui: CommandUI) -> None:
        data: dict[str, Any] = {}
        for el in cmd_ui.elements:
            schema = el.param_schema
            val = el.value
            if schema.get("type") == "integer" and val is not None:
                val = int(val)
            elif schema.get("type") == "number" and val is not None:
                val = float(val)
            elif schema.get("type") == "boolean":
                val = bool(val)

            if val is not None and val != "":
                data[el.param_name] = val
            elif schema.get("required"):
                if schema.get("type") in ("number", "integer"):
                    data[el.param_name] = schema.get("minimum", 0)
                elif schema.get("type") == "string":
                    data[el.param_name] = ""
                elif schema.get("type") == "boolean":
                    data[el.param_name] = False

        logger.info("Sending command: %s with data: %s", cmd_ui.name, data)
        asyncio.create_task(self.reactor.send_command(cmd_ui.name, data))

    def update(self) -> None:
        """Retry requesting capabilities if not yet received."""
        if self.reactor.get_status() == ReactorStatus.READY and not self._capabilities_received:
            now = time.time()
            if now - self._last_request_time >= 5.0:
                self._request_capabilities()
