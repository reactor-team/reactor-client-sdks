"""Reactor Python client — thin async wrapper over libreactor_ffi (ctypes)."""

from __future__ import annotations

import asyncio
import ctypes
import json
from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from ._ffi import (
    COMPLETION_FN,
    ON_AUDIO_FN,
    ON_FRAME_FN,
    ON_STRING_FN,
    ON_TRACK_FN,
    ReactorCallbacks,
    get_lib,
)

# ---------------------------------------------------------------------------
# Public types
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class Clip:
    """A finished (or soon-available) clip returned by request_clip / request_recording."""

    session_id: str
    kind: str
    start_marker: float
    end_marker: float
    now_marker: float
    predicted_ready_at_ms: float
    playlist_url: str


@dataclass(frozen=True)
class FileRef:
    """Reference to an uploaded file; pass as a command argument."""

    upload_id: str
    name: str
    mime_type: str
    size: int


@dataclass
class ReactorError:
    """Error event payload."""

    code: str
    message: str
    timestamp_ms: float
    recoverable: bool
    component: str
    retry_after_ms: float | None = None

    def __str__(self) -> str:
        return f"[{self.component}:{self.code}] {self.message}"


class ReactorFFIError(Exception):
    """Raised when a reactor FFI async operation fails."""


# ---------------------------------------------------------------------------
# Reactor
# ---------------------------------------------------------------------------


