package inc.reactor.sdk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import inc.reactor.sdk.internal.FakeNativeLibrary;
import inc.reactor.sdk.internal.FakeNativeLibrary.EncodedPush;
import inc.reactor.sdk.internal.Ffi;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Encoded video in a step: {@link Track#pushEncodedFrame}, which buffers an H.264 access unit as it
 * is, and the media a {@link Reactor#step(JsonValue, List, Map) step} carries of its own — through
 * the binding's real downcall against a fake library.
 */
final class EncodedFramesTest {

    private static final String TRACKS = "[{\"name\":\"camera_in\",\"kind\":\"video\",\"direction\":\"sendonly\"},"
            + "{\"name\":\"mic_in\",\"kind\":\"audio\",\"direction\":\"sendonly\"},"
            + "{\"name\":\"main_video\",\"kind\":\"video\",\"direction\":\"recvonly\"}]";

    private static final byte[] IDR = {0, 0, 0, 1, 0x65, 1, 2, 3};
    private static final byte[] P_FRAME = {0, 0, 0, 1, 0x41, 9};

    private FakeNativeLibrary fake;
    private Reactor reactor;

    @BeforeEach
    void openSteppedClient() {
        fake = new FakeNativeLibrary();
        fake.tracksJson = TRACKS;
        reactor = open(fake, Generation.STEPPED);
    }

    @AfterEach
    void closeClient() {
        reactor.close();
        fake.close();
    }

    // ── Track.pushEncodedFrame ──────────────────────────────────────────────

    @Test
    @DisplayName("an encoded frame reaches the library untouched, with its encoding, size, tag and capture time")
    void anEncodedFrameReachesTheLibrary() {
        Track camera = published("camera_in");

        camera.pushEncodedFrame(EncodedFrame.builder(IDR, 1280, 720)
                .userData(new byte[] {7})
                .captureTimeUs(9L)
                .build());
        camera.pushEncodedFrame(P_FRAME, 1280, 720);

        EncodedPush first = assertInstanceOf(EncodedPush.class, fake.pushOrder.get(0));
        assertEquals("camera_in", first.track());
        assertEquals(Ffi.FRAME_ENCODING_H264, first.encoding());
        assertArrayEquals(IDR, first.data());
        assertEquals(1280, first.width());
        assertEquals(720, first.height());
        assertArrayEquals(new byte[] {7}, first.userData());
        assertTrue(first.hasCaptureTime());
        assertEquals(9L, first.captureTimeUs());

        EncodedPush second = assertInstanceOf(EncodedPush.class, fake.pushOrder.get(1));
        assertArrayEquals(P_FRAME, second.data());
        assertNull(second.userData(), "an untagged frame passes NULL, not an empty tag");
        assertFalse(second.hasCaptureTime(), "no capture time means the library stamps it");
        assertEquals(2, fake.pushOrder.size());
    }

    @Test
    @DisplayName("an audio track takes no encoded frame")
    void anAudioTrackIsRefused() {
        Track microphone = published("mic_in");

        InvalidStateException thrown =
                assertThrows(InvalidStateException.class, () -> microphone.pushEncodedFrame(IDR, 2, 2));
        assertTrue(thrown.getMessage().contains("only a video track"), thrown.getMessage());
        assertTrue(fake.pushOrder.isEmpty());
    }

    @Test
    @DisplayName("a recvonly track takes no encoded frame")
    void aRecvonlyTrackIsRefused() {
        Track output = reactor.track("main_video");

        InvalidStateException thrown =
                assertThrows(InvalidStateException.class, () -> output.pushEncodedFrame(IDR, 2, 2));
        assertTrue(thrown.getMessage().contains("recvonly"), thrown.getMessage());
    }

    @Test
    @DisplayName("an encoded frame needs a stepped session: a streamed one has no step to carry it")
    void aStreamedSessionIsRefused() {
        try (FakeNativeLibrary streamedLibrary = new FakeNativeLibrary()) {
            streamedLibrary.tracksJson = TRACKS;
            try (Reactor streamed = open(streamedLibrary, Generation.STREAMED)) {
                Track camera = streamed.track("camera_in");
                camera.publish();
                streamedLibrary.settleLastCall(true, "{}", null);

                InvalidStateException thrown =
                        assertThrows(InvalidStateException.class, () -> camera.pushEncodedFrame(IDR, 2, 2));
                assertTrue(thrown.getMessage().contains("stepped session"), thrown.getMessage());
                assertTrue(streamedLibrary.pushOrder.isEmpty());
            }
        }
    }

    @Test
    @DisplayName("an encoded frame needs the track published first, as a raw one does")
    void anUnpublishedTrackIsRefused() {
        Track camera = reactor.track("camera_in");

        InvalidStateException thrown =
                assertThrows(InvalidStateException.class, () -> camera.pushEncodedFrame(IDR, 2, 2));
        assertEquals("pushEncodedFrame", thrown.operation());
        assertTrue(fake.pushOrder.isEmpty());
    }

    @Test
    @DisplayName("a frame with no data, a size that is not positive, or no codec is refused when it is built")
    void aMalformedFrameIsRefused() {
        IllegalArgumentException empty =
                assertThrows(IllegalArgumentException.class, () -> EncodedFrame.h264(new byte[0], 2, 2));
        assertTrue(empty.getMessage().contains("needs data"), empty.getMessage());
        IllegalArgumentException flat =
                assertThrows(IllegalArgumentException.class, () -> EncodedFrame.h264(IDR, 0, 2));
        assertTrue(flat.getMessage().contains("decoded frame"), flat.getMessage());
        assertThrows(IllegalArgumentException.class, () -> EncodedFrame.h264(IDR, 2, -1));
        EncodedFrame.Builder builder = EncodedFrame.builder(IDR, 2, 2);
        assertThrows(NullPointerException.class, () -> builder.codec(null));
    }

    @Test
    @DisplayName("a library without encoded step frames still loads, and the push says what to rebuild")
    void aLibraryWithoutTheSymbolSaysSo() {
        try (FakeNativeLibrary old = new FakeNativeLibrary()) {
            old.tracksJson = TRACKS;
            old.removeSymbol("reactor_push_step_encoded_video_frame");
            try (Reactor client = open(old, Generation.STEPPED)) {
                Track camera = client.track("camera_in");
                camera.publish();
                old.settleLastCall(true, "{}", null);

                InvalidStateException thrown =
                        assertThrows(InvalidStateException.class, () -> camera.pushEncodedFrame(IDR, 2, 2));
                assertTrue(thrown.getMessage().contains("predates encoded step frames"), thrown.getMessage());
                assertTrue(thrown.getMessage().contains("cargo build -p reactor-ffi --release"), thrown.getMessage());
                // Everything else still works on it.
                camera.pushFrame(new byte[2 * 2 * 4], 2, 2);
                assertEquals(1, old.videoFramesPushed);
            }
        }
    }

    @Test
    @DisplayName("an encoded frame is a value: equal by contents, and copied in and out")
    void anEncodedFrameIsAValue() {
        byte[] data = IDR.clone();
        EncodedFrame frame = EncodedFrame.h264(data, 4, 2);
        data[0] = 42;

        assertArrayEquals(IDR, frame.data(), "the frame copied its bytes when it was built");
        frame.data()[0] = 42;
        assertArrayEquals(IDR, frame.data(), "data() hands out a copy");
        assertEquals(EncodedFrame.h264(IDR, 4, 2), frame);
        assertEquals(EncodedFrame.h264(IDR, 4, 2).hashCode(), frame.hashCode());
        assertNotEquals(EncodedFrame.h264(IDR, 2, 4), frame);
        assertEquals(VideoCodec.H264, frame.codec());
        assertTrue(frame.userData().isEmpty());
        assertTrue(frame.captureTimeUs().isEmpty());
    }

    // ── step(state, commands, media) ────────────────────────────────────────

    @Test
    @DisplayName("a step's media goes after the frames already buffered, in order, before the step is sent")
    void stepMediaIsPushedInOrderBeforeTheStep() {
        Track camera = published("camera_in");
        published("mic_in");
        camera.pushFrame(new byte[2 * 2 * 4], 2, 2);

        reactor.step(
                null,
                null,
                Map.of(
                        "camera_in",
                        List.of(
                                EncodedFrame.h264(IDR, 2, 2),
                                EncodedFrame.h264(P_FRAME, 2, 2),
                                StepInput.video(new byte[2 * 2 * 4], 2, 2))));

        assertEquals(4, fake.pushOrder.size());
        assertEquals("camera_in", fake.pushOrder.get(0), "the frame pushed before the step goes first");
        assertArrayEquals(
                IDR, assertInstanceOf(EncodedPush.class, fake.pushOrder.get(1)).data());
        assertArrayEquals(
                P_FRAME,
                assertInstanceOf(EncodedPush.class, fake.pushOrder.get(2)).data());
        assertEquals("camera_in", fake.pushOrder.get(3));
        assertEquals(1, fake.stepCalls);
    }

    @Test
    @DisplayName("a step's raw audio goes to the library as a push to that track would")
    void stepMediaTakesRawAudio() {
        published("mic_in");

        reactor.step(null, null, Map.of("mic_in", List.of(StepInput.audio(new short[480], 48_000, 1))));

        assertEquals(1, fake.stepCalls);
    }

    @Test
    @DisplayName("a step refused for its state pushes none of its media")
    void aStepRefusedForItsStatePushesNoMedia() {
        published("camera_in");

        CompletableFuture<StepResult> refused = reactor.step(
                JsonValue.of("a red fox"), null, Map.of("camera_in", List.of(EncodedFrame.h264(IDR, 2, 2))));

        assertInstanceOf(
                BadRequestException.class,
                assertThrows(ExecutionException.class, refused::get).getCause());
        assertTrue(fake.pushOrder.isEmpty(), "the buffer is left as the caller left it");
        assertEquals(0, fake.stepCalls);
    }

    @Test
    @DisplayName("media naming a track the session does not declare refuses the step before anything is pushed")
    void anUnknownTrackRefusesTheStep() {
        published("camera_in");
        Map<String, List<EncodedFrame>> media = new java.util.LinkedHashMap<>();
        media.put("camera_in", List.of(EncodedFrame.h264(IDR, 2, 2)));
        media.put("webcam", List.of(EncodedFrame.h264(IDR, 2, 2)));

        CompletableFuture<StepResult> refused = reactor.step(null, null, media);

        NotFoundException cause = assertInstanceOf(
                NotFoundException.class,
                assertThrows(ExecutionException.class, refused::get).getCause());
        assertTrue(cause.getMessage().contains("webcam"), cause.getMessage());
        assertTrue(fake.pushOrder.isEmpty());
        assertEquals(0, fake.stepCalls);
    }

    @Test
    @DisplayName("a frame the push refuses fails the step, which is never sent")
    void aRefusedPushFailsTheStep() {
        CompletableFuture<StepResult> refused =
                reactor.step(null, null, Map.of("camera_in", List.of(EncodedFrame.h264(IDR, 2, 2))));

        // The track was never published.
        assertInstanceOf(
                InvalidStateException.class,
                assertThrows(ExecutionException.class, refused::get).getCause());
        assertEquals(0, fake.stepCalls);
    }

    @Test
    @DisplayName("a bad frame late in the media fails the step before any of the media is pushed")
    void aLaterBadFramePushesNoMedia() {
        published("camera_in");

        CompletableFuture<StepResult> refused = reactor.step(
                null,
                null,
                Map.of(
                        "camera_in",
                        List.of(
                                EncodedFrame.h264(IDR, 2, 2),
                                StepInput.video(new byte[2 * 2 * 4], 2, 2),
                                StepInput.video(new byte[3], 2, 2))));

        InvalidStateException cause = assertInstanceOf(
                InvalidStateException.class,
                assertThrows(ExecutionException.class, refused::get).getCause());
        assertTrue(cause.getMessage().contains("needs exactly"), cause.getMessage());
        assertTrue(fake.pushOrder.isEmpty(), "the frames before the bad one would ride the next step");
        assertEquals(0, fake.stepCalls);
    }

    @Test
    @DisplayName("a bad track late in the media fails the step before any of the media is pushed")
    void aLaterBadTrackPushesNoMedia() {
        published("camera_in");
        Map<String, List<StepInput>> media = new java.util.LinkedHashMap<>();
        media.put("camera_in", List.of(EncodedFrame.h264(IDR, 2, 2), StepInput.video(new byte[2 * 2 * 4], 2, 2)));
        media.put("mic_in", List.of(StepInput.audio(new short[480], 48_000, 1))); // never published

        CompletableFuture<StepResult> refused = reactor.step(null, null, media);

        InvalidStateException cause = assertInstanceOf(
                InvalidStateException.class,
                assertThrows(ExecutionException.class, refused::get).getCause());
        assertTrue(cause.getMessage().contains("mic_in"), cause.getMessage());
        assertTrue(fake.pushOrder.isEmpty(), "the camera's frames would ride the next step");
        assertEquals(0, fake.stepCalls);

        // The next step carries only its own media.
        reactor.step(null, null, Map.of("camera_in", List.of(EncodedFrame.h264(P_FRAME, 2, 2))));
        assertEquals(1, fake.pushOrder.size());
        assertEquals(1, fake.stepCalls);
    }

    @Test
    @DisplayName("a library without encoded step frames refuses the step before its raw frames are pushed")
    void aLibraryWithoutTheSymbolPushesNoMedia() {
        try (FakeNativeLibrary old = new FakeNativeLibrary()) {
            old.tracksJson = TRACKS;
            old.removeSymbol("reactor_push_step_encoded_video_frame");
            try (Reactor client = open(old, Generation.STEPPED)) {
                client.track("camera_in").publish();
                old.settleLastCall(true, "{}", null);

                CompletableFuture<StepResult> refused = client.step(
                        null,
                        null,
                        Map.of(
                                "camera_in",
                                List.of(StepInput.video(new byte[2 * 2 * 4], 2, 2), EncodedFrame.h264(IDR, 2, 2))));

                InvalidStateException cause = assertInstanceOf(
                        InvalidStateException.class,
                        assertThrows(ExecutionException.class, refused::get).getCause());
                assertTrue(cause.getMessage().contains("predates encoded step frames"), cause.getMessage());
                assertEquals(0, old.videoFramesPushed);
                assertEquals(0, old.stepCalls);
            }
        }
    }

    @Test
    @DisplayName("media on a streamed client is refused before a raw frame could go out live")
    void mediaOnAStreamedClientIsRefused() {
        try (FakeNativeLibrary streamedLibrary = new FakeNativeLibrary()) {
            streamedLibrary.tracksJson = TRACKS;
            try (Reactor streamed = open(streamedLibrary, Generation.STREAMED)) {
                Track camera = streamed.track("camera_in");
                camera.publish();
                streamedLibrary.settleLastCall(true, "{}", null);

                CompletableFuture<StepResult> refused = streamed.step(
                        null, null, Map.of("camera_in", List.of(StepInput.video(new byte[2 * 2 * 4], 2, 2))));

                InvalidStateException cause = assertInstanceOf(
                        InvalidStateException.class,
                        assertThrows(ExecutionException.class, refused::get).getCause());
                assertTrue(cause.getMessage().contains("Generation.STEPPED"), cause.getMessage());
                assertEquals(0, streamedLibrary.videoFramesPushed);
                assertEquals(0, streamedLibrary.stepCalls);
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static Reactor open(FakeNativeLibrary library, Generation generation) {
        Reactor client = Reactor.open(
                ReactorOptions.builder("https://api.example.test", "owner/model")
                        .dispatcher(Runnable::run)
                        .generation(generation)
                        .build(),
                arena -> Ffi.open(library.lookup()));
        client.connect();
        library.settleLastCall(true, "{}", null);
        library.clearPendingCall();
        return client;
    }

    private Track published(String name) {
        Track track = reactor.track(name);
        track.publish();
        fake.settleLastCall(true, "{}", null);
        return track;
    }
}
