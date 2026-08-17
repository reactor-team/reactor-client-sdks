"""Tests for the `Track` object.

A track is a handle onto a slot the model declared, so most of what is worth
asserting here is about *refusing*: the whole reason the object exists is that the
name-based calls it wraps fail quietly. Pushing into a recvonly track reaches the
FFI, finds no local source, logs a warning nobody reads, and returns as though it
had worked. Every guard below is one of those silent failures turned into an error.

No native library is needed: the two FFI getters a track reads are faked with real
C buffers, which exercises `_read_string`'s cast-and-free path as a side effect.
"""

from __future__ import annotations

import ctypes
import gc
import json

import numpy as np
import pytest

from reactor_sdk import Reactor, Track, TrackDirection, TrackKind

DECLARED = [
    {"name": "camera", "kind": "video", "direction": "sendonly"},
    {"name": "mic", "kind": "audio", "direction": "sendonly"},
    {"name": "output", "kind": "video", "direction": "recvonly"},
    {"name": "speech", "kind": "audio", "direction": "recvonly"},
]


class _FakeLib:
    """The two getters a `Track` reads, answering from real C memory.

    The buffers are held here for the life of the fake: `_read_string` casts the
    address it is given, so the memory has to outlive the call it came from.
    """

    def __init__(self, tracks: list[dict] | None, paused: list[str]) -> None:
        self._buffers: list[ctypes.Array] = []
        self._tracks = tracks
        self._paused = paused
        self.freed: list[int] = []
        self.unpublish_error: dict | None = None
        self.unpublished: list[str] = []

    def _string(self, payload: object) -> int:
        buffer = ctypes.create_string_buffer(json.dumps(payload).encode())
        self._buffers.append(buffer)
        return ctypes.cast(buffer, ctypes.c_void_p).value or 0

    def reactor_tracks(self, _handle: object) -> int:
        return 0 if self._tracks is None else self._string(self._tracks)

    def reactor_paused_tracks(self, _handle: object) -> int:
        return self._string(self._paused)

    def reactor_unpublish_track(self, _handle: object, name: bytes) -> int:
        self.unpublished.append(name.decode())
        return 0 if self.unpublish_error is None else self._string(self.unpublish_error)

    def reactor_free_string(self, ptr: object) -> None:
        self.freed.append(getattr(ptr, "value", ptr))


def _connected(
    monkeypatch: pytest.MonkeyPatch,
    tracks: list[dict] | None = None,
    paused: list[str] | None = None,
) -> tuple[Reactor, _FakeLib]:
    """A client with a handle, whose track getters answer from `tracks`.

    An empty `tracks` is a session that has not declared anything yet — every moment
    between `connect()` and the model's capabilities arriving. The fake is returned
    too, so a test can make the declaration land partway through.
    """
    reactor = Reactor("https://api.reactor.inc", "m")
    reactor._handle = 1234
    lib = _FakeLib(DECLARED if tracks is None else tracks, paused or [])
    monkeypatch.setattr("reactor_sdk.client.get_lib", lambda: lib)
    return reactor, lib


def _undeclared(monkeypatch: pytest.MonkeyPatch) -> Reactor:
    """A client whose session has not declared its tracks yet."""
    reactor, _ = _connected(monkeypatch, tracks=[])
    return reactor


