package inc.reactor.sdk;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import inc.reactor.sdk.internal.FakeNativeLibrary;
import inc.reactor.sdk.internal.FakeNativeLibrary.StepFileSpec;
import inc.reactor.sdk.internal.FakeNativeLibrary.StepFrameSpec;
import inc.reactor.sdk.internal.FakeNativeLibrary.StepTrackSpec;
import inc.reactor.sdk.internal.Ffi;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Stepped generation, end to end through the binding's real downcalls and upcalls against a fake
 * library: the creation call that asks for it, step() and its owned result with its files and error,
 * and the refusals that keep a stepped client from failing quietly.
 */
final class SteppedGenerationTest {

    private static final String TRACKS = "[{\"name\":\"camera_in\",\"kind\":\"video\",\"direction\":\"sendonly\"},"
            + "{\"name\":\"main_video\",\"kind\":\"video\",\"direction\":\"recvonly\"}]";

    private FakeNativeLibrary fake;
    private Reactor reactor;

    @BeforeEach
    void openSteppedClient() {
        fake = new FakeNativeLibrary();
        fake.tracksJson = TRACKS;
        reactor = open(stepped().stepTimeoutSeconds(12.5).build());
        connect(reactor);
    }

    @AfterEach
    void closeClient() {
        reactor.close();
        fake.close();
    }

    // ── Creation ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a stepped client is created through the options call, asking for stepped and synthetic audio")
    void aSteppedClientAsksForSteppedGeneration() {
        assertEquals(1, fake.createdWithOptions.size());
        JsonValue.JsonObject sent =
                assertInstanceOf(JsonValue.JsonObject.class, JsonValue.parse(fake.createdWithOptions.get(0)));
        assertEquals("stepped", sent.getString("generation").orElseThrow());
        // The same promise the streamed path keeps through reactor_create_with_adm(…, 0, …): no
        // environment variable can put a live microphone on the wire.
        assertEquals("synthetic", sent.getString("adm").orElseThrow());
        assertEquals("java", sent.getString("sdk_type").orElseThrow());
        assertEquals(12.5, sent.getNumber("step_timeout_seconds").orElseThrow());
        assertEquals(Generation.STEPPED, reactor.generation());
    }

    @Test
    @DisplayName("a streamed client still uses the ADM-pinning create, and never the options call")
    void aStreamedClientIsCreatedAsBefore() {
        try (FakeNativeLibrary streamedLibrary = new FakeNativeLibrary();
                Reactor streamed = Reactor.open(
                        ReactorOptions.builder("https://api.example.test", "owner/model")
                                .dispatcher(Runnable::run)
                                .build(),
                        arena -> Ffi.open(streamedLibrary.lookup()))) {
            streamed.connect();
            streamedLibrary.settleLastCall(true, "{}", null);

            assertEquals(0, streamedLibrary.admMode, "the streamed path pins the synthetic ADM itself");
            assertTrue(streamedLibrary.createdWithOptions.isEmpty());
            assertEquals(Generation.STREAMED, streamed.generation());
        }
    }

    @Test
    @DisplayName("options the library refuses fail connect with the error it names, and free it")
    void refusedOptionsFailConnect() {
        try (FakeNativeLibrary refusing = new FakeNativeLibrary()) {
            refusing.createOptionsError = "{\"code\":\"INVALID_STATE\",\"message\":\"unknown option"
                    + " \\\"generaton\\\"\",\"recoverable\":false,\"operation\":\"create\"}";
            try (Reactor client = Reactor.open(stepped().build(), arena -> Ffi.open(refusing.lookup()))) {
                ExecutionException thrown = assertThrows(
                        ExecutionException.class, () -> client.connect().get());
                InvalidStateException cause = assertInstanceOf(InvalidStateException.class, rootCause(thrown));
                assertTrue(cause.getMessage().contains("generaton"), cause.getMessage());
            }
            assertTrue(refusing.ownedStringsFreedExactlyOnce(), "the error_out string is the caller's to free");
        }
    }

    @Test
    @DisplayName("a step timeout is refused on a streamed client, and when it cannot be a duration")
    void aStepTimeoutIsValidated() {
        ReactorOptions.Builder streamed = ReactorOptions.builder("https://api.example.test", "owner/model")
                .stepTimeoutSeconds(30);
        assertThrows(IllegalArgumentException.class, streamed::build);
        for (double bad : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            ReactorOptions.Builder builder = stepped().stepTimeoutSeconds(bad);
            assertThrows(IllegalArgumentException.class, builder::build, "accepted " + bad);
        }
    }

