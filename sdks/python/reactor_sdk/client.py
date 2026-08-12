"""Reactor Python client — thin async wrapper over libreactor_ffi (ctypes)."""

from __future__ import annotations

import asyncio
import atexit
import collections.abc
import ctypes
import inspect
import json
import logging
import weakref
from collections.abc import Callable
from dataclasses import dataclass
from enum import Enum
from typing import Any

from ._auth import fetch_jwt
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

#: Reactor's production coordinator, used when no `api_url` is given.
DEFAULT_API_URL = "https://api.reactor.inc"

#: Where a local coordinator listens. `local=True` points at this.
LOCAL_API_URL = "http://localhost:8080"


class ReactorStatus(str, Enum):
    """Connection status.

    A `str` enum, so it compares equal to the plain strings the FFI reports and can be
    used interchangeably with them: ``reactor.status == ReactorStatus.READY`` and
    ``reactor.status == "ready"`` are both true, and ``.value`` gives the string.
    """

    DISCONNECTED = "disconnected"
    CONNECTING = "connecting"
    WAITING = "waiting"
    READY = "ready"


class MessageScope(str, Enum):
    """Which side of the protocol a command is addressed to.

    `APPLICATION` reaches the model; `RUNTIME` reaches the platform around it
    (capabilities, recording, moderation).
    """

    APPLICATION = "application"
    RUNTIME = "runtime"


class CommandResult(int):
    """The result of :meth:`Reactor.send_command`: 0 on success, -1 on failure.

    The send has already happened by the time this exists. It is an `int`, so it can be
    compared and tested directly — and it is *also* a coroutine, so every way of
    writing the call works:

        reactor.send_command(...)                      # 0 or -1
        await reactor.send_command(...)                # 0 or -1
        asyncio.create_task(reactor.send_command(...)) # a Task resolving to it

    Sending was a coroutine in earlier releases, so the awaiting forms are what code
    written against those does. Returning a real coroutine instead would make the plain
    synchronous call — the documented one — emit "coroutine was never awaited" every
    time. Satisfying the coroutine protocol on an already-finished value avoids the
    warning while keeping `create_task`, which insists on a coroutine rather than
    merely an awaitable.
    """

    def __await__(self):
        # A generator function, so `return` becomes the awaited value. `yield from ()`
        # is what makes it one without ever suspending: the work is already done, so
        # there is nothing to wait for.
        yield from ()
        return int(self)

    def send(self, _value: Any) -> None:
        raise StopIteration(int(self))

    def throw(self, *args: Any) -> None:
        raise StopIteration(int(self))

    def close(self) -> None:
        pass


# Registered rather than inherited: `int` and the ABC have incompatible metaclasses,
# and a virtual subclass is enough for `asyncio.iscoroutine`, which is what decides
# whether `create_task` accepts it.
collections.abc.Coroutine.register(CommandResult)


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


_log = logging.getLogger(__name__)

# Callback trampolines belonging to handles that could not be confirmed quiesced on
# destroy. Deliberately never emptied: the native library may still hold pointers
# into them, and a small permanent leak beats a use-after-free. Growth here means
# handlers are blocking or clients are being closed from inside a handler.
_ORPHANED_CALLBACKS: list[tuple[Any, list[Any]]] = []

# Every client with a live handle, weakly held. Exists so the interpreter cannot
# exit with one still open.
_LIVE_CLIENTS: weakref.WeakSet = weakref.WeakSet()


def _close_live_clients() -> None:
    """Tear down any client the program left open, at exit.

    Registered with :mod:`atexit`, which runs while the interpreter is still fully
    alive — and that timing is the whole point. Once finalisation proper begins,
    CPython stops handing the GIL back to foreign threads, so a callback in flight
    can never finish, ``reactor_destroy`` cannot reach quiescence, and the native
    threads are left calling into an interpreter that is dismantling itself.
    """
    for client in list(_LIVE_CLIENTS):
        try:
            client.close()
        except Exception:  # pragma: no cover - best effort at exit
            _log.debug("failed to close a client at exit", exc_info=True)


