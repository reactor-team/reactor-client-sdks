"""The `Track` object: one named media slot on the session, with its own methods.

A track is not something the client creates. The model declares its tracks — name,
kind and direction — and the session negotiates every one of them at connect time.
So a `Track` here is a handle onto a slot that already exists, obtained from
:meth:`Reactor.track` or :attr:`Reactor.tracks`, and it knows which slot it is:
calling something the direction does not allow raises rather than silently doing
nothing.

One type covers both directions and both kinds, because the operations are the same
operations either way::

    camera = reactor.track("camera")       # sendonly video
    await camera.publish()
    camera.push_frame(rgb_array)

    output = reactor.track("output")       # recvonly video
    @output.on_frame
    def render(frame): ...
    await output.pause()

There is no `push_video_frame` / `push_audio_frame` split, and no
`on_video_frame` / `on_audio_frame` split: the track already knows its kind, so the
caller does not have to say it twice.
"""

from __future__ import annotations

import logging
import weakref
from collections.abc import Callable
from enum import Enum
from typing import TYPE_CHECKING, Any, overload

from ._media import (
    _bgra_to_rgb_array,
    _is_array,
    _pcm_to_array,
    _positional_arity,
    _rgb_array_to_bgra,
)

if TYPE_CHECKING:  # pragma: no cover - types only
    # Imported for annotations only, so numpy stays an optional dependency: a
    # checker resolves these, and nothing at run time ever looks them up.
    import numpy as np
    from numpy.typing import NDArray

    from .client import Reactor

_log = logging.getLogger(__name__)


class TrackKind(str, Enum):
    """What a track carries. A `str` enum, so it compares equal to `"video"`."""

    VIDEO = "video"
    AUDIO = "audio"


class TrackDirection(str, Enum):
    """Which way a track flows, from the client's point of view.

    A `str` enum, so it compares equal to `"sendonly"`.
    """

    #: Client input: you push frames into it.
    SENDONLY = "sendonly"
    #: Model output: you receive frames from it.
    RECVONLY = "recvonly"


#: What a video track's `on_frame` offers a handler, in order. A handler is given as
#: many of these as it declares parameters for, so it can ask for only what it uses.
VIDEO_FRAME_HANDLER_ARGUMENTS = ("frame", "frame_id", "timestamp_us", "user_data")

#: What an audio track's `on_frame` offers a handler, in order.
AUDIO_FRAME_HANDLER_ARGUMENTS = ("frame", "sample_rate", "num_channels")


class TrackList(list["Track"]):
    """The tracks a session declares — a plain list, with filters that chain.

    A `list` first, so everything a list does keeps working::

        for track in reactor.tracks: ...
        reactor.tracks[0]
        len(reactor.tracks)

    and filters on top, for finding the one you mean::

        reactor.tracks.with_kind(TrackKind.VIDEO)
        reactor.tracks.with_direction(TrackDirection.RECVONLY).one()

    Each filter returns another `TrackList`, so they compose in any order. A track
    whose kind or direction the session has not declared yet matches neither
    filter — there is nothing to match it against.
    """

    def with_kind(self, kind: TrackKind | str) -> TrackList:
        """Only the tracks carrying `kind` — ``"video"`` or ``"audio"``."""
        wanted = TrackKind(kind)
        return TrackList(track for track in self if track.kind is wanted)

    def with_direction(self, direction: TrackDirection | str) -> TrackList:
        """Only the tracks flowing `direction` — ``"sendonly"`` or ``"recvonly"``."""
        wanted = TrackDirection(direction)
        return TrackList(track for track in self if track.direction is wanted)

    def one(self) -> Track:
        """The single track in this list, or an error naming what is actually here.

        For the common shape — "the model's video output", "the microphone slot" —
        where the caller means one track and a second or a missing one is a
        mistake worth hearing about rather than an index that happens to work.
        """
        if len(self) == 1:
            return self[0]
        if not self:
            raise ValueError("no track matches, so there is no one() to take")
        raise ValueError(
            f"one() wants a single track and {len(self)} match: "
            f"{', '.join(track.name for track in self)}. Narrow the filter, or "
            f"pick from the list."
        )


