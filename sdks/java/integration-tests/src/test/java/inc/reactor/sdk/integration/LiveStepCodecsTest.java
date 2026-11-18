package inc.reactor.sdk.integration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import inc.reactor.sdk.Generation;
import inc.reactor.sdk.GenerationModeMismatchException;
import inc.reactor.sdk.JsonValue;
import inc.reactor.sdk.PixelFormat;
import inc.reactor.sdk.Reactor;
import inc.reactor.sdk.SessionTerminalException;
import inc.reactor.sdk.StepCodec;
import inc.reactor.sdk.StepFrame;
import inc.reactor.sdk.StepResult;
import inc.reactor.sdk.Track;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * A stepped echo session whose step frames travel in the codec the client chose: H.264 both ways,
 * or encoding turned off so every frame travels raw. The client pushes and receives RGB either way.
 *
 * <p>Echo steps from 1.10.0. An older echo, or a runtime that does not step it, refuses the
 * stepped connect — as a mode mismatch, or as a terminal START_REFUSED — and that is accepted, as
 * the Python suite accepts it. A stepped connect that silently streams is not.
 */
final class LiveStepCodecsTest {

    private static final int SIZE = 64;
    private static final int[] INVERTED = {245, 235, 225};

    static Stream<Arguments> codecs() {
        return Stream.of(
                Arguments.of("h264", List.of(StepCodec.H264), "h264"), Arguments.of("encoding-off", List.of(), "raw"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("codecs")
    void aSteppedSessionCarriesFramesInTheCodecItChose(String label, List<StepCodec> codecs, String expected) {
        Reactor client =
                Reactor.open(Live.options().generation(Generation.STEPPED).build());
        boolean connected = false;
        try {
            client.setCodecPreference(codecs);
            Live.pace();
            try {
                client.connect().orTimeout(90, TimeUnit.SECONDS).join();
            } catch (CompletionException failed) {
                Throwable cause = failed.getCause();
                if (cause instanceof GenerationModeMismatchException) {
                    return;
                }
                if (cause instanceof SessionTerminalException terminal) {
                    assertTrue(terminal.getMessage().contains("START_REFUSED"), terminal.getMessage());
                    return;
                }
                throw failed;
            }
            connected = true;

            Track webcam = client.track(Live.VIDEO_IN);
            webcam.publish().orTimeout(30, TimeUnit.SECONDS).join();

            Map<String, String> encodings = client.stepFrameEncodings();
            assertEquals(expected, encodings.get(Live.VIDEO_OUT), encodings.toString());
            // With encoding off the input track may not be listed at all: absent reads as raw.
            assertEquals(expected, encodings.getOrDefault(Live.VIDEO_IN, "raw"), encodings.toString());
            if (expected.equals("raw")) {
                assertTrue(encodings.values().stream().allMatch("raw"::equals), encodings.toString());
            }
            int tolerance = expected.equals("raw") ? 0 : 8;

            webcam.pushFrame(solidRgb(10, 20, 30), PixelFormat.RGB, SIZE, SIZE);
            StepResult result = client.step(
                            JsonValue.object().put("effect", "invert").build())
                    .orTimeout(60, TimeUnit.SECONDS)
                    .join();
            assertNull(result.error().orElse(null));
            assertCentreIsInverted(result, tolerance);

            // The next step's frames continue the same stream.
            webcam.pushFrame(solidRgb(10, 20, 30), PixelFormat.RGB, SIZE, SIZE);
            StepResult again = client.step().orTimeout(60, TimeUnit.SECONDS).join();
            assertEquals(result.step() + 1, again.step());
            assertCentreIsInverted(again, tolerance);
        } finally {
            if (connected) {
                try {
                    client.disconnect().orTimeout(30, TimeUnit.SECONDS).join();
                } catch (RuntimeException alreadyGone) {
                    // The close below still releases the handle.
                }
            }
            client.close();
        }
    }

    private static void assertCentreIsInverted(StepResult result, int tolerance) {
        List<StepFrame> frames = result.track(Live.VIDEO_OUT).frames();
        assertEquals(1, frames.size(), "one frame per step on " + Live.VIDEO_OUT);
        StepFrame frame = frames.get(0);
        assertArrayEquals(new int[] {SIZE, SIZE, 3}, frame.shape());
        byte[] rgb = frame.toByteArray();
        int at = ((SIZE / 2) * SIZE + SIZE / 2) * 3;
        int[] centre = {rgb[at] & 0xFF, rgb[at + 1] & 0xFF, rgb[at + 2] & 0xFF};
        for (int channel = 0; channel < 3; channel++) {
            assertTrue(
                    Math.abs(centre[channel] - INVERTED[channel]) <= tolerance,
                    "centre pixel " + java.util.Arrays.toString(centre) + " is not within " + tolerance + " of "
                            + java.util.Arrays.toString(INVERTED));
        }
    }

    private static byte[] solidRgb(int r, int g, int b) {
        byte[] rgb = new byte[SIZE * SIZE * 3];
        for (int i = 0; i < SIZE * SIZE; i++) {
            rgb[i * 3] = (byte) r;
            rgb[i * 3 + 1] = (byte) g;
            rgb[i * 3 + 2] = (byte) b;
        }
        return rgb;
    }
}