atexit.register(_close_live_clients)


#: What `on_frame` offers a handler, in order. A handler is given as many of these as it
#: declares parameters for, so the historical one-argument contract keeps working while a
#: handler that wants the metadata trailer just asks for more.
FRAME_HANDLER_ARGUMENTS = ("frame", "frame_id", "timestamp_us", "user_data")


def _positional_arity(func: Callable, maximum: int) -> int:
    """How many of `maximum` positional arguments `func` is willing to take.

    Decided once, when the handler is registered, rather than per frame.

    Falls back to one on anything it cannot read — a builtin, a C function, an exotic
    callable. One is the shape `on_frame` has always had, so the fallback is the
    compatible direction rather than a guess.
    """
    try:
        parameters = inspect.signature(func).parameters.values()
    except (TypeError, ValueError):
        return 1

    count = 0
    for parameter in parameters:
        if parameter.kind is inspect.Parameter.VAR_POSITIONAL:
            # *args takes whatever there is.
            return maximum
        if parameter.kind in (
            inspect.Parameter.POSITIONAL_ONLY,
            inspect.Parameter.POSITIONAL_OR_KEYWORD,
        ):
            count += 1

    # Never zero: a handler that takes nothing is a mistake worth surfacing as the
    # TypeError it is, rather than silently never being given the frame.
    return max(1, min(count, maximum))


def _bgra_to_rgb_array(bgra: bytes, width: int, height: int) -> Any:
    """Turn a BGRA frame into an RGB ``numpy`` array of shape ``(height, width, 3)``.

    Two conversions in one: drop the alpha channel, and reverse BGR to RGB. The slice
    ``[..., 2::-1]`` does both — it walks the first three bytes backwards, so B, G, R, A
    becomes R, G, B.

    numpy is imported here rather than at module scope so that it stays optional: only
    the `on_frame` decorator needs it, and the rest of the SDK has no dependencies.
    """
    try:
        import numpy as np
    except ModuleNotFoundError as exc:  # pragma: no cover - depends on the environment
        raise ModuleNotFoundError(
            "on_frame delivers numpy arrays, and numpy is not installed. Install it, "
            'or use on("frame", ...) to receive the raw BGRA bytes instead.'
        ) from exc

    frame = np.frombuffer(bgra, dtype=np.uint8).reshape(height, width, 4)
    return frame[..., 2::-1]