class Track:
    """A named media track on the session. Obtain one from :meth:`Reactor.track`."""

    def __init__(
        self,
        reactor: Reactor,
        name: str,
        kind: TrackKind | None = None,
        direction: TrackDirection | None = None,
    ) -> None:
        # Weak, deliberately: a Track is a handle onto a client, not a co-owner of
        # one. Held strongly, a track parked in a capture thread would keep the whole
        # session — and its native handle — alive for as long as that thread lived.
        self._client: weakref.ref[Reactor] = weakref.ref(reactor)
        self._name = name
        self._kind = kind
        self._direction = direction
        # Handler → (what was registered on the client for it, whether it wanted
        # frames raw). `off_frame` needs the first to find it again; `_readapt`
        # needs the second to put it back the same way when the kind is learned.
        self._adapters: dict[Callable, tuple[Callable, bool]] = {}

    # ------------------------------------------------------------------
    # Identity
    # ------------------------------------------------------------------

    @property
    def name(self) -> str:
        """The declared name of this track. Never changes."""
        return self._name

    @property
    def kind(self) -> TrackKind | None:
        """`video` or `audio`, or None before the session declares its tracks."""
        self._refresh()
        return self._kind

    @property
    def direction(self) -> TrackDirection | None:
        """`sendonly` or `recvonly`, or None before the session declares its tracks."""
        self._refresh()
        return self._direction

    @property
    def mid(self) -> str | None:
        """The SDP media id, once the track has been received. None until then.

        Read from the client rather than stored here: the mid is reported from the
        FFI's own thread as tracks arrive, and it is renegotiated on a reconnect.
        """
        client = self._client()
        return client._track_mids.get(self._name) if client is not None else None

    @property
    def paused(self) -> bool:
        """Whether this track is currently paused.

        Read from the session rather than remembered here, so it stays right across
        a reconnect — recvonly tracks are resumed automatically once connected, and
        a `Track` that had cached ``True`` would go on claiming otherwise.
        """
        client = self._client()
        return client is not None and self._name in client.paused_tracks

    def __repr__(self) -> str:
        kind = self._kind.value if self._kind else "?"
        direction = self._direction.value if self._direction else "unresolved"
        return f"<Track {self._name!r} {kind} {direction}>"

    # ------------------------------------------------------------------
    # Sending
    # ------------------------------------------------------------------

    async def publish(self) -> Track:
        """Activate this sendonly slot, so frames pushed into it go on the wire.

        Returns the track, so a caller can do it in one line::

            camera = await reactor.track("camera").publish()
        """
        self._require(TrackDirection.SENDONLY, "publish()")
        await self._reactor().publish_track(self._name)
        return self

    def unpublish(self) -> int:
        """Deactivate this sendonly slot (sync). Returns 0 on success."""
        self._require(TrackDirection.SENDONLY, "unpublish()")
        return self._reactor().unpublish_track(self._name)

    # The two shapes of a frame, told apart by the one thing that already
    # distinguishes them: BGRA pixels are `uint8` and i16 PCM is `int16`. That is the
    # format, not a convention invented here — so an editor can offer the right
    # arguments without the caller restating a kind the session already declared.
    #
    # `bytes` appears in both, because raw bytes carry no dtype and could be either.
    # A checker then picks the overload the keywords fit, which is the best that can
    # be done for them; the runtime guards below catch what it cannot.

    @overload
    def push_frame(
        self,
        data: NDArray[np.uint8] | bytes,
        *,
        width: int | None = ...,
        height: int | None = ...,
        user_data: bytes | None = ...,
    ) -> None: ...

    @overload
    def push_frame(
        self,
        data: NDArray[np.int16] | bytes,
        *,
        sample_rate: int = ...,
        num_channels: int = ...,
        samples_per_channel: int | None = ...,
    ) -> None: ...

    def push_frame(
        self,
        data: Any,
        *,
        width: int | None = None,
        height: int | None = None,
        user_data: bytes | None = None,
        sample_rate: int = 48000,
        num_channels: int = 1,
        samples_per_channel: int | None = None,
    ) -> None:
        """Push one frame into this sendonly track.

        What `data` may be, and what else is needed, follows from the track's kind —
        which is why there is one method and not two.

        **Video.** Either an RGB ``numpy`` array of shape ``(height, width, 3)``,
        which is exactly what :meth:`on_frame` delivers and needs nothing else::

            camera.push_frame(frame)

        or raw BGRA bytes, which need the dimensions the array would have carried::

            camera.push_frame(bgra, width=640, height=480)

        A ``(height, width, 4)`` array is taken as BGRA already and sent untouched.
        Pass `user_data` to tag the frame; it reaches the model as that frame's
        metadata, and is dropped unless the far end declared that it reads tags.

        **Audio.** Interleaved i16 PCM, as bytes or as a ``numpy`` int16 array.
        `samples_per_channel` is worked out from the length when not given::

            mic.push_frame(pcm, sample_rate=48000, num_channels=1)

        An argument the track's kind has no use for is refused rather than ignored —
        but only where ignoring it would throw away what the caller meant. Passing
        `sample_rate` to a video track is merely redundant and is let through;
        passing `user_data` to an audio track means a tag that never goes anywhere,
        and that is an error.
        """
        self._require(TrackDirection.SENDONLY, "push_frame()")
        reactor = self._reactor()

        if self._kind is TrackKind.AUDIO:
            if user_data is not None:
                raise TypeError(
                    f"push_frame(): track {self._name!r} is audio, and an audio frame "
                    f"has nowhere to carry a tag — the wire format has no metadata "
                    f"trailer for one. Only video frames can be tagged."
                )
            pcm = data.tobytes() if _is_array(data) else bytes(data)
            if samples_per_channel is None:
                # i16 samples, interleaved across channels: two bytes each.
                samples_per_channel = len(pcm) // 2 // max(1, num_channels)
            reactor.push_audio_frame(
                self._name, pcm, samples_per_channel, sample_rate, num_channels
            )
            return

        bgra, actual_width, actual_height = _as_bgra(data, width, height, self._name)
        if _is_array(data) and (
            (width is not None and width != actual_width)
            or (height is not None and height != actual_height)
        ):
            # Dimensions that disagree with the array are not redundant, they are
            # contradictory: one of the two is what the caller believes is going on
            # the wire, and silently picking the other is how an afternoon goes.
            raise ValueError(
                f"push_frame(): track {self._name!r} was given a "
                f"{actual_width}x{actual_height} array together with width={width} "
                f"height={height}. An array carries its own shape; drop the "
                f"arguments, or pass the bytes if the other size is the intended one."
            )
        reactor.push_video_frame(self._name, bgra, actual_width, actual_height, user_data=user_data)

    # ------------------------------------------------------------------
    # Receiving
    # ------------------------------------------------------------------

    def on_frame(self, func: Callable) -> Callable:
        """Register a handler for this track's frames. Usable as a decorator.

        Only this track's frames reach it, which is why registration belongs on the
        track. The client has no equivalent: a handler fed every recvonly video
        track at once cannot tell them apart, and one that only ever worked for
        video was the wrong shape to keep.

        On a **video** track the handler is given as many of
        ``(frame, frame_id, timestamp_us, user_data)`` as it declares parameters
        for, `frame` being an RGB ``numpy`` array of shape ``(height, width, 3)``::

            @output.on_frame
            def render(frame): ...

            @output.on_frame
            def render(frame, frame_id, timestamp_us, user_data): ...

        On an **audio** track, as many of ``(frame, sample_rate, num_channels)``,
        `frame` being an int16 array of shape ``(samples, channels)``::

            @speech.on_frame
            def play(frame, sample_rate): ...

        An ``async def`` handler is scheduled on the client's loop. A handler that
        blocks holds up delivery for this track — which is what applies backpressure,
        since only the newest video frame is kept while it runs.

        Registering before the session has declared its tracks is allowed, so
        handlers can be set up before `connect()`. The direction is checked once it
        is known, and a handler on a sendonly track simply never fires.

        Requires numpy, which is not a dependency of this package. Use
        :meth:`on_raw_frame` for the same frames without the conversion.

        Returns the function unchanged, so the decorated name stays callable.
        """
        return self._register(func, raw=False)

    def on_raw_frame(self, func: Callable) -> Callable:
        """Register a handler for this track's frames as bytes. Usable as a decorator.

        Raw means unconverted, not undecoded. Every frame arrives here already
        decoded — BGRA pixels for video, interleaved i16 PCM for audio — because
        that happens in WebRTC, below this SDK; the encoded payload is never
        exposed. What :meth:`on_frame` adds on top is the numpy conversion, and
        this is the same frames without it.

        The same routing as :meth:`on_frame`, then, and the same arguments the
        client-wide events carry — so a handler written against ``on("frame", ...)``
        moves here unchanged and starts seeing one track instead of all of them::

            @output.on_raw_frame
            def forward(bgra, width, height, frame_id, timestamp_us, user_data): ...

            @speech.on_raw_frame
            def forward(pcm, num_samples, sample_rate, num_channels): ...

        Prefer this when the conversion is not wanted — forwarding the bytes
        somewhere, or counting frames — since it needs no numpy and copies nothing.
        Every argument is passed, so the handler must take them all.
        """
        return self._register(func, raw=True)

    def off_frame(self, func: Callable) -> None:
        """Unregister a handler registered with :meth:`on_frame` or :meth:`on_raw_frame`."""
        registered = self._adapters.pop(func, None)
        if registered is not None:
            self._reactor().off(self._event, registered[0])

    def _register(self, func: Callable, raw: bool) -> Callable:
        """Refuse the direction, then attach.

        The guard belongs here and not in :meth:`_attach`, because the two callers
        are asking different questions. This one is a caller registering a handler,
        where a sendonly track is a mistake worth an exception. `_readapt` is not:
        by the time it runs the handler is already registered, and the direction it
        turned out to have is news.
        """
        self._refresh()
        if self._direction is TrackDirection.SENDONLY:
            raise ValueError(
                f"track {self._name!r} is sendonly (client input), so it has no frames "
                f"to hand you. push_frame() sends into it; on_frame() applies to a "
                f"recvonly track."
            )
        self._attach(func, raw)
        return func

    def _attach(self, func: Callable, raw: bool) -> None:
        """Adapt `func` for the kind this track has now, and put it on the client."""
        if raw:
            adapter = func
        else:
            adapt = self._audio_adapter if self._kind is TrackKind.AUDIO else self._video_adapter
            adapter = adapt(func)
        self._adapters[func] = (adapter, raw)
        self._reactor().on(self._event, adapter)

    def _video_adapter(self, func: Callable) -> Callable:
        take = _positional_arity(func, len(VIDEO_FRAME_HANDLER_ARGUMENTS))

        def handler(
            bgra: bytes,
            width: int,
            height: int,
            frame_id: int,
            timestamp_us: int,
            user_data: bytes,
        ) -> Any:
            arguments = (
                _bgra_to_rgb_array(bgra, width, height),
                frame_id,
                timestamp_us,
                user_data,
            )
            # Returned rather than discarded: `func` may be `async def`, in which case
            # this is a coroutine the client needs to see in order to schedule it.
            return func(*arguments[:take])

        return handler

    def _audio_adapter(self, func: Callable) -> Callable:
        take = _positional_arity(func, len(AUDIO_FRAME_HANDLER_ARGUMENTS))

        def handler(pcm: bytes, num_samples: int, sample_rate: int, channels: int) -> Any:
            arguments = (_pcm_to_array(pcm, num_samples, channels), sample_rate, channels)
            return func(*arguments[:take])

        return handler

    @property
    def _event(self) -> str:
        """The client event this track's frames are dispatched under.

        Namespaced by track name, so the client's existing handler table does the
        routing and its error logging and `async def` scheduling come along unchanged.
        """
        kind = "audio" if self._kind is TrackKind.AUDIO else "frame"
        return f"{kind}@{self._name}"

    # ------------------------------------------------------------------
    # Pausing
    # ------------------------------------------------------------------

    async def pause(self) -> None:
        """Stop receiving this track. Frames stop arriving until :meth:`resume`."""
        self._require(TrackDirection.RECVONLY, "pause()")
        await self._reactor().pause_track(self._name)

    async def resume(self) -> None:
        """Start receiving this track again after :meth:`pause`."""
        self._require(TrackDirection.RECVONLY, "resume()")
        await self._reactor().resume_track(self._name)

    # ------------------------------------------------------------------
    # Internals
    # ------------------------------------------------------------------

    def _reactor(self) -> Reactor:
        client = self._client()
        if client is None:
            raise RuntimeError(
                f"track {self._name!r} outlived its Reactor client, which has been "
                f"garbage collected"
            )
        return client

    def _adopt(self, kind: TrackKind, direction: TrackDirection) -> None:
        """Fill in what the session declared for this track.

        Called by the client when capabilities arrive. Kind matters for more than
        display: handlers registered before it was known were adapted for video, so a
        track that turns out to be audio has to re-adapt them.
        """
        if self._kind is kind and self._direction is direction:
            return
        kind_changed = self._kind is not kind
        old_event = self._event
        self._kind, self._direction = kind, direction
        if kind_changed and self._adapters:
            self._readapt(old_event)

    def _readapt(self, old_event: str) -> None:
        """Re-register every handler for the kind this track turned out to be.

        Deliberately not through :meth:`_register`: its direction guard raises, and
        raising here would be raising out of `_sync_tracks` — which is reached from
        reading `reactor.tracks`, or refreshing some *other* track. The caller
        registering the handler is the one who can be told off; whoever happens to
        read the track list later is not.
        """
        client = self._client()
        if client is None:
            return
        registered = list(self._adapters.items())
        for _, (adapter, _raw) in registered:
            client.off(old_event, adapter)

        if self._direction is TrackDirection.SENDONLY:
            # Registered before the session said what this track was, and it turned
            # out to be one that only sends. Nothing will ever arrive, so the
            # handlers stay detached — and are said out loud once, here, rather than
            # left looking live and silently never firing.
            _log.warning(
                "track %r turned out to be sendonly, so the %d handler(s) registered "
                "on it before the session declared its tracks will never fire — a "
                "sendonly track is one you push_frame() into",
                self._name,
                len(registered),
            )
            return

        self._adapters.clear()
        for func, (_adapter, raw) in registered:
            self._attach(func, raw)

    def _refresh(self) -> None:
        """Adopt what the session declares, if it has not been adopted yet."""
        if self._direction is not None:
            return
        client = self._client()
        if client is not None:
            client._sync_tracks()

    def _require(self, direction: TrackDirection, action: str) -> None:
        """Refuse an operation the track's direction does not have.

        Deliberately loud. The whole reason for a `Track` object is that the plain
        name-based calls fail quietly — pushing into a track that is not a sendonly
        slot reaches the FFI, finds no local source, logs a warning nobody reads, and
        returns as if it had worked.
        """
        self._refresh()

        if self._direction is None:
            client = self._client()
            declared = client.tracks if client is not None else []
            if declared:
                raise ValueError(
                    f"{action}: no track named {self._name!r} in this session. "
                    f"Declared: {', '.join(sorted(t.name for t in declared))}"
                )
            raise RuntimeError(
                f"{action}: the session has not declared its tracks yet — they arrive "
                f"with the model's capabilities, shortly after connect(). Wait for "
                f"status READY, or register handlers now and call this once connected."
            )

        if self._direction is direction:
            return

        if direction is TrackDirection.SENDONLY:
            raise ValueError(
                f"{action}: track {self._name!r} is recvonly (model output), not "
                f"something you send into. Use on_frame() to receive its frames, or "
                f"pause()/resume() to control it."
            )
        raise ValueError(
            f"{action}: track {self._name!r} is sendonly (client input), not something "
            f"you receive. Use push_frame() to send into it, or unpublish() to "
            f"deactivate it."
        )


def _as_bgra(data: Any, width: int | None, height: int | None, name: str) -> tuple[bytes, int, int]:
    """Coerce whatever `push_frame` was given into BGRA bytes and its dimensions."""
    if _is_array(data):
        shape = tuple(data.shape)
        if len(shape) != 3 or shape[2] not in (3, 4):
            raise ValueError(
                f"push_frame(): track {name!r} takes an array of shape "
                f"(height, width, 3) for RGB or (height, width, 4) for BGRA; got "
                f"{shape}"
            )
        height, width = shape[0], shape[1]
        return (
            data.tobytes() if shape[2] == 4 else _rgb_array_to_bgra(data),
            width,
            height,
        )

    if width is None or height is None:
        raise ValueError(
            f"push_frame(): track {name!r} was given raw bytes, which carry no shape — "
            f"pass width= and height= too, or pass a numpy array and they are read "
            f"from it."
        )
    return bytes(data), width, height
