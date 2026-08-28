"""Reactor Python client — thin async wrapper over libreactor_ffi (ctypes)."""

from __future__ import annotations

import asyncio
import atexit
import ctypes
import json
import logging
import mimetypes
import os
import weakref
from collections.abc import Callable, Coroutine, Sequence
from dataclasses import dataclass
from enum import Enum
from typing import Any, BinaryIO, ClassVar, overload

from ._auth import DEFAULT_API_URL, fetch_jwt
from ._ffi import (
    COMPLETION_FN,
    ON_AUDIO_FN,
    ON_FRAME_FN,
    ON_STRING_FN,
    ON_TRACK_FN,
    ReactorCallbacks,
    get_lib,
)

# Re-exported from `client` as well as their own modules: all of these have been
# importable from here since before the split, and the tests reach for them by that
# path. The two media helpers have no caller left here now that the client-wide
# `on_frame` is gone — `Track` uses them — but the import path stays.
from ._media import _bgra_to_rgb_array, _positional_arity  # noqa: F401  (re-export)
from ._recording import download_clip
from .errors import ReactorError, error_for_code, error_from_payload  # noqa: F401  (re-export)
from .track import Track, TrackDirection, TrackKind, TrackList

# ---------------------------------------------------------------------------
# Public types
# ---------------------------------------------------------------------------

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


#: The synthetic audio device module, which this SDK always uses — the value
#: `reactor_create_with_adm` documents as "no capture device, no playout".
#:
#: Nothing here opens a microphone or a speaker. A sendonly audio track carries
#: only the PCM the caller pushes into it, and a model's audio arrives at that
#: track's `on_frame` for the caller to play.
#:
#: The alternative — the platform module, which captures and plays through the
#: real devices — was reachable through an `adm_mode` argument and is not any
#: more. It was the wrong thing to offer a library whose audience is scripts,
#: servers and pipelines: a model declaring a sendonly audio track was enough to
#: put the live microphone on the wire without the caller mentioning audio at
#: all. It also broke the other direction, since `push_audio_frame` feeds the
#: synthetic module — under the platform one a caller's PCM went nowhere while
#: the microphone streamed in its place, which is what `examples/push_audio.py`
#: was silently doing.
_SYNTHETIC_ADM = 0

#: What a registered handler returns: `None` from a plain function, or a coroutine from
#: an `async def` one — `_fire` inspects this to decide whether to schedule it.
_HandlerResult = Coroutine[Any, Any, None] | None