def _settle_from_foreign_thread(
    loop: asyncio.AbstractEventLoop,
    future: asyncio.Future,
    payload: Any,
    error: BaseException | None,
) -> None:
    """Complete `future` from a thread that does not own `loop`.

    Both failure modes here are reachable and neither may raise into the FFI: the
    loop can be closed by the time a completion arrives, and the future can already
    be cancelled. Left unguarded, the exception surfaces inside the ctypes trampoline
    on a native thread, where ctypes prints it and returns — and the coroutine that
    was waiting is never woken at all.
    """

    def _settle() -> None:
        if future.done():
            return
        if error is not None:
            future.set_exception(error)
        else:
            future.set_result(payload)

    try:
        loop.call_soon_threadsafe(_settle)
    except RuntimeError:
        _log.debug("completion arrived after the event loop closed; discarding")


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
        api_url: str | None = None,
        model_name: str | None = None,
        *,
        jwt: str | None = None,
        api_key: str | None = None,
        local: bool = False,
        adm_mode: int | None = None,
    ) -> None:
        """
        Args:
            api_url: Coordinator base URL. Defaults to production.
            model_name: Model to connect to. Required.
            jwt: A token to authenticate with.
            api_key: An API key to exchange for a token at connect time. Use one or
                the other; `jwt` wins if both are given.
            local: Local-dev mode — relaxes TLS verification and skips auth.
            adm_mode: 0 synthetic, 1 platform, None for the platform default.
        """
        # Older releases took `model_name` first and `api_url` second. Both orders work:
        # an api_url is always a URL and a model name never is, so the two cannot be
        # confused. Keyword arguments are unambiguous either way.
        if (
            api_url is not None
            and model_name is None
            and not api_url.startswith(("http://", "https://"))
        ):
            api_url, model_name = None, api_url

        if model_name is None:
            raise TypeError("Reactor() requires model_name")

        # Local mode means a local coordinator, so it picks the URL. The production
        # default counts as "no choice made": callers routinely compute
        # `api_url or "https://api.reactor.inc"` and pass that alongside `local=True`,
        # which without this would aim local mode at production. An api_url that is
        # anything else was a real choice and is honoured, so a local coordinator on
        # another port still works.
        if local and (api_url is None or api_url == DEFAULT_API_URL):
            api_url = LOCAL_API_URL

        self._api_url = api_url or DEFAULT_API_URL
        self._model_name = model_name
        self._jwt = jwt
        self._api_key = api_key
        self._local = local
        self._adm_mode = adm_mode

        # A token the caller handed us is theirs: never replaced. One we minted from an
        # API key is ours, and `_minted_for` records the scope it was minted with, so a
        # later connect needing a different scope knows to mint again.
        self._caller_supplied_jwt = jwt is not None
        self._minted_for: list[str] | None = None

        self._handle: int | None = None

        # event handlers: event_name -> list[callable]
        self._handlers: dict[str, list[Callable]] = {}

        # Keep ctypes callback objects alive (GC would invalidate the fn pointer)
        self._callbacks_struct: ReactorCallbacks | None = None
        self._cb_refs: list[Any] = []

        # Completion trampolines for operations in flight, keyed by id(). Owned here
        # rather than by the awaiting frame, so cancelling an await cannot free one
        # the library still holds a pointer to.
        self._pending_completions: dict[int, Any] = {}

        # The loop that created the handle. Control events are marshalled onto it.
        self._loop: asyncio.AbstractEventLoop | None = None

    # ------------------------------------------------------------------
    # Event registration
    # ------------------------------------------------------------------

    def on(self, event: str, handler: Callable) -> None:
        """Register a handler for an event name."""
        self._handlers.setdefault(event, []).append(handler)

    # ------------------------------------------------------------------
    # Decorator registration
    # ------------------------------------------------------------------
    #
    # The same events as `on`, registered by decorating. Each returns the function
    # unchanged, so the decorated name stays callable.

    def on_frame(self, func: Callable) -> Callable:
        """Register a handler for decoded video frames.

        The handler is given as many of ``(frame, frame_id, timestamp_us, user_data)``
        as it declares parameters for, so it can ask for only what it uses::

            @reactor.on_frame
            def render(frame): ...                                  # just the image

            @reactor.on_frame
            def render(frame, frame_id, timestamp_us, user_data): ...  # and the trailer

        ``frame`` is an RGB ``numpy`` array of shape ``(height, width, 3)``, ready for
        anything that renders images — width and height are its shape, which is why they
        are not passed separately. The other three are the metadata trailer, and are
        ``0``, ``0`` and ``b""`` on a frame that carries none.

        One argument is what this decorator has always given, so existing handlers are
        unaffected; a handler taking ``*args`` gets all four.

        ``on("frame", ...)`` remains the other shape of the same event, handing over the
        untouched BGRA bytes with the dimensions and the trailer. Prefer it when the
        conversion is not wanted — for forwarding the bytes somewhere, say.

        Requires numpy, which is not a dependency of this package: installing it is the
        price of the conversion.
        """
        take = _positional_arity(func, len(FRAME_HANDLER_ARGUMENTS))

        def handler(
            bgra: bytes,
            width: int,
            height: int,
            frame_id: int,
            timestamp_us: int,
            user_data: bytes,
        ) -> None:
            # The array is built first because it is what every handler wants; the
            # conversion is the cost of this decorator either way.
            arguments = (
                _bgra_to_rgb_array(bgra, width, height),
                frame_id,
                timestamp_us,
                user_data,
            )
            func(*arguments[:take])

        self.on("frame", handler)
        return func

    def on_status(self, arg: Callable | str | None = None) -> Callable:
        """Register a handler for status changes.

        Bare, the handler receives every change::

            @reactor.on_status
            def changed(status): ...

        Given a status, it fires only on that one. The handler still receives it, even
        though it can only ever be the one asked for — that is the shape existing code
        is written to::

            @reactor.on_status(ReactorStatus.READY)
            def ready(status): ...
        """
        # Bare: the decorated function arrives as `arg`.
        if callable(arg):
            func = arg

            def every(status: str) -> None:
                func(ReactorStatus(status))

            self.on("status_changed", every)
            return func

        wanted = ReactorStatus(arg) if arg is not None else None

        def decorator(func: Callable) -> Callable:
            def filtered(status: str) -> None:
                if wanted is None or status == wanted.value:
                    func(ReactorStatus(status))

            self.on("status_changed", filtered)
            return func

        return decorator

    def on_error(self, func: Callable) -> Callable:
        """Register a handler for errors. The handler receives a `ReactorError`."""
        self.on("error", func)
        return func

    def on_message(self, func: Callable) -> Callable:
        """Register a handler for application messages from the model."""
        self.on("message", func)
        return func

    def on_track(self, func: Callable) -> Callable:
        """Register a handler for incoming media tracks."""
        self.on("track_received", func)
        return func

    def off(self, event: str, handler: Callable) -> None:
        """Unregister a handler."""
        handlers = self._handlers.get(event, [])
        try:
            handlers.remove(handler)
        except ValueError:
            pass

    def _fire(self, event: str, *args: Any) -> None:
        """Run every handler for `event` on the calling thread, now.

        A raising handler does not stop the others, and the exception is logged
        rather than dropped — one broken handler should be visible without taking
        the rest of the event down with it.
        """
        for h in list(self._handlers.get(event, [])):
            try:
                h(*args)
            except Exception:
                _log.exception("error in %r handler %r", event, h)

    def _fire_on_loop(self, event: str, *args: Any) -> None:
        """Hand `event` to the loop thread, and run the handlers there.

        Control events go through here because handlers naturally reach for asyncio
        — every example in this repo sets an ``asyncio.Event`` from ``on_status`` —
        and ``Event.set()``, ``Queue.put_nowait()`` and friends are not thread-safe.
        Called directly from the native control thread, they mutate loop state from
        outside the loop: it works almost every time and fails under a race, which is
        the worst way for a bug to behave.

        Media events deliberately do *not* come through here; see the note on
        ``_on_frame``.
        """
        if not self._handlers.get(event):
            return

        loop = self._loop
        if loop is not None and not loop.is_closed():
            try:
                loop.call_soon_threadsafe(self._fire, event, *args)
                return
            except RuntimeError:
                # Closed between the check and the call.
                pass

        # No loop to marshal onto — the client was built outside one, or it has since
        # shut down. Running inline is all that is left, and there is no loop state
        # left to corrupt either.
        _log.debug("no running loop for %r; dispatching on the callback thread", event)
        self._fire(event, *args)

    # ------------------------------------------------------------------
    # Lifecycle
    # ------------------------------------------------------------------

    def _create_handle(self) -> None:
        lib = get_lib()
        # get_running_loop, not get_event_loop: the latter is deprecated and raises
        # without a running loop from 3.12. _create_handle is only reached from
        # connect(), so there is always one.
        self._loop = asyncio.get_running_loop()

        # Callbacks capture self weakly, so a registered handler cannot be what keeps
        # the client alive.
        weak = weakref.ref(self)

        def _on_status(status_bytes: bytes, _ud: Any) -> None:
            r = weak()
            if r is None:
                return
            status = status_bytes.decode() if status_bytes else "disconnected"
            r._fire_on_loop("status_changed", status)

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
                r._fire_on_loop("error", err)
            except Exception:
                pass

        def _on_message(json_bytes: bytes, _ud: Any) -> None:
            r = weak()
            if r is None:
                return
            try:
                r._fire_on_loop("message", json.loads(json_bytes))
            except Exception:
                pass

        def _on_runtime_message(json_bytes: bytes, _ud: Any) -> None:
            r = weak()
            if r is None:
                return
            try:
                r._fire_on_loop("runtime_message", json.loads(json_bytes))
            except Exception:
                pass

        def _on_track(name_bytes: bytes, mid_bytes: bytes | None, _ud: Any) -> None:
            r = weak()
            if r is None:
                return
            name = name_bytes.decode() if name_bytes else ""
            mid = mid_bytes.decode() if mid_bytes else None
            r._fire_on_loop("track_received", name, mid)

        def _on_capabilities(json_bytes: bytes, _ud: Any) -> None:
            r = weak()
            if r is None:
                return
            try:
                r._fire_on_loop("capabilities_received", json.loads(json_bytes))
            except Exception:
                pass

        def _on_session_id(sid_bytes: bytes | None, _ud: Any) -> None:
            r = weak()
            if r is None:
                return
            sid = sid_bytes.decode() if sid_bytes else None
            r._fire_on_loop("session_id_changed", sid)

        def _on_frame(
            data_ptr: int,
            width: int,
            height: int,
            frame_id: int,
            timestamp_us: int,
            user_data_ptr: int,
            user_data_len: int,
            _ud: Any,
        ) -> None:
            # Media runs inline on its own FFI delivery thread rather than being
            # marshalled to the loop, and that is deliberate. Blocking here is what
            # applies backpressure: the FFI keeps only the newest frame while this
            # handler runs, so a slow consumer sees fresh frames. Hand each one to
            # the loop instead and the queue simply moves into asyncio's ready queue,
            # which is unbounded — trading a bounded drop for unbounded latency and
            # memory. The cost is that a handler touching asyncio state must go
            # through loop.call_soon_threadsafe itself.
            r = weak()
            if r is None:
                return
            if not data_ptr:
                _log.debug("frame callback with a null buffer; dropping")
                return
            n = width * height * 4
            frame = (ctypes.c_uint8 * n).from_address(data_ptr)
            ud = (
                bytes((ctypes.c_uint8 * user_data_len).from_address(user_data_ptr))
                if user_data_ptr and user_data_len
                else b""
            )
            r._fire("frame", bytes(frame), width, height, frame_id, timestamp_us, ud)

        def _on_audio(
            data_ptr: int, num_samples: int, sample_rate: int, channels: int, _ud: Any
        ) -> None:
            # Inline for the same reason as _on_frame.
            r = weak()
            if r is None:
                return
            if not data_ptr:
                _log.debug("audio callback with a null buffer; dropping")
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
        # Now closable, so the atexit hook must know about it.
        _LIVE_CLIENTS.add(self)

    def _destroy_handle(self) -> None:
        if self._handle is None:
            return

        # The CFUNCTYPE objects in _cb_refs solely own the trampoline code the FFI
        # holds raw pointers to, so dropping them while the library could still
        # call one is a jump into freed memory. reactor_destroy answers whether
        # that is possible: 0 means no callback is running and none will start.
        #
        # ctypes releases the GIL for the duration of the call, so a callback
        # blocked waiting for it can finish and let destroy return.
        quiesced = get_lib().reactor_destroy(ctypes.c_void_p(self._handle)) == 0
        self._handle = None
        _LIVE_CLIENTS.discard(self)

        # Completions for operations still in flight count too: the same guarantee
        # covers them, and an abandoned await can have left entries behind.
        trampolines = self._cb_refs + list(self._pending_completions.values())
        self._callbacks_struct = None
        self._cb_refs = []
        self._pending_completions = {}

        if quiesced:
            return

        # A callback is still running and could not be waited for — a blocking
        # handler, or close() called from inside a handler. Leaking a handful of
        # small objects is the right trade against a use-after-free, so park them
        # somewhere that outlives this client and never free them.
        _ORPHANED_CALLBACKS.append((None, trampolines))
        _log.warning(
            "reactor_destroy could not confirm no callback was running; retaining "
            "%d callback trampolines for the life of the process rather than "
            "risking a use-after-free. Avoid blocking in handlers, and close the "
            "client from outside one.",
            len(trampolines),
        )

    # ------------------------------------------------------------------
    # Async completion bridge
    # ------------------------------------------------------------------

    async def _async_op(self, dispatcher: Callable[[Any], None]) -> Any:
        """Run a one-shot FFI operation and return its result.

        The trampoline's lifetime is the delicate part. The FFI promises to call it
        exactly once, but says nothing about *when* relative to this coroutine: an
        ``asyncio.wait_for`` that times out, a ``task.cancel()``, or a Ctrl-C unwinds
        this frame while the operation is still in flight. So ownership sits on the
        client, keyed by id, and only the completion itself takes it out. A cancelled
        await leaves the entry in place, which is a bounded leak until the call fires
        — the previous arrangement kept it in a local and left the library holding a
        pointer to freed memory instead.
        """
        loop = asyncio.get_running_loop()
        future: asyncio.Future = loop.create_future()
        pending = self._pending_completions
        holder: list[Any] = []

        def _cb(ok: int, result_json: bytes | None, error_msg: bytes | None, _ud: Any) -> None:
            # Runs on the FFI's control thread.
            try:
                if ok:
                    payload = (
                        json.loads(result_json) if result_json and result_json != b"{}" else None
                    )
                    _settle_from_foreign_thread(loop, future, payload, None)
                else:
                    msg = error_msg.decode() if error_msg else "unknown error"
                    _settle_from_foreign_thread(loop, future, None, ReactorFFIError(msg))
            finally:
                # Fired exactly once, so the trampoline has no further use.
                if holder:
                    pending.pop(id(holder[0]), None)

        fn = COMPLETION_FN(_cb)
        holder.append(fn)
        pending[id(fn)] = fn

        dispatcher(fn)
        return await future

    # ------------------------------------------------------------------
    # Connection lifecycle
    # ------------------------------------------------------------------

    async def connect(self, *, session_id: str | None = None) -> None:
        """Connect: create a session and establish WebRTC transport."""
        token_changed = await self._resolve_token(session_id)
        if token_changed and self._handle is not None:
            # The native client is handed its token when it is created, so a re-minted
            # one only takes effect on a fresh handle. Registered handlers live on this
            # object and survive; only the native side is rebuilt.
            self._destroy_handle()
        if self._handle is None:
            self._create_handle()
        lib = get_lib()
        sid = session_id.encode() if session_id else None
        handle = self._handle

        await self._async_op(lambda fn: lib.reactor_connect(ctypes.c_void_p(handle), sid, fn, None))

    async def reconnect(self) -> None:
        """Reconnect to the current session after a transient failure."""
        self._require_handle()
        handle = self._handle
        lib = get_lib()
        await self._async_op(lambda fn: lib.reactor_reconnect(ctypes.c_void_p(handle), fn, None))

    async def disconnect(self) -> None:
        """Gracefully disconnect (session is preserved for reconnect)."""
        if self._handle is None:
            return
        handle = self._handle
        lib = get_lib()
        await self._async_op(lambda fn: lib.reactor_disconnect(ctypes.c_void_p(handle), fn, None))

    def close(self) -> None:
        """Synchronously destroy the underlying handle."""
        self._destroy_handle()

    # ------------------------------------------------------------------
    # Messaging
    # ------------------------------------------------------------------

    async def _resolve_token(self, session_id: str | None) -> bool:
        """Turn an API key into a JWT, if that is what we were given.

        Scoped to this model when we are creating the session, which is the safer
        default: such a token can only start sessions on this model, so a leak is worth
        that rather than everything the key can reach. Adopting a session created
        elsewhere needs the broader token, because a scoped one cannot reach a session
        it did not create.

        So the scope depends on the *call*, not on the client: a token minted for one
        connect is not necessarily right for the next, and this mints again when the
        requirement changes. Caching regardless would quietly hand a model-scoped token
        to a connect that needs an unscoped one.

        The exchange is a blocking HTTP call, so it runs in a thread rather than
        stalling the loop. Skipped in local mode, which does not authenticate, and for a
        token the caller supplied, which is theirs rather than ours to replace.

        Returns True when the token changed, since the native client is handed its token
        at creation and a new one only reaches it through a new handle.
        """
        if self._local or self._api_key is None or self._caller_supplied_jwt:
            return False

        wanted = [self._model_name] if session_id is None else None
        if self._jwt is not None and self._minted_for == wanted:
            return False

        self._jwt = await asyncio.to_thread(fetch_jwt, self._api_key, self._api_url, models=wanted)
        self._minted_for = wanted
        return True

    def send_command(
        self,
        command: str,
        data: Any,
        scope: str | MessageScope = "application",
    ) -> CommandResult:
        """Send a fire-and-forget command. Returns 0 on success, -1 on error.

        The result is awaitable as well as an int, so ``await send_command(...)`` works
        for code written when this was a coroutine. The send happens either way, before
        this returns.
        """
        self._require_handle()
        lib = get_lib()
        args_json = json.dumps(data).encode()
        # A MessageScope is a str subclass, so this covers both it and a bare string.
        if scope == MessageScope.RUNTIME:
            return CommandResult(
                lib.reactor_send_runtime_command(
                    ctypes.c_void_p(self._handle),
                    command.encode(),
                    args_json,
                )
            )
        return CommandResult(
            lib.reactor_send_command(
                ctypes.c_void_p(self._handle),
                command.encode(),
                args_json,
            )
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
        return get_lib().reactor_unpublish_track(ctypes.c_void_p(self._handle), name.encode())

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
        user_data: bytes | None = None,
    ) -> None:
        """Push a raw BGRA video frame into a named sendonly track.

        Pass ``user_data`` to tag the frame; it reaches the model as that frame's
        metadata. The bytes are sent as they are — JSON, protobuf or anything
        else is between you and the model.

        A tag is dropped unless the far end declared that it reads them, so
        tagging is safe whatever the model was built against.
        """
        self._require_handle()
        buf = (ctypes.c_uint8 * len(data)).from_buffer_copy(data)
        if user_data:
            tag = (ctypes.c_uint8 * len(user_data)).from_buffer_copy(user_data)
            get_lib().reactor_push_video_frame_with_metadata(
                ctypes.c_void_p(self._handle),
                track_name.encode(),
                buf,
                ctypes.c_uint32(width),
                ctypes.c_uint32(height),
                tag,
                ctypes.c_uint32(len(user_data)),
            )
            return
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
    def status(self) -> ReactorStatus:
        """Current status.

        A `ReactorStatus`, which is a `str` enum — so it compares equal to
        ``"ready"`` as readily as to ``ReactorStatus.READY``, and ``.value`` gives the
        string.
        """
        if self._handle is None:
            return ReactorStatus.DISCONNECTED
        raw = get_lib().reactor_status(ctypes.c_void_p(self._handle))
        return ReactorStatus(raw.decode()) if raw else ReactorStatus.DISCONNECTED

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

    # Method forms of the two properties above. Kept because code written against
    # earlier releases calls them, and because there is no cost to having both.
    def get_status(self) -> ReactorStatus:
        """Current status. Same as the `status` property."""
        return self.status

    def get_session_id(self) -> str | None:
        """Current session ID. Same as the `session_id` property."""
        return self.session_id

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

    def __del__(self) -> None:
        """Last resort for a client dropped without close().

        Deliberately minimal. __del__ runs at whatever moment the collector picks,
        including late in interpreter shutdown when module globals this method needs
        may already be None — hence the bare guard. The orderly path is the atexit
        hook, or `async with`.
        """
        try:
            if getattr(self, "_handle", None) is not None:
                self._destroy_handle()
        except Exception:  # pragma: no cover - interpreter teardown
            pass

    # ------------------------------------------------------------------
    # Internals
    # ------------------------------------------------------------------

    def _require_handle(self) -> None:
        if self._handle is None:
            raise RuntimeError("Reactor handle not created — call connect() first")