class Reactor:
    """
    Async Reactor client backed by libreactor_ffi.

    Example::

        async with Reactor("wss://api.reactor.inc", "my-model", jwt=token) as r:
            r.on("message", lambda msg: print(msg))
            await r.connect()
            await r.send_command("hello", {"text": "hi"})
    """

    def __init__(
        self,
        api_url: str,
        model_name: str,
        *,
        jwt: str | None = None,
        local: bool = False,
        adm_mode: int | None = None,
    ) -> None:
        self._api_url = api_url
        self._model_name = model_name
        self._jwt = jwt
        self._local = local
        self._adm_mode = adm_mode

        self._handle: int | None = None

        # event handlers: event_name -> list[callable]
        self._handlers: dict[str, list[Callable]] = {}

        # Keep ctypes callback objects alive (GC would invalidate the fn pointer)
        self._callbacks_struct: ReactorCallbacks | None = None
        self._cb_refs: list[Any] = []

        # Current loop — set during connect/create
        self._loop: asyncio.AbstractEventLoop | None = None

    # ------------------------------------------------------------------
    # Event registration
    # ------------------------------------------------------------------

    def on(self, event: str, handler: Callable) -> None:
        """Register a handler for an event name."""
        self._handlers.setdefault(event, []).append(handler)

    def off(self, event: str, handler: Callable) -> None:
        """Unregister a handler."""
        handlers = self._handlers.get(event, [])
        try:
            handlers.remove(handler)
        except ValueError:
            pass

    def _fire(self, event: str, *args: Any) -> None:
        for h in list(self._handlers.get(event, [])):
            try:
                h(*args)
            except Exception:
                pass

    # ------------------------------------------------------------------
    # Lifecycle
    # ------------------------------------------------------------------

    def _create_handle(self) -> None:
        lib = get_lib()
        loop = asyncio.get_event_loop()
        self._loop = loop

        # Build callback wrappers; capture self weakly via closure
        import weakref
        weak = weakref.ref(self)

        def _on_status(status_bytes: bytes, _ud: Any) -> None:
            r = weak()
            if r is None:
                return
            status = status_bytes.decode() if status_bytes else "disconnected"
            r._fire("status_changed", status)

        def _on_error(json_bytes: bytes, _ud: Any) -> None:
            r = weak()
            if r is None:
                return
            try:
                d = json.loads(json_bytes)
                err = ReactorError(
                    code=d.get("code", "UNKNOWN"),
                    message=d.get("message", ""),
                    timestamp_ms=d.get("timestamp_ms", 0.0),
                    recoverable=d.get("recoverable", False),
                    component=d.get("component", "api"),
                    retry_after_ms=d.get("retry_after_ms"),
                )
                r._fire("error", err)
            except Exception:
                pass

        def _on_message(json_bytes: bytes, _ud: Any) -> None:
            r = weak()
            if r is None:
                return
            try:
                r._fire("message", json.loads(json_bytes))
            except Exception:
                pass

        def _on_runtime_message(json_bytes: bytes, _ud: Any) -> None:
            r = weak()
            if r is None:
                return
            try:
                r._fire("runtime_message", json.loads(json_bytes))
            except Exception:
                pass

        def _on_track(name_bytes: bytes, mid_bytes: bytes | None, _ud: Any) -> None:
            r = weak()
            if r is None:
                return
            name = name_bytes.decode() if name_bytes else ""
            mid = mid_bytes.decode() if mid_bytes else None
            r._fire("track_received", name, mid)

        def _on_capabilities(json_bytes: bytes, _ud: Any) -> None:
            r = weak()
            if r is None:
                return
            try:
                r._fire("capabilities_received", json.loads(json_bytes))
            except Exception:
                pass

        def _on_session_id(sid_bytes: bytes | None, _ud: Any) -> None:
            r = weak()
            if r is None:
                return
            sid = sid_bytes.decode() if sid_bytes else None
            r._fire("session_id_changed", sid)

        def _on_frame(data_ptr: int, width: int, height: int, _ud: Any) -> None:
            r = weak()
            if r is None:
                return
            n = width * height * 4
            frame = (ctypes.c_uint8 * n).from_address(data_ptr)
            r._fire("frame", bytes(frame), width, height)

        def _on_audio(
            data_ptr: int, num_samples: int, sample_rate: int, channels: int, _ud: Any
        ) -> None:
            r = weak()
            if r is None:
                return
            arr = (ctypes.c_int16 * num_samples).from_address(data_ptr)
            r._fire("audio", bytes(arr), num_samples, sample_rate, channels)

        # Wrap with ctypes CFUNCTYPE
        s_cb = ON_STRING_FN(_on_status)
        e_cb = ON_STRING_FN(_on_error)
        m_cb = ON_STRING_FN(_on_message)
        rm_cb = ON_STRING_FN(_on_runtime_message)
        tr_cb = ON_TRACK_FN(_on_track)
        cap_cb = ON_STRING_FN(_on_capabilities)
        sid_cb = ON_STRING_FN(_on_session_id)
        fr_cb = ON_FRAME_FN(_on_frame)
        au_cb = ON_AUDIO_FN(_on_audio)

        cbs = ReactorCallbacks(
            on_status=s_cb,
            on_error=e_cb,
            on_message=m_cb,
            on_runtime_message=rm_cb,
            on_track=tr_cb,
            on_capabilities=cap_cb,
            on_session_id=sid_cb,
            on_frame=fr_cb,
            on_audio=au_cb,
            userdata=None,
        )
        self._callbacks_struct = cbs
        self._cb_refs = [s_cb, e_cb, m_cb, rm_cb, tr_cb, cap_cb, sid_cb, fr_cb, au_cb]

        jwt_bytes = self._jwt.encode() if self._jwt else None
        local_int = 1 if self._local else 0

        if self._adm_mode is not None:
            handle = lib.reactor_create_with_adm(
                self._api_url.encode(),
                self._model_name.encode(),
                jwt_bytes,
                local_int,
                ctypes.byref(cbs),
                ctypes.c_int(self._adm_mode),
            )
        else:
            handle = lib.reactor_create(
                self._api_url.encode(),
                self._model_name.encode(),
                jwt_bytes,
                local_int,
                ctypes.byref(cbs),
            )

        self._handle = handle

    def _destroy_handle(self) -> None:
        if self._handle is not None:
            get_lib().reactor_destroy(ctypes.c_void_p(self._handle))
            self._handle = None
            self._callbacks_struct = None
            self._cb_refs = []

    # ------------------------------------------------------------------
    # Async completion bridge
    # ------------------------------------------------------------------

    def _make_completion(
        self, future: asyncio.Future
    ) -> Any:
        """Return a COMPLETION_FN that resolves ``future`` on the event loop."""
        loop = self._loop

        def _cb(
            ok: int, result_json: bytes | None, error_msg: bytes | None, _ud: Any
        ) -> None:
            if ok:
                payload = json.loads(result_json) if result_json and result_json != b"{}" else None
                loop.call_soon_threadsafe(future.set_result, payload)
            else:
                msg = error_msg.decode() if error_msg else "unknown error"
                loop.call_soon_threadsafe(future.set_exception, ReactorFFIError(msg))

        fn = COMPLETION_FN(_cb)
        # Keep fn alive until future resolves
        future.add_done_callback(lambda _: None)
        return fn

    async def _async_op(self, dispatcher: Callable[[Any], None]) -> Any:
        """Run a one-shot async FFI operation and return its result."""
        loop = asyncio.get_event_loop()
        future: asyncio.Future = loop.create_future()
        fn = self._make_completion(future)
        # Keep fn alive for the duration of the await
        dispatcher(fn)
        result = await future
        del fn
        return result

    # ------------------------------------------------------------------
    # Connection lifecycle
    # ------------------------------------------------------------------

    async def connect(self, *, session_id: str | None = None) -> None:
        """Connect: create a session and establish WebRTC transport."""
        if self._handle is None:
            self._create_handle()
        lib = get_lib()
        sid = session_id.encode() if session_id else None
        handle = self._handle

        await self._async_op(
            lambda fn: lib.reactor_connect(
                ctypes.c_void_p(handle), sid, fn, None
            )
        )

    async def reconnect(self) -> None:
        """Reconnect to the current session after a transient failure."""
        self._require_handle()
        handle = self._handle
        lib = get_lib()
        await self._async_op(
            lambda fn: lib.reactor_reconnect(ctypes.c_void_p(handle), fn, None)
        )

    async def disconnect(self) -> None:
        """Gracefully disconnect (session is preserved for reconnect)."""
        if self._handle is None:
            return
        handle = self._handle
        lib = get_lib()
        await self._async_op(
            lambda fn: lib.reactor_disconnect(ctypes.c_void_p(handle), fn, None)
        )

    def close(self) -> None:
        """Synchronously destroy the underlying handle."""
        self._destroy_handle()

    # ------------------------------------------------------------------
    # Messaging
    # ------------------------------------------------------------------

    def send_command(
        self,
        command: str,
        data: Any,
        scope: str = "application",
    ) -> int:
        """Send a fire-and-forget command. Returns 0 on success, -1 on error."""
        self._require_handle()
        lib = get_lib()
        args_json = json.dumps(data).encode()
        if scope == "runtime":
            return lib.reactor_send_runtime_command(
                ctypes.c_void_p(self._handle),
                command.encode(),
                args_json,
            )
        return lib.reactor_send_command(
            ctypes.c_void_p(self._handle),
            command.encode(),
            args_json,
        )

    # ------------------------------------------------------------------
    # Track control
    # ------------------------------------------------------------------

    async def publish_track(self, name: str) -> None:
        """Activate a named sendonly track slot."""
        self._require_handle()
        handle = self._handle
        lib = get_lib()
        name_b = name.encode()
        await self._async_op(
            lambda fn: lib.reactor_publish_track(ctypes.c_void_p(handle), name_b, fn, None)
        )

    def unpublish_track(self, name: str) -> int:
        """Deactivate a sendonly track (sync). Returns 0 on success."""
        self._require_handle()
        return get_lib().reactor_unpublish_track(
            ctypes.c_void_p(self._handle), name.encode()
        )

    async def pause_track(self, name: str) -> None:
        """Pause receiving a named track."""
        self._require_handle()
        handle = self._handle
        lib = get_lib()
        name_b = name.encode()
        await self._async_op(
            lambda fn: lib.reactor_pause_track(ctypes.c_void_p(handle), name_b, fn, None)
        )

    async def resume_track(self, name: str) -> None:
        """Resume receiving a named track."""
        self._require_handle()
        handle = self._handle
        lib = get_lib()
        name_b = name.encode()
        await self._async_op(
            lambda fn: lib.reactor_resume_track(ctypes.c_void_p(handle), name_b, fn, None)
        )

    # ------------------------------------------------------------------
    # Frame push
    # ------------------------------------------------------------------

    def push_video_frame(
        self,
        track_name: str,
        data: bytes,
        width: int,
        height: int,
    ) -> None:
        """Push a raw BGRA video frame into a named sendonly track."""
        self._require_handle()
        buf = (ctypes.c_uint8 * len(data)).from_buffer_copy(data)
        get_lib().reactor_push_video_frame(
            ctypes.c_void_p(self._handle),
            track_name.encode(),
            buf,
            ctypes.c_uint32(width),
            ctypes.c_uint32(height),
        )

    def push_audio_frame(
        self,
        track_name: str,
        data: bytes,
        samples_per_channel: int,
        sample_rate: int = 48000,
        num_channels: int = 1,
    ) -> None:
        """Push interleaved i16 PCM into a named sendonly audio track."""
        self._require_handle()
        import ctypes as ct
        n = samples_per_channel * num_channels
        buf = (ct.c_int16 * n).from_buffer_copy(data)
        get_lib().reactor_push_audio_frame(
            ctypes.c_void_p(self._handle),
            track_name.encode(),
            buf,
            ctypes.c_uint32(samples_per_channel),
            ctypes.c_uint32(sample_rate),
            ctypes.c_uint32(num_channels),
        )

    # ------------------------------------------------------------------
    # Recording
    # ------------------------------------------------------------------

    async def request_clip(self, duration_seconds: float) -> Clip:
        """Request a clip of the last ``duration_seconds`` of the session."""
        self._require_handle()
        handle = self._handle
        lib = get_lib()
        result = await self._async_op(
            lambda fn: lib.reactor_request_clip(
                ctypes.c_void_p(handle), ctypes.c_double(duration_seconds), fn, None
            )
        )
        return Clip(**result)

    async def request_recording(self) -> Clip:
        """Request a clip covering the entire session up to now."""
        self._require_handle()
        handle = self._handle
        lib = get_lib()
        result = await self._async_op(
            lambda fn: lib.reactor_request_recording(ctypes.c_void_p(handle), fn, None)
        )
        return Clip(**result)

    # ------------------------------------------------------------------
    # File upload
    # ------------------------------------------------------------------

    async def upload_file(self, path: str) -> FileRef:
        """Upload a local file; returns a FileRef for use in send_command."""
        self._require_handle()
        handle = self._handle
        lib = get_lib()
        path_b = path.encode()
        result = await self._async_op(
            lambda fn: lib.reactor_upload_file(ctypes.c_void_p(handle), path_b, fn, None)
        )
        return FileRef(**result)

    # ------------------------------------------------------------------
    # State accessors
    # ------------------------------------------------------------------

    @property
    def status(self) -> str:
        """Current status string: disconnected | connecting | waiting | ready."""
        if self._handle is None:
            return "disconnected"
        raw = get_lib().reactor_status(ctypes.c_void_p(self._handle))
        return raw.decode() if raw else "disconnected"

    @property
    def session_id(self) -> str | None:
        """Current session ID, or None when disconnected."""
        if self._handle is None:
            return None
        lib = get_lib()
        ptr = lib.reactor_session_id(ctypes.c_void_p(self._handle))
        if not ptr:
            return None
        sid = ctypes.cast(ptr, ctypes.c_char_p).value
        lib.reactor_free_string(ctypes.c_void_p(ptr))
        return sid.decode() if sid else None

    # ------------------------------------------------------------------
    # Context manager
    # ------------------------------------------------------------------

    async def __aenter__(self) -> Reactor:
        return self

    async def __aexit__(self, *_: object) -> None:
        try:
            if self._handle is not None and self.status != "disconnected":
                await self.disconnect()
        finally:
            self.close()

    # ------------------------------------------------------------------
    # Internals
    # ------------------------------------------------------------------

    def _require_handle(self) -> None:
        if self._handle is None:
            raise RuntimeError("Reactor handle not created — call connect() first")
