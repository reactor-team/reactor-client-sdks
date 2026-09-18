package inc.reactor.sdk.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import inc.reactor.sdk.ReactorOptions;
import inc.reactor.sdk.TrackDirection;
import inc.reactor.sdk.TrackKind;
import inc.reactor.sdk.VideoFrame;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Declared tracks, and the frames that arrive on them. */
final class TracksTest {

    private static final String THREE_TRACKS =
            "[{\"name\":\"zulu_video\",\"kind\":\"video\",\"direction\":\"recvonly\"},"
                    + "{\"name\":\"alpha_audio\",\"kind\":\"audio\",\"direction\":\"recvonly\"},"
                    + "{\"name\":\"mike_input\",\"kind\":\"video\",\"direction\":\"sendonly\"}]";

    private static ClientPeer peer(FakeNativeLibrary fake) {
        return ClientPeer.create(
                ReactorOptions.builder("https://api.example.test", "owner/model")
                        .dispatcher(Runnable::run)
                        .build(),
                arena -> Ffi.open(fake.lookup()));
    }

    @Test
    @DisplayName("declaration order survives, rather than being sorted by name")
    void declarationOrderSurvives() {
        try (FakeNativeLibrary fake = new FakeNativeLibrary()) {
            fake.tracksJson = THREE_TRACKS;
            ClientPeer client = peer(fake);

            List<String> names = client.trackDeclarations().stream()
                    .map(TrackRegistry.Declaration::name)
                    .toList();

            // Sorted alphabetically this would be [alpha_audio, mike_input, zulu_video]. The
            // session declared them in another order, and that order is what tracks().get(0) means.
            assertEquals(List.of("zulu_video", "alpha_audio", "mike_input"), names);
            client.close();
        }
    }

    @Test
    @DisplayName("kinds and directions are read as declared")
    void kindsAndDirectionsAreRead() {
        try (FakeNativeLibrary fake = new FakeNativeLibrary()) {
            fake.tracksJson = THREE_TRACKS;
            ClientPeer client = peer(fake);

            List<TrackRegistry.Declaration> declared = client.trackDeclarations();

            assertEquals(TrackKind.VIDEO, declared.get(0).kind());
            assertEquals(TrackDirection.RECVONLY, declared.get(0).direction());
            assertEquals(TrackKind.AUDIO, declared.get(1).kind());
            assertEquals(TrackDirection.SENDONLY, declared.get(2).direction());
            client.close();
        }
    }

    @Test
    @DisplayName("a read that raced an invalidation is used once and not remembered")
    void aRacedReadDoesNotBecomeTheCache() {
        try (FakeNativeLibrary fake = new FakeNativeLibrary()) {
            fake.tracksJson = "[]";
            ClientPeer client = peer(fake);

            // The race, made deterministic: the library's own read fires the event that
            // invalidates the cache, so the answer being returned is already stale by the time it
            // arrives. Hoping for this to happen would be an anecdote; causing it is a test.
            fake.duringTracksRead = () -> fake.fireCallback(
                    "on_track",
                    Ffi.Callbacks.ON_TRACK,
                    fake.cString("late_track"),
                    fake.cString("0"),
                    MemorySegment.NULL);

            assertTrue(client.trackDeclarations().isEmpty(), "the first read sees what the FFI answered");

            // The declaration arrived during that read, so the entry it produced is keyed to a
            // generation nothing matches any more. A cache that ignored generations — or keyed the
            // entry to the generation the read *finished* at — would serve that stale list here and
            // the new track would stay invisible.
            fake.duringTracksRead = null;
            fake.tracksJson = "[{\"name\":\"late_track\",\"kind\":\"video\",\"direction\":\"recvonly\"}]";

            assertEquals(1, client.trackDeclarations().size(), "the stale read was cached and hid a new track");
            client.close();
        }
    }