def _bitrate_arg(v: int | None) -> ctypes.c_int32:
    """Encode an optional bitrate bound for the C ABI, which has no optional int.

    `None` becomes -1, the ABI's "leave this at the WebRTC default", and -1 is
    the only value that means that. A negative is passed through to be refused
    on the other side rather than quietly clearing the bound: reading a typo as
    "remove the cap" would be the opposite of what was asked. Zero is a value
    like any other and reaches the engine.
    """
    return ctypes.c_int32(-1 if v is None else v)


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

        async with Reactor("my-model", jwt=token) as r:
            r.on("message", lambda msg: print(msg))
            await r.connect()
            await r.send_command("hello", {"text": "hi"})
    """

    def __init__(
        self,
        model_name: str,
        api_key: str | None = None,
        *,
        jwt: str | None = None,
        api_url: str = DEFAULT_API_URL,
        local: bool = False,
        max_session_duration_seconds: int | None = None,
    ) -> None:
        """
        Args:
            model_name: Model to connect to. Required.
            api_key: An API key to exchange for a token at connect time. Use one or
                the other; `jwt` wins if both are given.
            jwt: A token to authenticate with.
            api_url: Coordinator base URL. Defaults to production.
            local: Local-dev mode — relaxes TLS verification and skips auth.
            max_session_duration_seconds: Force-terminates every session a token this
                client mints creates after this many seconds (1-86400), independent of
                the token's own expiry. Only takes effect on the model-scoped token
                minted to create a session — the unscoped one minted to adopt an
                existing session ignores it, the same as the server does. Has no effect
                when `jwt` is given: that token is already minted, and this client never
                re-mints one it did not create itself.

        No audio device is ever opened — see `_SYNTHETIC_ADM`.
        """
        # Local mode means a local coordinator, so it picks the URL. The production
        # default counts as "no choice made": callers routinely compute
        # `api_url or "https://api.reactor.inc"` and pass that alongside `local=True`,
        # which without this would aim local mode at production. An api_url that is
        # anything else was a real choice and is honoured, so a local coordinator on
        # another port still works.
        if local and api_url == DEFAULT_API_URL:
            api_url = LOCAL_API_URL

        self._api_url = api_url
        self._model_name = model_name
        self._jwt = jwt
        self._api_key = api_key
        self._local = local
        self._max_session_duration_seconds = max_session_duration_seconds

        # A token the caller handed us is theirs: never replaced. One we minted from an
        # API key is ours, and `_minted_for` records the scope it was minted with, so a
        # later connect needing a different scope knows to mint again.
        self._caller_supplied_jwt = jwt is not None
        self._minted_for: list[str] | None = None

        self._handle: int | None = None

        # event handlers: event_name -> list[callable]
        self._handlers: dict[str, list[Callable]] = {}

        # Track handles, by name. Kept for the life of the client rather than
        # rebuilt per connection: a caller holds one, registers handlers on it and
        # expects both to survive a reconnect, which rebuilds the native handle.
        self._tracks: dict[str, Track] = {}

        # SDP media ids, filled in as tracks are received. Separate from the Track
        # objects because the FFI reports them from its own control thread, and a
        # plain dict assignment is the one thing safe to do from there.
        self._track_mids: dict[str, str | None] = {}

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

    #: Event -> track kind, for the two client-wide media events that no longer
    #: exist. Media is delivered per track, so these are refused at registration:
    #: accepting a handler that can never fire is the silent failure the `Track`
    #: object exists to end, and it would be one nobody could find.
    _MEDIA_EVENTS: ClassVar[dict[str, str]] = {"frame": "video", "audio": "audio"}

    def on(self, event: str, handler: Callable) -> None:
        """Register a handler for an event name.

        Media is not among them: it is delivered per track, so it is registered on
        the track — see `Track.on_frame` and `Track.on_raw_frame`.
        """
        kind = self._MEDIA_EVENTS.get(event)
        if kind is not None:
            raise ValueError(
                f"on({event!r}) does not deliver anything: media is per track, and a "
                f"single handler fed every recvonly {kind} track at once cannot tell "
                f"them apart. Register on the track instead — "
                f"reactor.track(name).on_frame for decoded frames, .on_raw_frame for "
                f"the same bytes with the same arguments. To reach the track without "
                f"naming it: "
                f'reactor.tracks.with_direction("recvonly").with_kind("{kind}").one().'
            )
        self._handlers.setdefault(event, []).append(handler)

    # ------------------------------------------------------------------
    # Decorator registration
    # ------------------------------------------------------------------
    #
    # The same events as `on`, registered by decorating. Each returns the function
    # unchanged, so the decorated name stays callable.

    def on_status(self, arg: Callable | str | Sequence[str] | None = None) -> Callable:
        """Register a handler for status changes.

        Bare, the handler receives every change::

            @reactor.on_status
            def changed(status): ...

        Given a status, it fires only on that one. The handler still receives it, even
        though it can only ever be the one asked for — that is the shape existing code
        is written to::

            @reactor.on_status(ReactorStatus.READY)
            def ready(status): ...

        Given a sequence, it fires on any status in it::

            @reactor.on_status([ReactorStatus.CONNECTING, ReactorStatus.WAITING])
            def pending(status): ...
        """
        # Bare: the decorated function arrives as `arg`.
        if callable(arg):
            func = arg

            def every(status: str) -> _HandlerResult:
                # Returned rather than discarded: `func` may be `async def`, in which
                # case this is a coroutine that `_fire` needs to see to schedule it.
                return func(ReactorStatus(status))

            self.on("status_changed", every)
            return func

        if arg is None:
            wanted: set[ReactorStatus] | None = None
        elif isinstance(arg, str):
            wanted = {ReactorStatus(arg)}
        else:
            wanted = {ReactorStatus(a) for a in arg}

        def decorator(func: Callable) -> Callable:
            def filtered(status: str) -> _HandlerResult:
                if wanted is None or ReactorStatus(status) in wanted:
                    return func(ReactorStatus(status))
                return None

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
        """Register a handler for incoming media tracks.

        Fires once per named track the session declares, handing over the
        `Track` itself — `reactor.track(name)`, already resolved — rather than
        a bare name the handler has to look up before doing anything with it.
        Not filtered by name: check `track.name` yourself if only one matters.
        """
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

        An `async def` handler returns a coroutine instead of running its body —
        calling it plainly never awaits that. Earlier releases dispatched through an
        emitter that checked for exactly this and scheduled the coroutine, so an
        `async def` handler registered the documented way has always worked; this
        restores that. `run_coroutine_threadsafe` rather than `ensure_future` because
        `_fire` also runs on the foreign FFI thread that delivers frame/audio events,
        not only on the loop thread.
        """
        for h in list(self._handlers.get(event, [])):
            try:
                result = h(*args)
                if asyncio.iscoroutine(result):
                    loop = self._loop
                    if loop is not None and not loop.is_closed():
                        future = asyncio.run_coroutine_threadsafe(result, loop)
                        # `run_coroutine_threadsafe` chains the coroutine's outcome
                        # onto this future rather than reporting it anywhere — that
                        # chaining is itself what retrieves the inner task's
                        # exception, which is what would otherwise make asyncio log
                        # "exception was never retrieved". Left alone, a raising
                        # async handler fails completely silently; this callback is
                        # what still logs it, same as a raising sync handler below.
                        future.add_done_callback(
                            lambda f, event=event, h=h: self._log_async_handler_error(event, h, f)
                        )
                    else:
                        result.close()
            except Exception:
                _log.exception("error in %r handler %r", event, h)

    @staticmethod
    def _log_async_handler_error(event: str, handler: Callable, future: Any) -> None:
        if future.cancelled():
            return
        exc = future.exception()
        if exc is not None:
            _log.error("error in %r async handler %r", event, handler, exc_info=exc)

    def _fire_on_track(self, kind: str, track: bytes | None, *args: Any) -> None:
        """Deliver a media frame to the handlers registered on its own track.

        The track name arrives from the FFI, which reports it empty when the
        transceiver could not be matched to a declared track. Such a frame has
        nowhere to go — there is no client-wide event to fall back to any more — so
        it is dropped, and said out loud at debug level rather than silently. Seeing
        these means the session negotiated a track this SDK cannot name.

        Runs on the media delivery thread, like `_fire`, and for the same reason:
        blocking here is what applies backpressure.
        """
        if not track:
            _log.debug("a %s frame arrived with no track name; dropping it", kind)
            return
        self._fire(f"{kind}@{track.decode()}", *args)

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
            if status != ReactorStatus.READY:
                # Leaving ready ends every publish that was in force. A reconnect
                # resumes recvonly tracks and stops there, so a sendonly track that
                # was published before the drop is not published after it, and the
                # far end never hears about the frames pushed into it. Forgetting
                # here is what turns that into publish() again, rather than a track
                # that claims to be live and sends into nothing.
                for track in r._tracks.values():
                    track._published = False
            r._fire_on_loop("status_changed", status)

        def _on_error(json_bytes: bytes, _ud: Any) -> None:
            r = weak()
            if r is None:
                return
            try:
                d = json.loads(json_bytes)
                code = d.get("code")
                # The specific class, not just the base: an on_error handler gets
                # the same UnauthorizedError/RateLimitedError/... a raised call
                # would, since it is the same class either way — see errors.py.
                err = error_for_code(code)(
                    d.get("message", ""),
                    code=code,
                    recoverable=d.get("recoverable", False),
                    status=d.get("status"),
                    operation=d.get("operation"),
                    retry_after_ms=d.get("retry_after_ms"),
                    timestamp_ms=d.get("timestamp_ms", 0.0),
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
            # An empty name means the transceiver could not be matched to a
            # declared track — the same case _fire_on_track drops for the same
            # reason: there is no `Track` to hand a handler for it.
            if not name:
                return
            mid = mid_bytes.decode() if mid_bytes else None
            r._track_mids[name] = mid
            try:
                track = r.track(name)
            except ValueError:
                _log.debug("track_received named a track the session does not declare: %r", name)
                return
            r._fire_on_loop("track_received", track)

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
            track_bytes: bytes | None,
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
            pixels = bytes(frame)
            r._fire_on_track(
                "frame", track_bytes, pixels, width, height, frame_id, timestamp_us, ud
            )

        def _on_audio(
            track_bytes: bytes | None,
            data_ptr: int,
            num_samples: int,
            sample_rate: int,
            channels: int,
            _ud: Any,
        ) -> None:
            # Inline for the same reason as _on_frame.
            r = weak()
            if r is None:
                return
            if not data_ptr:
                _log.debug("audio callback with a null buffer; dropping")
                return
            arr = (ctypes.c_int16 * num_samples).from_address(data_ptr)
            pcm = bytes(arr)
            r._fire_on_track("audio", track_bytes, pcm, num_samples, sample_rate, channels)

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

        # Named rather than defaulted: `reactor_create` would take the mode from
        # the REACTOR_WEBRTC_ADM environment variable, and an environment variable
        # is not something a library should let open the user's microphone.
        handle = lib.reactor_create_with_adm(
            self._api_url.encode(),
            self._model_name.encode(),
            jwt_bytes,
            local_int,
            ctypes.byref(cbs),
            ctypes.c_int(_SYNTHETIC_ADM),
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
        # Renegotiated with the next connection; the Track objects and their
        # handlers deliberately outlive the handle, but the mids do not.
        self._track_mids.clear()

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

        def _cb(ok: int, result_json: bytes | None, error_json: bytes | None, _ud: Any) -> None:
            # Runs on the FFI's control thread.
            try:
                if ok:
                    payload = (
                        json.loads(result_json) if result_json and result_json != b"{}" else None
                    )
                    _settle_from_foreign_thread(loop, future, payload, None)
                else:
                    error = error_from_payload(error_json)
                    _settle_from_foreign_thread(loop, future, None, error)
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

    async def connect(
        self,
        *,
        session_id: str | None = None,
        connection_id: int | None = None,
    ) -> None:
        """Connect: create a session and establish WebRTC transport.

        `connection_id` adopts a connection a backend already registered for
        this session — the connection-level analogue of `session_id` adopting
        an existing session. Leave it `None` to register a new one, which is
        what every single-connection client wants.
        """
        # Checked first, before anything here has a side effect: ctypes.c_uint32
        # does not raise for a value outside its range, it wraps modulo 2**32,
        # so a caller's -1 or a stray extra digit would silently adopt a
        # different, real connection instead of failing. session_id needs no
        # equivalent check — it is already a string, with no wrong-but-valid
        # uint32 a typo in it could produce.
        if connection_id is not None and not 0 <= connection_id < 2**32:
            raise ValueError(
                f"connection_id must fit in a uint32 (0 to {2**32 - 1}), got {connection_id}"
            )

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
        # A local, not inlined into the call: reactor_connect reads *cid before
        # returning (it dispatches to a tokio task, not the completion, for the
        # actual connect), so its lifetime only needs to outlast this statement —
        # but byref() needs a named object to point at, not a temporary.
        cid = ctypes.c_uint32(connection_id) if connection_id is not None else None
        handle = self._handle

        await self._async_op(
            lambda fn: lib.reactor_connect(
                ctypes.c_void_p(handle),
                sid,
                ctypes.byref(cid) if cid is not None else None,
                fn,
                None,
            )
        )

    async def reconnect(self) -> None:
        """Reconnect using the same session — after a transient failure, or from
        `ready` to deliberately cycle the connection.

        Tears down the live connection first if there is one, without ending the
        session server-side — the whole point of calling this instead of
        `disconnect()` then `connect()`. Raises `InvalidStateError` if there is no
        session to reconnect to: nothing has connected yet, or a previous
        `disconnect()` already ended it.

        Recvonly tracks come back subscribed on their own. Sendonly ones do not
        come back published — the runtime does not carry a publish across the
        reconnect — so publish again for anything you were sending::

            await reactor.reconnect()
            await camera.publish()

        `Track.published` is False until that happens, and `push_frame` raises
        rather than sending into a slot with nothing behind it.
        """
        self._require_handle()
        handle = self._handle
        lib = get_lib()
        await self._async_op(lambda fn: lib.reactor_reconnect(ctypes.c_void_p(handle), fn, None))

    async def disconnect(self) -> None:
        """Disconnect and end the session server-side. Not recoverable — call
        `reconnect()` instead of `disconnect()` + `connect()` to keep it."""
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

        self._jwt = await asyncio.to_thread(
            fetch_jwt,
            self._api_key,
            self._api_url,
            models=wanted,
            max_session_duration_seconds=self._max_session_duration_seconds,
        )
        self._minted_for = wanted
        return True

    async def send_command(self, command: str, data: Any) -> dict[str, Any] | None:
        """Send a command and wait for its correlated reply: ``{type, data}``.

        Returns ``None`` if the handler ran and acknowledged the command but
        returned no message — e.g. an auto-generated ``set_<field>`` setter.

        A ``FileRef`` (from `upload_file`) may be passed as a top-level value in
        ``data``, alongside regular parameters — it is pulled out and sent as a
        separate upload reference rather than embedded in the JSON payload::

            ref = await reactor.upload_file("photo.jpg")
            await reactor.send_command("set_image", {"image": ref})

        Only top-level values are inspected — a `FileRef` nested inside a list or
        another dict is not detected and is left for `json.dumps` to reject.

        To fire a command without waiting on the reply, schedule the call
        instead of awaiting it directly, e.g. ``asyncio.create_task(
        reactor.send_command(...))`` — keep a reference to the task so it
        isn't garbage-collected before it completes.
        """
        self._require_handle()
        handle = self._handle
        lib = get_lib()

        uploads: dict[str, Any] = {}
        if isinstance(data, dict):
            scalars = {}
            for key, value in data.items():
                if isinstance(value, FileRef):
                    uploads[key] = {
                        "upload_id": value.upload_id,
                        "name": value.name,
                        "mime_type": value.mime_type,
                        "size": value.size,
                    }
                else:
                    scalars[key] = value
            data = scalars

        args_json = json.dumps(data).encode()
        uploads_json = json.dumps(uploads).encode() if uploads else None
        return await self._async_op(
            lambda fn: lib.reactor_send_command(
                ctypes.c_void_p(handle), command.encode(), args_json, uploads_json, fn, None
            )
        )

    # ------------------------------------------------------------------
    # Schema
    # ------------------------------------------------------------------

    async def request_schema(self) -> dict[str, Any]:
        """Request the model's command schema, as an OpenAPI document."""
        self._require_handle()
        handle = self._handle
        lib = get_lib()
        return await self._async_op(
            lambda fn: lib.reactor_request_schema(ctypes.c_void_p(handle), fn, None)
        )

    # ------------------------------------------------------------------
    # Track control
    # ------------------------------------------------------------------

    def track(self, name: str) -> Track:
        """The named track, as a `Track` — the object form of everything below.

        A `Track` knows its own kind and direction, so it can refuse what the
        direction does not allow instead of failing quietly the way a wrong name
        does here::

            camera = reactor.track("camera")
            await camera.publish()
            camera.push_frame(frame)

        Raises `ValueError` for a name the session does not declare, naming the ones
        it does. Before the session has declared anything — which happens shortly
        after `connect()`, with the model's capabilities — any name is accepted, so
        that handlers can be registered ahead of connecting; the check happens as
        soon as the declaration arrives.

        The same object comes back every time for a given name, so handlers
        registered on it stay registered, including across a reconnect.
        """
        # A track already resolved needs no round trip: what the session declares
        # does not change within a session, and this is on the path of anyone who
        # looks a track up per frame rather than holding on to it.
        existing = self._tracks.get(name)
        if existing is not None and existing.direction is not None:
            return existing

        declared = self._sync_tracks()
        existing = self._tracks.get(name)
        if existing is not None:
            return existing
        if declared:
            raise ValueError(
                f"no track named {name!r} in this session. Declared: "
                f"{', '.join(sorted(t.name for t in declared))}"
            )
        track = Track(self, name)
        self._tracks[name] = track
        return track

    @property
    def tracks(self) -> TrackList:
        """Every track the session declares, in declaration order.

        A list, so it iterates and indexes like one, with filters that chain::

            reactor.tracks.with_kind(TrackKind.VIDEO)
            reactor.tracks.with_direction(TrackDirection.RECVONLY).one()

        Empty until the model's capabilities arrive, shortly after `connect()`.
        """
        return self._sync_tracks()

    @property
    def paused_tracks(self) -> frozenset[str]:
        """The names of the tracks currently paused.

        Recvonly tracks are resumed automatically once connected, so this is empty
        on a healthy session until something is paused.
        """
        raw = self._read_string(lambda lib, handle: lib.reactor_paused_tracks(handle))
        try:
            return frozenset(json.loads(raw)) if raw else frozenset()
        except ValueError:
            _log.debug("could not decode the paused-track list")
            return frozenset()

    def _sync_tracks(self) -> TrackList:
        """Adopt what the session declares onto the `Track` registry.

        The registry is only ever added to. A disconnect empties the native list,
        and a `Track` the caller is holding must not lose what it knows because of
        that — it is the same track when the session comes back.
        """
        raw = self._read_string(lambda lib, handle: lib.reactor_tracks(handle))
        try:
            declared = json.loads(raw) if raw else []
        except ValueError:
            _log.debug("could not decode the declared-track list")
            return TrackList()

        tracks = TrackList()
        for entry in declared:
            name = entry.get("name")
            if not name:
                continue
            track = self._tracks.get(name)
            if track is None:
                track = Track(self, name)
                self._tracks[name] = track
            try:
                track._adopt(TrackKind(entry["kind"]), TrackDirection(entry["direction"]))
            except (KeyError, ValueError):
                # A kind or direction this release does not know. The track is still
                # real and still nameable; it just cannot be typed.
                _log.debug("track %r declared with an unrecognised shape: %r", name, entry)
            tracks.append(track)
        return tracks

    def _read_string(self, call: Callable[[Any, Any], int]) -> bytes | None:
        """Run an FFI getter that returns a heap string, and free it.

        Returns None when there is no handle or the call declined to answer.
        """
        if self._handle is None:
            return None
        lib = get_lib()
        ptr = call(lib, ctypes.c_void_p(self._handle))
        if not ptr:
            return None
        try:
            return ctypes.cast(ptr, ctypes.c_char_p).value
        finally:
            lib.reactor_free_string(ctypes.c_void_p(ptr))

    async def publish_track(self, name: str) -> Track:
        """Activate a named sendonly track slot.

        Returns the slot as a `Track`, so the frames can go straight into it::

            camera = await reactor.publish_track("camera")
            camera.push_frame(frame)
        """
        self._require_handle()
        handle = self._handle
        lib = get_lib()
        name_b = name.encode()
        await self._async_op(
            lambda fn: lib.reactor_publish_track(ctypes.c_void_p(handle), name_b, fn, None)
        )
        track = self.track(name)
        track._published = True
        return track

    def unpublish_track(self, name: str) -> None:
        """Deactivate a sendonly track (sync — no network round trip, only a
        local status check and a fire-and-forget notification).

        Failure is logged, not raised: unpublish is commonly the last thing a
        cleanup path does, often from a `finally` block, and raising there
        would replace whatever exception was already propagating instead of
        just adding information to it. Check the logs (`reactor_sdk` at
        `WARNING`) if a track seems to have stayed published.
        """
        self._require_handle()
        raw = self._read_string(
            lambda lib, handle: lib.reactor_unpublish_track(handle, name.encode())
        )
        if raw is not None:
            # Deliberately still published as far as this side is concerned. The
            # notification is what tells the far end to stop, and a send that failed
            # while the session was otherwise fine did not send it — the track is
            # still up. Clearing here would make `Track.unpublish()` a no-op on the
            # retry, which is the one call that could still put it right.
            _log.warning("unpublish_track(%r) failed: %s", name, error_from_payload(raw))
            return
        track = self._tracks.get(name)
        if track is not None:
            track._published = False

    async def set_bitrate(
        self,
        *,
        min_bps: int | None = None,
        start_bps: int | None = None,
        max_bps: int | None = None,
    ) -> None:
        """Bound what this *connection* may allocate, in bits per second.

        There are two bitrate ceilings and they are conjunctive — the lower one
        wins. This is the connection-wide one, which bounds the congestion
        controller's whole budget. `Track.set_bitrate` bounds one sender's share
        of it, and that is the one that lifts WebRTC's 2.5 Mbps video default::

            await reactor.set_bitrate(start_bps=4_000_000, max_bps=12_000_000)
            await reactor.track("camera").set_bitrate(max_bps=8_000_000)

        Raising `max_bps` alone will not make a video track exceed 2.5 Mbps.

        - `min_bps` — a floor for the congestion controller; it will not drop
          below this even on a poor estimate. A floor above what the link can
          sustain trades graceful degradation for a fixed send rate, so choose
          it deliberately.
        - `start_bps` — the initial encoder target. WebRTC starts at ~300 kbps
          and ramps, which is visible as a few seconds of soft video. Set this
          near your expected steady state to skip the ramp.
        - `max_bps` — a ceiling on the whole connection.

        Needs a connected client, and the bounds are remembered from there on:
        they survive a reconnect, though not the handle being rebuilt on a
        re-minted token. `None` leaves a bound at the WebRTC default.

        The catch is `start_bps`, which exists to skip a ramp that happens during
        connection setup — by the time `connect()` resolves, the ramp is over.
        Reaching it needs the handle to exist before `connect()`, which this
        binding does not currently offer. See REA-5730.
        """
        self._require_handle()
        handle = self._handle
        lib = get_lib()
        args = (_bitrate_arg(min_bps), _bitrate_arg(start_bps), _bitrate_arg(max_bps))
        await self._async_op(
            lambda fn: lib.reactor_set_bitrate(ctypes.c_void_p(handle), *args, fn, None)
        )

    # Pausing and frame push are reached through `Track` — `reactor.track(name)`,
    # or `reactor.tracks`. They stay here as the plumbing the track methods call,
    # private because a name-based copy of each was a second way to say the same
    # thing that could not check what it was being asked to do: a wrong name, or a
    # track pointing the other way, went to the FFI and came back looking fine.

    async def _pause_track(self, name: str) -> None:
        """Pause receiving a named track."""
        self._require_handle()
        handle = self._handle
        lib = get_lib()
        name_b = name.encode()
        await self._async_op(
            lambda fn: lib.reactor_pause_track(ctypes.c_void_p(handle), name_b, fn, None)
        )

    async def _resume_track(self, name: str) -> None:
        """Resume receiving a named track."""
        self._require_handle()
        handle = self._handle
        lib = get_lib()
        name_b = name.encode()
        await self._async_op(
            lambda fn: lib.reactor_resume_track(ctypes.c_void_p(handle), name_b, fn, None)
        )

    async def _set_track_bitrate(self, name: str, min_bps: int | None, max_bps: int | None) -> None:
        """Bound one sender's bitrate. Reached through `Track.set_bitrate`."""
        self._require_handle()
        handle = self._handle
        lib = get_lib()
        name_b = name.encode()
        args = (_bitrate_arg(min_bps), _bitrate_arg(max_bps))
        await self._async_op(
            lambda fn: lib.reactor_set_track_bitrate(
                ctypes.c_void_p(handle), name_b, *args, fn, None
            )
        )

    # ------------------------------------------------------------------
    # Frame push
    # ------------------------------------------------------------------

    def _push_video_frame(
        self,
        track_name: str,
        data: bytes,
        width: int,
        height: int,
        user_data: bytes | None = None,
        capture_time_us: int | None = None,
    ) -> None:
        """Push a raw BGRA video frame into a named sendonly track.

        Pass ``user_data`` to tag the frame; it reaches the model as that frame's
        metadata. The bytes are sent as they are — JSON, protobuf or anything
        else is between you and the model.

        A tag is dropped unless the far end declared that it reads them, so
        tagging is safe whatever the model was built against.

        Pass ``capture_time_us`` to stamp the frame with the time it was captured
        instead of the time it is pushed. Tagging and stamping are independent:
        either, both, or neither.
        """
        self._require_handle()
        buf = (ctypes.c_uint8 * len(data)).from_buffer_copy(data)
        tag_len = len(user_data) if user_data else 0
        tag = (ctypes.c_uint8 * tag_len).from_buffer_copy(user_data) if user_data else None
        # Three exports, one per combination the C ABI has a symbol for. The
        # stamped one carries the tag too, so it covers stamped-and-untagged.
        if capture_time_us is not None:
            get_lib().reactor_push_video_frame_with_metadata_at(
                ctypes.c_void_p(self._handle),
                track_name.encode(),
                buf,
                ctypes.c_uint32(width),
                ctypes.c_uint32(height),
                tag,
                ctypes.c_uint32(tag_len),
                ctypes.c_int64(capture_time_us),
            )
            return
        if tag is not None:
            get_lib().reactor_push_video_frame_with_metadata(
                ctypes.c_void_p(self._handle),
                track_name.encode(),
                buf,
                ctypes.c_uint32(width),
                ctypes.c_uint32(height),
                tag,
                ctypes.c_uint32(tag_len),
            )
            return
        get_lib().reactor_push_video_frame(
            ctypes.c_void_p(self._handle),
            track_name.encode(),
            buf,
            ctypes.c_uint32(width),
            ctypes.c_uint32(height),
        )

    def _push_audio_frame(
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

    @overload
    async def download(
        self,
        clip: Clip,
        path: None = None,
        *,
        on_progress: Callable[[int, int], None] | None = None,
    ) -> bytes: ...
    @overload
    async def download(
        self,
        clip: Clip,
        path: str | os.PathLike,
        *,
        on_progress: Callable[[int, int], None] | None = None,
    ) -> None: ...
    async def download(
        self,
        clip: Clip,
        path: str | os.PathLike | None = None,
        *,
        on_progress: Callable[[int, int], None] | None = None,
        ready_timeout: float | None = None,
    ) -> bytes | None:
        """Download a `Clip` this client asked for, with this client's token.

        The coordinator serves playlists and segments behind auth, so the
        module-level `download_clip()` needs a `jwt=` handed to it. This is the
        same call with the one already in hand.

        It also waits without a deadline, where the module-level function
        defaults to a bounded one. A clip becomes ready because the model keeps
        generating, and this client knows whether it still is: the wait ends by
        itself when the session does. Any number here would instead be a guess
        about how fast the model generates — `predicted_ready_at_ms` is the
        runtime adding *media* seconds to a wall clock, so it is only right for a
        model running at real-time. Pass `ready_timeout` to bound it anyway.
        """
        return await download_clip(
            clip,
            path,
            jwt=self._jwt,
            on_progress=on_progress,
            ready_timeout=ready_timeout,
            while_live=lambda: self.status == ReactorStatus.READY,
        )

    @overload
    async def download_clip(
        self,
        duration_seconds: float,
        path: None = None,
        *,
        on_progress: Callable[[int, int], None] | None = None,
    ) -> bytes: ...
    @overload
    async def download_clip(
        self,
        duration_seconds: float,
        path: str | os.PathLike,
        *,
        on_progress: Callable[[int, int], None] | None = None,
    ) -> None: ...
    async def download_clip(
        self,
        duration_seconds: float,
        path: str | os.PathLike | None = None,
        *,
        on_progress: Callable[[int, int], None] | None = None,
        ready_timeout: float | None = None,
    ) -> bytes | None:
        """`request_clip()` and download it, in one call.

        For when the only thing you want is the file — `request_clip()`
        directly if you also want `session_id`, the exact `start_marker` /
        `end_marker` it landed on, or want to defer, redirect, or skip the
        download depending on what it says.
        """
        clip = await self.request_clip(duration_seconds)
        return await download_clip(
            clip, path, jwt=self._jwt, on_progress=on_progress, ready_timeout=ready_timeout
        )

    @overload
    async def download_recording(
        self,
        path: None = None,
        *,
        on_progress: Callable[[int, int], None] | None = None,
    ) -> bytes: ...
    @overload
    async def download_recording(
        self,
        path: str | os.PathLike,
        *,
        on_progress: Callable[[int, int], None] | None = None,
    ) -> None: ...
    async def download_recording(
        self,
        path: str | os.PathLike | None = None,
        *,
        on_progress: Callable[[int, int], None] | None = None,
        ready_timeout: float | None = None,
    ) -> bytes | None:
        """`request_recording()` and download it, in one call.

        Prefer passing `path`: a full-session recording has no upper bound on
        length, and only the streamed-to-disk form avoids holding the whole
        thing in memory — see `download_clip()` (the module-level function).
        """
        clip = await self.request_recording()
        return await download_clip(
            clip, path, jwt=self._jwt, on_progress=on_progress, ready_timeout=ready_timeout
        )

    # ------------------------------------------------------------------
    # File upload
    # ------------------------------------------------------------------

    async def upload_file(
        self,
        file: BinaryIO | bytes | str | os.PathLike[str],
        *,
        name: str | None = None,
        mime_type: str | None = None,
    ) -> FileRef:
        """Upload a file; returns a FileRef for use in `send_command`.

        `file` accepts a path, raw bytes, or a file-like object opened in binary
        mode. `name` and `mime_type` are inferred when not given — from the path
        or the file object's own `.name`, falling back to `"upload"`; MIME type is
        guessed from that name, falling back to `"application/octet-stream"`.

        Only callable once the connection status is `"ready"` — raises
        `InvalidStateError` otherwise, the same guard `request_clip()` and
        `pause_track()` use.
        """
        self._require_handle()
        handle = self._handle
        lib = get_lib()

        # The path-based FFI call derives name/mime_type from the path itself with
        # no way to override either, so it is only used when neither is given —
        # otherwise the file is read here and sent as bytes instead, same as any
        # other override.
        if isinstance(file, (str, os.PathLike)) and name is None and mime_type is None:
            path_b = os.fspath(file).encode()
            result = await self._async_op(
                lambda fn: lib.reactor_upload_file(ctypes.c_void_p(handle), path_b, fn, None)
            )
            return FileRef(**result)

        if isinstance(file, (str, os.PathLike)):
            with open(file, "rb") as f:
                data = f.read()
            inferred_name = os.path.basename(os.fspath(file)) or "upload"
        elif isinstance(file, bytes):
            data = file
            inferred_name = "upload"
        else:
            data = file.read()
            inferred_name = os.path.basename(getattr(file, "name", "")) or "upload"

        resolved_name = name or inferred_name
        guessed_mime_type = mimetypes.guess_type(resolved_name)[0]
        resolved_mime_type = mime_type or guessed_mime_type or "application/octet-stream"
        name_b = resolved_name.encode()
        mime_type_b = resolved_mime_type.encode()
        buf = (ctypes.c_uint8 * len(data)).from_buffer_copy(data)
        result = await self._async_op(
            lambda fn: lib.reactor_upload_bytes(
                ctypes.c_void_p(handle), buf, len(data), name_b, mime_type_b, fn, None
            )
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
        sid = self._read_string(lambda lib, handle: lib.reactor_session_id(handle))
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