    // ── step() ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("step sends state and commands, and resolves with every frame copied out")
    void aStepResolvesWithItsFrames() throws Exception {
        CompletableFuture<StepResult> pending = reactor.step(
                JsonValue.object().put("prompt", "a red fox").build(),
                List.of(
                        StepCommand.of(
                                "advance", JsonValue.object().put("frames", 2).build()),
                        StepCommand.of("reset")));

        JsonValue.JsonObject state = assertInstanceOf(JsonValue.JsonObject.class, JsonValue.parse(fake.lastStepState));
        assertEquals("a red fox", state.getString("prompt").orElseThrow());
        assertEquals(
                JsonValue.parse("[{\"command\":\"advance\",\"data\":{\"frames\":2}},"
                        + "{\"command\":\"reset\",\"data\":null}]"),
                JsonValue.parse(fake.lastStepCommands));

        byte[] rgb = {1, 2, 3, 4, 5, 6};
        byte[] pcm = {10, 0, 20, 0, -1, -1, 0, 1}; // int16 LE: 10, 20, -1, 256
        long handedOut = fake.settleLastStep(
                3,
                List.of(
                        new StepTrackSpec(
                                "main_video",
                                "video",
                                24.0,
                                List.of(
                                        new StepFrameSpec(
                                                "raw", "uint8", new int[] {1, 2, 3}, rgb, "tag".getBytes(), 99L),
                                        new StepFrameSpec(
                                                "raw", "uint8", new int[] {1, 2, 3}, rgb, new byte[0], null))),
                        new StepTrackSpec(
                                "speech",
                                "audio",
                                48000.0,
                                List.of(new StepFrameSpec("raw", "int16", new int[] {2, 2}, pcm, null, null)))),
                0.25);

        StepResult result = pending.get();
        assertEquals(3, result.step());
        assertEquals(0.25, result.elapsed());
        assertEquals(
                List.of("main_video", "speech"),
                result.tracks().stream().map(StepTrackOutput::name).toList());

        StepTrackOutput video = result.track("main_video");
        assertEquals(TrackKind.VIDEO, video.kind());
        assertEquals(24.0, video.rate());
        assertEquals(2, video.frames().size());
        StepFrame first = video.frames().get(0);
        assertEquals("raw", first.encoding());
        assertEquals("uint8", first.dtype());
        assertArrayEquals(new int[] {1, 2, 3}, first.shape());
        assertArrayEquals(rgb, first.toByteArray());
        assertArrayEquals("tag".getBytes(), first.metadata().orElseThrow());
        assertEquals(99L, first.captureTimeUs().orElseThrow());
        // An attached but empty tag is present and empty; no capture time is absent.
        assertEquals(0, video.frames().get(1).metadata().orElseThrow().length);
        assertTrue(video.frames().get(1).captureTimeUs().isEmpty());

        StepFrame audio = result.track("speech").frames().get(0);
        assertEquals(TrackKind.AUDIO, result.track("speech").kind());
        assertEquals(48000.0, result.track("speech").rate());
        assertTrue(audio.metadata().isEmpty(), "no tag attached is not an empty tag");
        assertEquals(ByteOrder.LITTLE_ENDIAN, audio.data().order());
        ShortBuffer samples = audio.data().asShortBuffer();
        assertEquals(List.of((short) 10, (short) 20, (short) -1, (short) 256), shorts(samples));
        assertTrue(audio.data().isReadOnly());

        // Copied out, then released: the very address handed over, exactly once.
        assertEquals(List.of(handedOut), fake.freedResults);
        assertTrue(fake.stepResultsFreedExactlyOnce());
    }

    @Test
    @DisplayName("a step with nothing to say sends no state and an empty command list")
    void aBareStepSendsNothingButTheStep() throws Exception {
        CompletableFuture<StepResult> pending = reactor.step();

        assertEquals(null, fake.lastStepState);
        assertEquals("[]", fake.lastStepCommands);
        fake.settleLastStep(1, List.of());
        StepResult result = pending.get();
        assertEquals(List.of(), result.tracks());
        assertEquals(List.of(), result.files());
        assertTrue(result.error().isEmpty());

        // Null for either part means none of it, as for the state.
        CompletableFuture<StepResult> nulls = reactor.step(null, null);
        assertEquals("[]", fake.lastStepCommands);
        fake.settleLastStep(2, List.of());
        assertEquals(2, nulls.get().step());
    }

