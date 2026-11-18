package inc.reactor.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import inc.reactor.sdk.internal.FakeNativeLibrary;
import inc.reactor.sdk.internal.FakeNativeLibrary.CodecPreferenceCall;
import inc.reactor.sdk.internal.Ffi;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Choosing the codecs a stepped session's step frames travel in, reading what was negotiated, and
 * asking a step for keyframes — through the binding's real downcalls, against a fake library.
 */
final class StepCodecsTest {

    private FakeNativeLibrary fake;
    private Reactor reactor;

    @BeforeEach
    void openSteppedClient() {
        fake = new FakeNativeLibrary();
        reactor = Reactor.open(
                ReactorOptions.builder("https://api.example.test", "owner/model")
                        .dispatcher(Runnable::run)
                        .generation(Generation.STEPPED)
                        .build(),
                arena -> Ffi.open(fake.lookup()));
    }

    @AfterEach
    void closeClient() {
        reactor.close();
        fake.close();
    }

    private void connect() {
        reactor.connect();
        fake.settleLastCall(true, "{}", null);
        fake.clearPendingCall();
    }

    // ── setCodecPreference ──────────────────────────────────────────────────

    @Test
    @DisplayName("preferences set before connect are kept, and given to the handle the connect creates")
    void preferencesBeforeConnectAreReplayed() {
        reactor.setCodecPreference(List.of(StepCodec.VP9, StepCodec.H264));
        reactor.setCodecPreference(List.of(), "main_video");
        reactor.setCodecPreference(List.of(StepCodec.AV1), "webcam");
        assertTrue(fake.codecPreferenceCalls.isEmpty(), "there is no handle to give them to yet");

        connect();

        long handle = fake.lastCreatedHandle();
        assertEquals(
                List.of(
                        new CodecPreferenceCall(handle, null, "[\"vp9\",\"h264\"]"),
                        new CodecPreferenceCall(handle, "main_video", "[]"),
                        new CodecPreferenceCall(handle, "webcam", "[\"av1\"]")),
                fake.codecPreferenceCalls);
    }

    @Test
    @DisplayName("a later preference for the same track replaces the earlier one in what is replayed")
    void aLaterPreferenceReplacesAnEarlierOne() {
        reactor.setCodecPreference(List.of(StepCodec.VP8));
        reactor.setCodecPreference(List.of());

        connect();

        assertEquals(List.of(new CodecPreferenceCall(fake.lastCreatedHandle(), null, "[]")), fake.codecPreferenceCalls);
    }

    @Test
    @DisplayName("during a session a preference reaches the live handle at once, null as the default")
    void duringASessionAPreferenceIsSentAtOnce() {
        connect();
        long handle = fake.lastCreatedHandle();

        reactor.setCodecPreference(List.of(StepCodec.H264), "webcam");
        reactor.setCodecPreference(null);

        assertEquals(
                List.of(
                        new CodecPreferenceCall(handle, "webcam", "[\"h264\"]"),
                        new CodecPreferenceCall(handle, null, null)),
                fake.codecPreferenceCalls);
    }

    @Test
    @DisplayName("a null codec or an empty track name is refused before the library sees it")
    void invalidPreferencesAreRefused() {
        connect();

        assertThrows(
                IllegalArgumentException.class, () -> reactor.setCodecPreference(Arrays.asList(StepCodec.H264, null)));
        assertThrows(IllegalArgumentException.class, () -> reactor.setCodecPreference(List.of(StepCodec.H264), ""));

        assertTrue(fake.codecPreferenceCalls.isEmpty());
    }

    @Test
    @DisplayName("a codec is read by name in any case, and an unknown name is refused")
    void codecsAreReadByName() {
        assertEquals(StepCodec.H264, StepCodec.of("H264"));
        assertEquals(StepCodec.VP9, StepCodec.of("vp9"));
        assertEquals("av1", StepCodec.AV1.wireName());
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () -> StepCodec.of("hevc"));
        assertTrue(thrown.getMessage().contains("hevc"), thrown.getMessage());
    }

    @Test
    @DisplayName("the library's refusal is thrown as the typed error it names, and its string is freed")
    void aRefusalIsSurfacedTyped() {
        connect();
        fake.codecPreferenceError = "{\"code\":\"DECODE_FAILED\",\"message\":\"unknown codec\","
                + "\"recoverable\":false,\"operation\":\"set_codec_preference\"}";

        DecodeFailedException thrown =
                assertThrows(DecodeFailedException.class, () -> reactor.setCodecPreference(List.of(StepCodec.H264)));

        assertTrue(thrown.getMessage().contains("unknown codec"), thrown.getMessage());
        assertTrue(fake.ownedStringsFreedExactlyOnce(), "the error object is the caller's to free");
    }

    @Test
    @DisplayName("a library without the codec symbols still loads and steps, and says so where they are asked for")
    void anOlderLibraryRefusesAtTheCall() {
        try (FakeNativeLibrary older = new FakeNativeLibrary()) {
            older.removeSymbol("reactor_set_codec_preference");
            older.removeSymbol("reactor_step_frame_encodings");
            try (Reactor client = Reactor.open(
                    ReactorOptions.builder("https://api.example.test", "owner/model")
                            .dispatcher(Runnable::run)
                            .generation(Generation.STEPPED)
                            .build(),
                    arena -> Ffi.open(older.lookup()))) {
                InvalidStateException refused = assertThrows(
                        InvalidStateException.class, () -> client.setCodecPreference(List.of(StepCodec.H264)));
                assertTrue(refused.getMessage().contains("reactor_set_codec_preference"), refused.getMessage());

                client.connect();
                older.settleLastCall(true, "{}", null);

                assertEquals(Map.of(), client.stepFrameEncodings());

                client.step();
                assertEquals(1, older.stepCalls, "a step still runs");
            }
        }
    }

    // ── stepFrameEncodings ──────────────────────────────────────────────────

    @Test
    @DisplayName("the negotiated encodings are read by track, and the string is freed")
    void encodingsAreRead() {
        connect();
        fake.stepFrameEncodingsJson = "{\"main_video\":\"h264\",\"webcam\":\"h264\",\"main_audio\":\"raw\"}";

        assertEquals(Map.of("main_video", "h264", "webcam", "h264", "main_audio", "raw"), reactor.stepFrameEncodings());
        assertTrue(fake.ownedStringsFreedExactlyOnce());
    }

    @Test
    @DisplayName("outside a connected session the encodings are empty")
    void encodingsBeforeConnectAreEmpty() {
        assertEquals(Map.of(), reactor.stepFrameEncodings());
    }
}