class TestResolution:
    def test_a_declared_track_knows_its_kind_and_direction(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        reactor, _ = _connected(monkeypatch)
        camera = reactor.track("camera")
        assert camera.kind is TrackKind.VIDEO
        assert camera.direction is TrackDirection.SENDONLY

    def test_an_undeclared_name_is_refused_by_name(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """The error names what is available. A typo is the failure this whole
        object exists to catch, so it must not need a debugger to diagnose."""
        reactor, _ = _connected(monkeypatch)
        with pytest.raises(ValueError, match="no track named 'camrea'") as excinfo:
            reactor.track("camrea")
        assert "camera, mic, output, speech" in str(excinfo.value)

    def test_the_same_object_comes_back_every_time(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """Handlers are registered on the object, so a second lookup handing back a
        fresh one would silently drop them."""
        reactor, _ = _connected(monkeypatch)
        assert reactor.track("output") is reactor.track("output")

    def test_tracks_lists_every_declared_track_in_order(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        reactor, _ = _connected(monkeypatch)
        assert [t.name for t in reactor.tracks] == ["camera", "mic", "output", "speech"]

    def test_before_the_session_declares_anything_any_name_is_accepted(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """So that handlers can be registered before connect(). There is nothing to
        check the name against yet, and refusing would make the natural order —
        register, then connect — impossible."""
        reactor = _undeclared(monkeypatch)
        track = reactor.track("output")
        assert track.direction is None
        assert reactor.tracks == []

    def test_a_track_asked_for_early_is_resolved_once_declared(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        reactor, lib = _connected(monkeypatch, tracks=[])
        track = reactor.track("output")
        assert track.direction is None

        lib._tracks = DECLARED
        assert track.direction is TrackDirection.RECVONLY
        assert track.kind is TrackKind.VIDEO

    def test_a_disconnect_does_not_unlearn_a_track(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """The native list empties on teardown. A `Track` the caller is holding is
        still the same track when the session comes back, and must not lose what it
        knows in between."""
        reactor, lib = _connected(monkeypatch)
        output = reactor.track("output")
        lib._tracks = []
        assert output.direction is TrackDirection.RECVONLY

    def test_the_repr_says_what_is_known(self, monkeypatch: pytest.MonkeyPatch) -> None:
        reactor, _ = _connected(monkeypatch)
        assert repr(reactor.track("camera")) == "<Track 'camera' video sendonly>"
        assert repr(Track(reactor, "later")) == "<Track 'later' ? unresolved>"


class TestTrackList:
    """`reactor.tracks` is a list with filters, not a filter object.

    That order matters: iteration and indexing are what most callers want, and a
    fluent wrapper that had to be unwrapped first would put ceremony in front of
    the common case.
    """

    def test_it_is_a_real_list(self, monkeypatch: pytest.MonkeyPatch) -> None:
        reactor, _ = _connected(monkeypatch)
        tracks = reactor.tracks
        assert isinstance(tracks, list)
        assert len(tracks) == 4
        assert tracks[0].name == "camera"
        assert [t.name for t in tracks] == ["camera", "mic", "output", "speech"]

    def test_filtering_by_kind_and_direction(self, monkeypatch: pytest.MonkeyPatch) -> None:
        reactor, _ = _connected(monkeypatch)
        names = lambda tl: [t.name for t in tl]  # noqa: E731
        assert names(reactor.tracks.with_kind(TrackKind.VIDEO)) == ["camera", "output"]
        assert names(reactor.tracks.with_direction(TrackDirection.SENDONLY)) == ["camera", "mic"]

    def test_the_filters_chain_in_either_order(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """Each returns another list, so composition is not order-sensitive."""
        reactor, _ = _connected(monkeypatch)
        forward = reactor.tracks.with_kind("video").with_direction("recvonly")
        backward = reactor.tracks.with_direction("recvonly").with_kind("video")
        assert [t.name for t in forward] == ["output"]
        assert [t.name for t in backward] == ["output"]

    def test_a_filter_takes_the_enum_or_the_string(self, monkeypatch: pytest.MonkeyPatch) -> None:
        reactor, _ = _connected(monkeypatch)
        assert reactor.tracks.with_kind("audio") == reactor.tracks.with_kind(TrackKind.AUDIO)

    def test_an_unknown_value_is_refused_rather_than_matching_nothing(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """An empty list would read as "the model has none of those", which is a
        different and misleading answer to a typo."""
        reactor, _ = _connected(monkeypatch)
        with pytest.raises(ValueError):
            reactor.tracks.with_kind("vidoe")

    def test_one_returns_the_single_match(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """The shape that replaces the removed client-wide on_frame: the model's
        one video output, without hardcoding its name."""
        reactor, _ = _connected(monkeypatch)
        recvonly = reactor.tracks.with_direction(TrackDirection.RECVONLY)
        track = recvonly.with_kind(TrackKind.VIDEO).one()
        assert track.name == "output"

    def test_one_names_the_candidates_when_there_are_several(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        reactor, _ = _connected(monkeypatch)
        with pytest.raises(ValueError, match="2 match: camera, output") as excinfo:
            reactor.tracks.with_kind(TrackKind.VIDEO).one()
        assert "Narrow the filter" in str(excinfo.value)

    def test_one_says_so_when_nothing_matches(self, monkeypatch: pytest.MonkeyPatch) -> None:
        reactor, _ = _connected(monkeypatch)
        with pytest.raises(ValueError, match="no track matches"):
            reactor.tracks.with_kind(TrackKind.AUDIO).with_direction("sendonly").with_kind(
                TrackKind.VIDEO
            ).one()

    def test_an_unresolved_track_matches_no_filter(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """Before the session declares anything there is nothing to match against,
        so a filter is empty rather than guessing."""
        reactor, _ = _connected(monkeypatch, tracks=[])
        reactor.track("output")
        assert reactor.tracks.with_kind(TrackKind.VIDEO) == []


class TestDirectionGuards:
    """One test per silent failure the object turns into an error."""

    def test_pushing_into_a_recvonly_track_is_refused(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        reactor, _ = _connected(monkeypatch)
        with pytest.raises(ValueError, match="is recvonly"):
            reactor.track("output").push_frame(b"\x00" * 4, width=1, height=1)

    async def test_publishing_a_recvonly_track_is_refused(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        reactor, _ = _connected(monkeypatch)
        with pytest.raises(ValueError, match="is recvonly"):
            await reactor.track("output").publish()

    async def test_pausing_a_sendonly_track_is_refused(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """Pause and resume control what is being received. The sendonly
        counterpart is unpublish(), which the message points at."""
        reactor, _ = _connected(monkeypatch)
        with pytest.raises(ValueError, match="is sendonly") as excinfo:
            await reactor.track("camera").pause()
        assert "unpublish()" in str(excinfo.value)

    def test_receiving_from_a_sendonly_track_is_refused(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        reactor, _ = _connected(monkeypatch)
        with pytest.raises(ValueError, match="sendonly"):
            reactor.track("camera").on_frame(lambda frame: None)

    def test_an_unresolved_track_says_the_declaration_has_not_arrived(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """Distinct from the wrong-name error: nothing is wrong yet, the caller is
        simply early, and the message has to say which of the two it is."""
        reactor = _undeclared(monkeypatch)
        with pytest.raises(RuntimeError, match="has not declared its tracks yet"):
            reactor.track("camera").push_frame(b"\x00" * 4, width=1, height=1)

    def test_a_name_that_turns_out_to_be_undeclared_is_refused_on_use(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        reactor, lib = _connected(monkeypatch, tracks=[])
        ghost = reactor.track("ghost")
        lib._tracks = DECLARED
        with pytest.raises(ValueError, match="no track named 'ghost'"):
            ghost.push_frame(b"\x00" * 4, width=1, height=1)


class TestPushFrame:
    """One method for both kinds: the track already knows which it is."""

    def _captured(self, reactor: Reactor) -> dict:
        captured: dict = {}
        reactor.push_video_frame = lambda *a, **k: captured.update(video=(a, k))  # type: ignore[method-assign]
        reactor.push_audio_frame = lambda *a, **k: captured.update(audio=(a, k))  # type: ignore[method-assign]
        return captured

    def test_video_bytes_need_their_dimensions(self, monkeypatch: pytest.MonkeyPatch) -> None:
        reactor, _ = _connected(monkeypatch)
        with pytest.raises(ValueError, match="carry no shape"):
            reactor.track("camera").push_frame(b"\x00" * 4)

    def test_video_bytes_are_passed_through_untouched(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        reactor, _ = _connected(monkeypatch)
        captured = self._captured(reactor)
        reactor.track("camera").push_frame(b"\x01\x02\x03\x04", width=1, height=1)
        args, kwargs = captured["video"]
        assert args == ("camera", b"\x01\x02\x03\x04", 1, 1)
        assert kwargs == {"user_data": None}

    def test_an_rgb_array_carries_its_own_dimensions_and_is_converted(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """An RGB array is what on_frame delivers, so it is what push_frame has to
        accept for the round trip to close without the caller doing the conversion
        the SDK already knows how to do."""
        reactor, _ = _connected(monkeypatch)
        captured = self._captured(reactor)
        frame = np.array([[[10, 20, 30], [40, 50, 60]]], dtype=np.uint8)  # (1, 2, 3)

        reactor.track("camera").push_frame(frame)

        args, _ = captured["video"]
        assert args[0] == "camera"
        assert args[2:] == (2, 1)  # width, height — read from the shape
        assert args[1] == bytes([30, 20, 10, 255, 60, 50, 40, 255])  # BGRA, opaque

    def test_a_four_channel_array_is_taken_as_bgra_already(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        reactor, _ = _connected(monkeypatch)
        captured = self._captured(reactor)
        frame = np.array([[[1, 2, 3, 4]]], dtype=np.uint8)

        reactor.track("camera").push_frame(frame)

        args, _ = captured["video"]
        assert args[1] == bytes([1, 2, 3, 4])

    def test_an_array_of_the_wrong_shape_is_refused(self, monkeypatch: pytest.MonkeyPatch) -> None:
        reactor, _ = _connected(monkeypatch)
        with pytest.raises(ValueError, match=r"\(height, width, 3\)"):
            reactor.track("camera").push_frame(np.zeros((4, 4), dtype=np.uint8))

    def test_a_tag_reaches_the_frame(self, monkeypatch: pytest.MonkeyPatch) -> None:
        reactor, _ = _connected(monkeypatch)
        captured = self._captured(reactor)
        reactor.track("camera").push_frame(b"\x00" * 4, width=1, height=1, user_data=b"n=1")
        assert captured["video"][1] == {"user_data": b"n=1"}

    def test_a_tag_on_an_audio_track_is_refused(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """An audio frame has nowhere to carry one — the wire format has no
        metadata trailer for it. Accepting the argument and dropping it would mean
        a caller believing their frames are tagged when nothing is."""
        reactor, _ = _connected(monkeypatch)
        with pytest.raises(TypeError, match="nowhere to carry a tag"):
            reactor.track("mic").push_frame(b"\x00\x00" * 480, user_data=b"n=1")

    def test_dimensions_that_contradict_an_array_are_refused(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """Not redundant, contradictory: one of the two is what the caller thinks
        is going on the wire, and silently picking the other is unfindable."""
        reactor, _ = _connected(monkeypatch)
        frame = np.zeros((1, 2, 3), dtype=np.uint8)
        with pytest.raises(ValueError, match="carries its own shape"):
            reactor.track("camera").push_frame(frame, width=1280, height=720)

    def test_dimensions_that_agree_with_an_array_are_allowed(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """Someone porting from push_video_frame() passes both, and they agree.
        Refusing that would be pedantry."""
        reactor, _ = _connected(monkeypatch)
        captured = self._captured(reactor)
        reactor.track("camera").push_frame(np.zeros((1, 2, 3), dtype=np.uint8), width=2, height=1)
        assert captured["video"][0][2:] == (2, 1)

    def test_an_argument_of_the_other_kind_that_loses_nothing_is_let_through(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """The line the guards are drawn on: refuse where ignoring would throw away
        what the caller meant, allow where it is merely unused."""
        reactor, _ = _connected(monkeypatch)
        captured = self._captured(reactor)
        reactor.track("camera").push_frame(b"\x00" * 4, width=1, height=1, sample_rate=16000)
        assert captured["video"][0][0] == "camera"

    def test_audio_works_out_how_many_samples_it_was_given(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """i16 interleaved: the byte length already says it, so making the caller
        repeat it is a chance to get it wrong."""
        reactor, _ = _connected(monkeypatch)
        captured = self._captured(reactor)

        reactor.track("mic").push_frame(b"\x00\x00" * 480)

        assert captured["audio"][0] == ("mic", b"\x00\x00" * 480, 480, 48000, 1)

    def test_audio_sample_count_accounts_for_channels(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        reactor, _ = _connected(monkeypatch)
        captured = self._captured(reactor)

        reactor.track("mic").push_frame(b"\x00\x00" * 480, num_channels=2)

        assert captured["audio"][0][2] == 240

    def test_an_int16_array_is_accepted_as_pcm(self, monkeypatch: pytest.MonkeyPatch) -> None:
        reactor, _ = _connected(monkeypatch)
        captured = self._captured(reactor)

        reactor.track("mic").push_frame(np.zeros(240, dtype=np.int16))

        assert captured["audio"][0][2] == 240


class TestOnFrame:
    def test_only_this_track_s_frames_arrive(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """The gap this closes. Every recvonly video track decodes into one callback;
        without per-track routing a second track is indistinguishable noise."""
        reactor, _ = _connected(monkeypatch)
        seen: list[int] = []

        @reactor.track("output").on_frame
        def handler(frame) -> None:
            seen.append(int(frame[0][0][0]))

        bgra = bytes([30, 20, 10, 255])
        reactor._fire_on_track("frame", b"output", bgra, 1, 1, 0, 0, b"")
        reactor._fire_on_track("frame", b"other", bytes([1, 2, 3, 4]), 1, 1, 0, 0, b"")

        assert seen == [10]

    def test_the_client_wide_event_still_sees_everything(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """Per-track delivery is additive. `on("frame", ...)` predates it and its
        handlers expect every frame, with the argument list they were written for."""
        reactor, _ = _connected(monkeypatch)
        seen: list[tuple] = []
        reactor.on("frame", lambda *args: seen.append(args))

        reactor._fire("frame", b"\x00" * 4, 1, 1, 7, 8, b"tag")

        assert seen == [(b"\x00" * 4, 1, 1, 7, 8, b"tag")]

    def test_a_handler_is_given_as_much_as_it_asks_for(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        reactor, _ = _connected(monkeypatch)
        seen: list[tuple] = []

        @reactor.track("output").on_frame
        def handler(frame, frame_id, timestamp_us, user_data) -> None:
            seen.append((frame.shape, frame_id, timestamp_us, user_data))

        reactor._fire_on_track("frame", b"output", bytes(4 * 6), 3, 2, 42, 99, b"tag")

        assert seen == [((2, 3, 3), 42, 99, b"tag")]

    def test_an_audio_track_delivers_an_int16_array(self, monkeypatch: pytest.MonkeyPatch) -> None:
        reactor, _ = _connected(monkeypatch)
        seen: list[tuple] = []

        @reactor.track("speech").on_frame
        def handler(frame, sample_rate) -> None:
            seen.append((frame.shape, frame.dtype, sample_rate))

        pcm = np.arange(8, dtype=np.int16).tobytes()
        reactor._fire_on_track("audio", b"speech", pcm, 8, 48000, 2)

        assert seen == [((4, 2), np.dtype("int16"), 48000)]

    def test_a_frame_with_no_track_name_reaches_no_track(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """The FFI reports an empty name when a transceiver could not be matched.
        There is nothing to route it to, and the client-wide event already had it."""
        reactor, _ = _connected(monkeypatch)
        seen: list[object] = []
        reactor.track("output").on_frame(lambda frame: seen.append(frame))

        reactor._fire_on_track("frame", b"", bytes(4), 1, 1, 0, 0, b"")
        reactor._fire_on_track("frame", None, bytes(4), 1, 1, 0, 0, b"")

        assert seen == []

    def test_off_frame_unregisters(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """The handler the client holds is an adapter built around the function, so
        removing it needs the mapping — `off` with the bare function finds nothing."""
        reactor, _ = _connected(monkeypatch)
        seen: list[object] = []

        def handler(frame) -> None:
            seen.append(frame)

        track = reactor.track("output")
        track.on_frame(handler)
        track.off_frame(handler)
        reactor._fire_on_track("frame", b"output", bytes(4), 1, 1, 0, 0, b"")

        assert seen == []

    def test_raw_frames_skip_the_conversion(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """Same routing, same arguments as the client-wide event — so a handler
        written against `on("frame", ...)` moves onto a track unchanged, and one
        that only counts frames pays for no numpy."""
        reactor, _ = _connected(monkeypatch)
        seen: list[tuple] = []

        @reactor.track("output").on_raw_frame
        def handler(bgra, width, height, frame_id, timestamp_us, user_data) -> None:
            seen.append((bgra, width, height, frame_id, timestamp_us, user_data))

        reactor._fire_on_track("frame", b"output", b"\x01\x02\x03\x04", 1, 1, 7, 8, b"tag")
        reactor._fire_on_track("frame", b"other", b"\x00" * 4, 1, 1, 0, 0, b"")

        assert seen == [(b"\x01\x02\x03\x04", 1, 1, 7, 8, b"tag")]

    def test_a_raw_audio_handler_gets_the_client_wide_arguments(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        reactor, _ = _connected(monkeypatch)
        seen: list[tuple] = []

        @reactor.track("speech").on_raw_frame
        def handler(pcm, num_samples, sample_rate, num_channels) -> None:
            seen.append((len(pcm), num_samples, sample_rate, num_channels))

        reactor._fire_on_track("audio", b"speech", b"\x00" * 16, 8, 48000, 2)

        assert seen == [(16, 8, 48000, 2)]

    def test_off_frame_also_unregisters_a_raw_handler(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        reactor, _ = _connected(monkeypatch)
        seen: list[object] = []

        def handler(bgra, width, height, frame_id, timestamp_us, user_data) -> None:
            seen.append(bgra)

        track = reactor.track("output")
        track.on_raw_frame(handler)
        track.off_frame(handler)
        reactor._fire_on_track("frame", b"output", bytes(4), 1, 1, 0, 0, b"")

        assert seen == []

    def test_a_raw_handler_registered_early_stays_raw(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """Learning the kind re-registers every handler. One that asked for raw
        frames must come back raw, not quietly converted."""
        reactor, lib = _connected(monkeypatch, tracks=[])
        seen: list[tuple] = []

        @reactor.track("speech").on_raw_frame
        def handler(pcm, num_samples, sample_rate, num_channels) -> None:
            seen.append((num_samples, sample_rate))

        lib._tracks = DECLARED
        assert reactor.track("speech").kind is TrackKind.AUDIO

        reactor._fire_on_track("audio", b"speech", b"\x00" * 8, 4, 16000, 1)

        assert seen == [(4, 16000)]

    def test_a_handler_on_a_name_that_turns_out_to_be_sendonly_is_detached_loudly(
        self, monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
    ) -> None:
        """Registering before the session declares its tracks is allowed, so this
        is reachable without doing anything wrong at the time.

        Resolution used to route back through the direction guard, which raises —
        inside `_sync_tracks`, whose `except` clause then swallowed it and logged
        "declared with an unrecognised shape". Nothing was wrong with the shape.
        Anyone reading that would go looking for a protocol problem, when the real
        answer was that they had registered on a track that only sends.
        """
        reactor, lib = _connected(monkeypatch, tracks=[])
        seen: list[object] = []
        reactor.track("camera").on_frame(lambda frame: seen.append(frame))

        lib._tracks = DECLARED
        with caplog.at_level("DEBUG"):
            names = [t.name for t in reactor.tracks]

        assert "camera" in names
        assert "sendonly" in caplog.text
        assert "unrecognised shape" not in caplog.text
        # Detached, not merely inert: nothing reaches it.
        reactor._fire_on_track("frame", b"camera", bytes(4), 1, 1, 0, 0, b"")
        assert seen == []

    def test_resolving_such_a_track_does_not_raise_at_whoever_reads_the_list(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """`reactor.tracks`, and any other track's refresh, both go through
        `_sync_tracks`. Raising there would punish a reader for what a registrar
        did."""
        reactor, lib = _connected(monkeypatch, tracks=[])
        reactor.track("camera").on_frame(lambda frame: None)
        other = reactor.track("output")

        lib._tracks = DECLARED

        assert other.direction is TrackDirection.RECVONLY
        assert [t.name for t in reactor.tracks] == [t["name"] for t in DECLARED]

    def test_off_frame_still_works_on_a_detached_handler(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """The record is kept so unregistering stays symmetric with registering,
        even though there is nothing left on the client to remove."""
        reactor, lib = _connected(monkeypatch, tracks=[])

        def handler(frame: object) -> None: ...

        camera = reactor.track("camera")
        camera.on_frame(handler)
        lib._tracks = DECLARED
        reactor.tracks

        # The point: the handler is still known here. Detaching used to drop the
        # record too, so `off_frame` had nothing to find and the caller had no way
        # to tell a handler it registered from one that was never accepted.
        assert handler in camera._adapters
        camera.off_frame(handler)
        assert camera._adapters == {}

    def test_raw_frames_are_refused_on_a_sendonly_track(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        reactor, _ = _connected(monkeypatch)
        with pytest.raises(ValueError, match="sendonly"):
            reactor.track("camera").on_raw_frame(lambda *a: None)

    def test_off_frame_of_an_unregistered_handler_is_a_noop(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        reactor, _ = _connected(monkeypatch)
        reactor.track("output").off_frame(lambda frame: None)

    def test_a_handler_registered_before_the_kind_was_known_still_gets_arrays(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        """Registering early is allowed, and early means the kind is unknown — so
        the adapter was built for video. A track that turns out to be audio has to
        re-adapt, or the handler is handed a frame decoded the wrong way."""
        reactor, lib = _connected(monkeypatch, tracks=[])
        seen: list[tuple] = []

        track = reactor.track("speech")

        @track.on_frame
        def handler(frame, sample_rate) -> None:
            seen.append((frame.dtype, sample_rate))

        lib._tracks = DECLARED
        assert track.kind is TrackKind.AUDIO  # resolves, and re-adapts

        pcm = np.zeros(4, dtype=np.int16).tobytes()
        reactor._fire_on_track("audio", b"speech", pcm, 4, 16000, 1)

        assert seen == [(np.dtype("int16"), 16000)]

    async def test_an_async_handler_is_scheduled(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """Media is fired from the delivery thread, so an `async def` handler is
        only reached through the client's own coroutine scheduling."""
        import asyncio

        reactor, _ = _connected(monkeypatch)
        reactor._loop = asyncio.get_running_loop()
        seen: list[object] = []

        @reactor.track("output").on_frame
        async def handler(frame) -> None:
            seen.append(frame)

        reactor._fire_on_track("frame", b"output", bytes(4), 1, 1, 0, 0, b"")
        await asyncio.sleep(0.05)

        assert len(seen) == 1


class TestPausedState:
    def test_paused_is_read_from_the_session(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """Not remembered on the object: recvonly tracks are resumed automatically
        once connected, so a cached True would go on claiming otherwise."""
        reactor, lib = _connected(monkeypatch, paused=["output"])
        assert reactor.track("output").paused is True
        assert reactor.track("speech").paused is False

        lib._paused = []
        assert reactor.track("output").paused is False

    def test_paused_tracks_is_a_set_of_names(self, monkeypatch: pytest.MonkeyPatch) -> None:
        reactor, _ = _connected(monkeypatch, paused=["output", "speech"])
        assert reactor.paused_tracks == frozenset({"output", "speech"})

    def test_without_a_handle_nothing_is_paused(self) -> None:
        assert Reactor("https://api.reactor.inc", "m").paused_tracks == frozenset()

    def test_without_a_handle_there_are_no_tracks(self) -> None:
        assert Reactor("https://api.reactor.inc", "m").tracks == []


class TestLifetime:
    def test_a_track_does_not_keep_its_client_alive(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """A track parked in a capture thread would otherwise hold the whole
        session — and its native handle — open for as long as that thread lived."""
        reactor, _ = _connected(monkeypatch)
        track = reactor.track("camera")

        del reactor
        gc.collect()

        with pytest.raises(RuntimeError, match="outlived its Reactor client"):
            track.unpublish()

    def test_the_frees_are_paired_with_the_gets(self, monkeypatch: pytest.MonkeyPatch) -> None:
        """Every string the FFI hands over is heap-allocated and owned by us. A
        getter read on every property access is exactly where a leak would hide."""
        reactor, lib = _connected(monkeypatch)
        reactor.tracks
        reactor.paused_tracks
        assert len(lib.freed) == 2


class TestPublishTrack:
    async def test_publish_track_hands_back_the_track(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        reactor, lib = _connected(monkeypatch)
        lib.reactor_publish_track = lambda handle, name, fn, ud: fn(1, b"{}", None, None)

        track = await reactor.publish_track("camera")

        assert isinstance(track, Track)
        assert track is reactor.track("camera")

    async def test_track_publish_goes_through_the_same_call(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        reactor, lib = _connected(monkeypatch)
        names: list[bytes] = []

        def publish(handle, name, fn, ud):
            names.append(name)
            fn(1, b"{}", None, None)

        lib.reactor_publish_track = publish

        assert await reactor.track("camera").publish() is reactor.track("camera")
        assert names == [b"camera"]


class TestUnpublishTrack:
    """Sync, and a failure is logged rather than raised — see `Reactor.
    unpublish_track`'s docstring for why: it is commonly the last call in a
    `finally` block, and raising there would replace whatever exception was
    already propagating instead of adding to it."""

    def test_success_is_silent(
        self, monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
    ) -> None:
        reactor, lib = _connected(monkeypatch)

        with caplog.at_level("WARNING", logger="reactor_sdk.client"):
            reactor.unpublish_track("camera")

        assert lib.unpublished == ["camera"]
        assert caplog.records == []

    def test_track_unpublish_goes_through_the_same_call(
        self, monkeypatch: pytest.MonkeyPatch
    ) -> None:
        reactor, lib = _connected(monkeypatch)
        assert reactor.track("camera").unpublish() is None
        assert lib.unpublished == ["camera"]

    def test_failure_is_a_warning_not_an_exception(
        self, monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
    ) -> None:
        reactor, lib = _connected(monkeypatch)
        lib.unpublish_error = {
            "code": "INVALID_STATE",
            "message": "operation requires ready status, currently connecting",
            "recoverable": False,
            "operation": "unpublish_track",
        }

        with caplog.at_level("WARNING", logger="reactor_sdk.client"):
            result = reactor.unpublish_track("camera")

        assert result is None
        assert len(caplog.records) == 1
        assert "camera" in caplog.text
        assert "INVALID_STATE" in caplog.text
        assert "currently connecting" in caplog.text

    def test_track_unpublish_failure_is_also_only_a_warning(
        self, monkeypatch: pytest.MonkeyPatch, caplog: pytest.LogCaptureFixture
    ) -> None:
        reactor, lib = _connected(monkeypatch)
        lib.unpublish_error = {"code": "INVALID_STATE", "message": "not ready"}

        with caplog.at_level("WARNING", logger="reactor_sdk.client"):
            assert reactor.track("camera").unpublish() is None


def test_the_kind_and_direction_enums_are_strings() -> None:
    """Same contract as `ReactorStatus`: interchangeable with the strings the FFI
    reports, so `track.kind == "video"` works as readily as the enum member."""
    assert TrackKind.VIDEO == "video"
    assert TrackDirection.RECVONLY == "recvonly"
    assert TrackKind("audio") is TrackKind.AUDIO