    @Test
    @DisplayName("frames, and the results that hold them, compare by value")
    void framesCompareByValue() {
        StepFrame frame = new StepFrame("raw", "uint8", new int[] {1, 1, 3}, new byte[] {1, 2, 3}, null, 7L);
        StepFrame same = new StepFrame("raw", "uint8", new int[] {1, 1, 3}, new byte[] {1, 2, 3}, null, 7L);
        assertEquals(frame, same);
        assertEquals(frame.hashCode(), same.hashCode());
        assertNotEquals(frame, new StepFrame("raw", "uint8", new int[] {1, 1, 3}, new byte[] {1, 2, 4}, null, 7L));
        assertNotEquals(
                frame, new StepFrame("raw", "uint8", new int[] {1, 1, 3}, new byte[] {1, 2, 3}, new byte[0], 7L));

        StepTrackOutput track = new StepTrackOutput("main_video", TrackKind.VIDEO, 30.0, List.of(frame));
        assertEquals(
                new StepResult(1, List.of(track), 0.5, List.of(), Optional.empty()),
                new StepResult(
                        1,
                        List.of(new StepTrackOutput("main_video", TrackKind.VIDEO, 30.0, List.of(same))),
                        0.5,
                        List.of(),
                        Optional.empty()));
    }

    @Test
    @DisplayName("the runtime's step codes arrive as their own exception types")
    void stepFailuresAreTyped() {
        assertStepFailsWith("step_refused", StepRefusedException.class);
        assertStepFailsWith("step_not_ready", StepNotReadyException.class);
        assertStepFailsWith("step_result_too_large", StepResultTooLargeException.class);
        assertStepFailsWith("step_command_failed", StepCommandFailedException.class);
        assertStepFailsWith("step_session_ended", StepSessionEndedException.class);
        assertStepFailsWith("MESSAGE_TOO_LARGE", MessageTooLargeException.class);
        assertStepFailsWith("UNSUPPORTED_FRAME_ENCODING", UnsupportedFrameEncodingException.class);
        assertStepFailsWith("REQUEST_TIMEOUT", RequestTimeoutException.class);
    }

    @Test
    @DisplayName("a failed step's call names its entry and the code its handler raised")
    void aFailedCallCarriesItsEntryAndCode() {
        CompletableFuture<StepResult> pending = reactor.step();
        fake.failLastStep("{\"code\":\"step_command_failed\",\"message\":\"tag failed (queue_full): full\","
                + "\"recoverable\":false,\"operation\":\"step\",\"entry\":\"commands[2]\","
                + "\"command_code\":\"queue_full\"}");

        StepCommandFailedException cause = assertInstanceOf(
                StepCommandFailedException.class,
                assertThrows(ExecutionException.class, pending::get).getCause());
        assertEquals("commands[2]", cause.entry());
        assertEquals("queue_full", cause.commandCode());
    }

    @Test
    @DisplayName("a refused step names the entry at fault, and carries no command code")
    void aRefusedStepCarriesItsEntry() {
        CompletableFuture<StepResult> pending = reactor.step();
        fake.failLastStep("{\"code\":\"step_refused\",\"message\":\"media_input[0] holds too many frames\","
                + "\"recoverable\":false,\"operation\":\"step\",\"entry\":\"media_input[0]\"}");

        StepRefusedException cause = assertInstanceOf(
                StepRefusedException.class,
                assertThrows(ExecutionException.class, pending::get).getCause());
        assertEquals("media_input[0]", cause.entry());
        assertNull(cause.commandCode());
    }

    @Test
    @DisplayName("a frame whose bytes disagree with its shape is a decode failure, and is still freed")
    void aMisshapenFrameIsADecodeFailure() {
        CompletableFuture<StepResult> pending = reactor.step();

        fake.settleLastStep(
                1,
                List.of(new StepTrackSpec(
                        "main_video",
                        "video",
                        List.of(new StepFrameSpec("raw", "uint8", new int[] {2, 2, 3}, new byte[5], null, null)))));

        ReactorException cause = assertInstanceOf(
                ReactorException.class,
                assertThrows(ExecutionException.class, pending::get).getCause());
        assertEquals("DECODE_FAILED", cause.code());
        assertTrue(cause.getMessage().contains("12"), "the message names the expected size: " + cause.getMessage());
        assertTrue(fake.stepResultsFreedExactlyOnce(), "a result that failed to decode is still the binding's to free");
    }

    @Test
    @DisplayName("a track of a kind this SDK does not know is a decode failure, not a dropped track")
    void anUnknownTrackKindIsADecodeFailure() {
        CompletableFuture<StepResult> pending = reactor.step();

        fake.settleLastStep(1, List.of(new StepTrackSpec("depth", "pointcloud", List.of())));

        ReactorException cause = assertInstanceOf(
                ReactorException.class,
                assertThrows(ExecutionException.class, pending::get).getCause());
        assertEquals("DECODE_FAILED", cause.code());
        assertTrue(cause.getMessage().contains("pointcloud"), cause.getMessage());
        assertTrue(fake.stepResultsFreedExactlyOnce());
    }