    @Test
    @DisplayName("a video frame reaches the track it arrived on")
    void aVideoFrameReachesItsTrack() {
        try (FakeNativeLibrary fake = new FakeNativeLibrary()) {
            fake.tracksJson = THREE_TRACKS;
            ClientPeer client = peer(fake);
            List<VideoFrame> seen = new ArrayList<>();
            client.onVideoFrame("zulu_video", seen::add);

            fireVideoFrame(fake, "zulu_video", 2, 2, 77L, 1234L);

            assertEquals(1, seen.size());
            assertEquals(2, seen.get(0).width());
            assertEquals(77L, seen.get(0).frameId());
            assertEquals(1234L, seen.get(0).timestampUs());
            client.close();
        }
    }

    @Test
    @DisplayName("a frame for a track nobody listens to is dropped, not delivered elsewhere")
    void anUnclaimedFrameIsDropped() {
        try (FakeNativeLibrary fake = new FakeNativeLibrary()) {
            fake.tracksJson = THREE_TRACKS;
            ClientPeer client = peer(fake);
            List<VideoFrame> seen = new ArrayList<>();
            client.onVideoFrame("zulu_video", seen::add);

            fireVideoFrame(fake, "a_track_nobody_declared", 2, 2, 0L, 0L);

            assertEquals(0, seen.size(), "a frame for another track must not reach this handler");
            client.close();
        }
    }

    @Test
    @DisplayName("a frame kept past its handler throws, instead of reading memory the FFI reused")
    void aFrameKeptPastItsHandlerThrows() {
        try (FakeNativeLibrary fake = new FakeNativeLibrary()) {
            fake.tracksJson = THREE_TRACKS;
            ClientPeer client = peer(fake);
            AtomicReference<VideoFrame> escaped = new AtomicReference<>();
            client.onVideoFrame("zulu_video", frame -> {
                // Reading inside the handler is fine.
                assertEquals(2 * 2 * 4, frame.pixels().byteSize());
                escaped.set(frame);
            });

            fireVideoFrame(fake, "zulu_video", 2, 2, 1L, 1L);

            // The pixels belong to the FFI and it has reused them by now. In any other binding this
            // read would be a use-after-free that reproduces under load and not in tests; here the
            // memory is scoped to the callback, so it is an exception instead.
            IllegalStateException thrown = assertThrows(
                    IllegalStateException.class, () -> escaped.get().toByteArray());
            assertTrue(thrown.getMessage().toLowerCase(java.util.Locale.ROOT).contains("closed"), thrown.getMessage());
            client.close();
        }
    }

    @Test
    @DisplayName("removing a frame handler stops it firing")
    void removingAFrameHandlerStopsIt() {
        try (FakeNativeLibrary fake = new FakeNativeLibrary()) {
            fake.tracksJson = THREE_TRACKS;
            ClientPeer client = peer(fake);
            List<VideoFrame> seen = new ArrayList<>();
            var subscription = client.onVideoFrame("zulu_video", seen::add);

            fireVideoFrame(fake, "zulu_video", 2, 2, 1L, 0L);
            subscription.close();
            fireVideoFrame(fake, "zulu_video", 2, 2, 2L, 0L);

            assertEquals(1, seen.size(), "the handler fired after it was removed");
            client.close();
        }
    }

    @Test
    @DisplayName("paused tracks are read from the FFI, and the owned string is freed")
    void pausedTracksAreReadAndFreed() {
        try (FakeNativeLibrary fake = new FakeNativeLibrary()) {
            fake.pausedJson = "[\"alpha_audio\",\"zulu_video\"]";
            ClientPeer client = peer(fake);

            assertEquals(java.util.Set.of("alpha_audio", "zulu_video"), client.pausedTracks());
            assertEquals(1, fake.freeCallCount());
            assertTrue(fake.ownedStringsFreedExactlyOnce());
            client.close();
        }
    }

    private static void fireVideoFrame(
            FakeNativeLibrary fake, String track, int width, int height, long frameId, long timestampUs) {
        MemorySegment pixels = fake.allocate((long) width * height * 4);
        fake.fireCallback(
                "on_frame",
                Ffi.Callbacks.ON_FRAME,
                fake.cString(track),
                pixels,
                width,
                height,
                frameId,
                timestampUs,
                MemorySegment.NULL,
                0,
                MemorySegment.NULL);
    }
}