    @Test
    @DisplayName("asking a result for a track it has no output on names the ones it does")
    void anAbsentTrackNamesThePresentOnes() throws Exception {
        CompletableFuture<StepResult> pending = reactor.step();
        fake.settleLastStep(4, List.of(new StepTrackSpec("main_video", "video", List.of())));
        StepResult result = pending.get();

        NotFoundException thrown = assertThrows(NotFoundException.class, () -> result.track("main_vidoe"));
        assertTrue(thrown.getMessage().contains("main_video"), thrown.getMessage());
        assertTrue(result.findTrack("main_vidoe").isEmpty());
        assertEquals(0.0, result.track("main_video").rate(), "a rate the runtime did not report reads as 0");
    }

    @Test
    @DisplayName("a result built without elapsed, files or error defaults them to zero and empty, as the other SDKs do")
    void aResultBuiltWithoutElapsedDefaultsToZero() {
        StepResult result = new StepResult(3, List.of());
        assertEquals(new StepResult(3, List.of(), 0.0), result);
        assertEquals(new StepResult(3, List.of(), 0.0, List.of(), java.util.Optional.empty()), result);
        assertEquals(List.of(), result.files());
        assertTrue(result.error().isEmpty());
    }

    @Test
    @DisplayName("close settles a step still in flight rather than leaving it waiting forever")
    void closeSettlesAPendingStep() {
        CompletableFuture<StepResult> pending = reactor.step();
        assertTrue(fake.hasPendingStep());

        reactor.close();

        assertInstanceOf(
                AbortedException.class,
                assertThrows(ExecutionException.class, pending::get).getCause());
    }

    // ── Refusals ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("step on a streamed client is refused without reaching the library")
    void stepIsRefusedOnAStreamedClient() {
        try (FakeNativeLibrary streamedLibrary = new FakeNativeLibrary();
                Reactor streamed = Reactor.open(
                        ReactorOptions.builder("https://api.example.test", "owner/model")
                                .dispatcher(Runnable::run)
                                .build(),
                        arena -> Ffi.open(streamedLibrary.lookup()))) {
            CompletableFuture<StepResult> refused = streamed.step();

            InvalidStateException cause = assertInstanceOf(
                    InvalidStateException.class,
                    assertThrows(ExecutionException.class, refused::get).getCause());
            assertTrue(cause.getMessage().contains("Generation.STEPPED"), cause.getMessage());
            assertEquals(0, streamedLibrary.stepCalls);
        }
    }

    @Test
    @DisplayName("step before connect is refused: the library would never answer a NULL handle")
    void stepBeforeConnectIsRefused() {
        try (Reactor unconnected = open(stepped().build())) {
            int before = fake.stepCalls;
            CompletableFuture<StepResult> refused = unconnected.step();

            InvalidStateException cause = assertInstanceOf(
                    InvalidStateException.class,
                    assertThrows(ExecutionException.class, refused::get).getCause());
            assertTrue(cause.getMessage().contains("connect"), cause.getMessage());
            assertEquals(before, fake.stepCalls);
        }
    }

    @Test
    @DisplayName("step on a closed client is refused")
    void stepOnAClosedClientIsRefused() {
        reactor.close();

        assertInstanceOf(
                InvalidStateException.class,
                assertThrows(ExecutionException.class, () -> reactor.step().get())
                        .getCause());
        assertThrows(InvalidStateException.class, reactor::clearStepInputs);
    }

    @Test
    @DisplayName("a state that is not a JSON object is refused before anything is sent")
    void aNonObjectStateIsRefused() {
        CompletableFuture<StepResult> refused = reactor.step(JsonValue.of("a red fox"));

        assertInstanceOf(
                BadRequestException.class,
                assertThrows(ExecutionException.class, refused::get).getCause());
        assertEquals(0, fake.stepCalls);
    }

    @Test
    @DisplayName("a step command with no name, or with non-object data, is refused when it is built")
    void aMalformedCommandIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> StepCommand.of(""));
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> StepCommand.of("advance", JsonValue.of(4)));
        assertTrue(thrown.getMessage().contains("advance"), thrown.getMessage());
        // JSON null is "no arguments", the same as leaving data out.
        assertEquals(null, StepCommand.of("advance", JsonValue.ofNull()).data());
    }

    @Test
    @DisplayName("a frame handler on a stepped session is refused: nothing streams there")
    void aFrameHandlerIsRefusedInASteppedSession() {
        Track output = reactor.track("main_video");

        InvalidStateException thrown =
                assertThrows(InvalidStateException.class, () -> output.onFrame((VideoFrame frame) -> {}));
        assertTrue(thrown.getMessage().contains("StepResult"), thrown.getMessage());
    }

    // ── Inputs and events ───────────────────────────────────────────────────

    @Test
    @DisplayName("a push in a stepped session goes to the library, which buffers it for the next step")
    void aPushReachesTheLibrary() {
        Track camera = reactor.track("camera_in");
        camera.publish();
        fake.settleLastCall(true, "{}", null);

        camera.pushFrame(new byte[2 * 2 * 4], 2, 2);

        assertEquals(1, fake.videoFramesPushed);
    }

    @Test
    @DisplayName("clearStepInputs reaches the library on a stepped client and is a no-op on a streamed one")
    void clearStepInputsReachesTheLibrary() {
        reactor.clearStepInputs();
        assertEquals(1, fake.clearStepInputsCalls);

        try (FakeNativeLibrary streamedLibrary = new FakeNativeLibrary();
                Reactor streamed = Reactor.open(
                        ReactorOptions.builder("https://api.example.test", "owner/model")
                                .dispatcher(Runnable::run)
                                .build(),
                        arena -> Ffi.open(streamedLibrary.lookup()))) {
            streamed.clearStepInputs();
            assertEquals(0, streamedLibrary.clearStepInputsCalls);
        }
    }

    @Test
    @DisplayName("the files a step kept arrive on its result, in order, with their types and sizes")
    void aStepResultNamesItsFiles() throws Exception {
        CompletableFuture<StepResult> pending = reactor.step();
        fake.settleLastStep(
                2,
                List.of(),
                0.5,
                List.of(
                        new StepFileSpec("depth.npy", "application/octet-stream", 4096),
                        new StepFileSpec("caption.txt", "text/plain", 12)),
                null);

        StepResult result = pending.get();
        assertEquals(
                List.of(
                        new StepFile("depth.npy", "application/octet-stream", 4096),
                        new StepFile("caption.txt", "text/plain", 12)),
                result.files());
        assertTrue(result.error().isEmpty(), "a step that worked carries no error");
        assertTrue(fake.stepResultsFreedExactlyOnce());
    }

    @Test
    @DisplayName("a step that ran but failed resolves, with the model's error, rather than throwing")
    void aRanButFailedStepResolvesWithItsError() throws Exception {
        CompletableFuture<StepResult> pending = reactor.step();
        fake.settleLastStep(5, List.of(), 0.1, List.of(), "out of memory");

        StepResult result = pending.get();
        assertEquals(5, result.step());
        assertEquals("out of memory", result.error().orElseThrow());
        assertEquals(List.of(), result.files());
        assertTrue(fake.stepResultsFreedExactlyOnce());
    }

    @Test
    @DisplayName("there is no step-accepted event: the result is the step's one answer")
    void thereIsNoStepAcceptedEvent() {
        assertTrue(
                java.util.Arrays.stream(Reactor.class.getMethods())
                        .noneMatch(method -> method.getName()
                                .toLowerCase(java.util.Locale.ROOT)
                                .contains("accepted")),
                "Reactor exposes no accepted listener");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static ReactorOptions.Builder stepped() {
        return ReactorOptions.builder("https://api.example.test", "owner/model")
                .dispatcher(Runnable::run)
                .generation(Generation.STEPPED);
    }

    private Reactor open(ReactorOptions options) {
        return Reactor.open(options, arena -> Ffi.open(fake.lookup()));
    }

    private void connect(Reactor client) {
        client.connect();
        fake.settleLastCall(true, "{}", null);
        fake.clearPendingCall();
    }

    private <T extends ReactorException> void assertStepFailsWith(String code, Class<T> type) {
        CompletableFuture<StepResult> pending = reactor.step();
        fake.failLastStep(
                "{\"code\":\"" + code + "\",\"message\":\"no\",\"recoverable\":false,\"operation\":\"step\"}");
        T cause = assertInstanceOf(
                type, assertThrows(ExecutionException.class, pending::get).getCause());
        assertEquals(code, cause.code());
    }

    private static List<Short> shorts(ShortBuffer buffer) {
        List<Short> values = new ArrayList<>();
        while (buffer.hasRemaining()) {
            values.add(buffer.get());
        }
        return values;
    }

    private static Throwable rootCause(Throwable thrown) {
        Throwable cause = thrown;
        while (cause.getCause() != null && !(cause instanceof ReactorException)) {
            cause = cause.getCause();
        }
        return cause;
    }
}
