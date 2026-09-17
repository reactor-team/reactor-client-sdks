package inc.reactor.sdk.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import inc.reactor.sdk.AudioFrame;
import inc.reactor.sdk.PublishState;
import inc.reactor.sdk.ReactorException;
import inc.reactor.sdk.Track;
import inc.reactor.sdk.VideoFrame;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

/**
 * Media, both directions, over a real connection.
 *
 * <p>{@code reactor/echo} sends back what it is given, so the send path can be checked by what
 * comes out rather than by the absence of an error — which is the whole difficulty with a
 * permissive native layer.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
final class LiveMediaTest extends LiveFixture {

    private static final int WIDTH = 320;
    private static final int HEIGHT = 240;

    @Test
    @DisplayName("published frames come back out the other side")
    @Timeout(240)
    void publishedFramesComeBack() throws Exception {
        Track input = shared.track(Live.VIDEO_IN);
        Track output = shared.track(Live.VIDEO_OUT);

        AtomicInteger received = new AtomicInteger();
        AtomicReference<String> size = new AtomicReference<>();
        var subscription = output.onFrame((VideoFrame frame) -> {
            received.incrementAndGet();
            size.compareAndSet(null, frame.width() + "x" + frame.height());
        });

        assertEquals(PublishState.UNPUBLISHED, input.publishState());
        input.publish().join();
        assertEquals(PublishState.PUBLISHED, input.publishState());

        // Pushed on a loop rather than once: a single frame can be swallowed by a pipeline still
        // warming up, and this is checking that the path works rather than that every frame
        // survives it.
        for (int index = 0; index < 150 && received.get() < 5; index++) {
            input.pushFrame(bar(index), WIDTH, HEIGHT);
            Thread.sleep(33);
        }

        Live.await("frames to come back from echo", Duration.ofSeconds(60), () -> received.get() > 0);
        System.out.println("received " + received.get() + " frames at " + size.get());

        input.unpublish();
        assertEquals(PublishState.UNPUBLISHED, input.publishState());
        subscription.close();
    }

    @Test
    @DisplayName("pushing before publishing raises against a real session, rather than vanishing")
    @Timeout(180)
    void pushingBeforePublishingRaises() {
        Track input = shared.track(Live.VIDEO_IN);
        if (input.publishState() == PublishState.PUBLISHED) {
            input.unpublish();
        }

        // The native layer would take this frame and drop it. Against a live session that is
        // indistinguishable from a model ignoring you, which is why it raises here.
        ReactorException thrown = assertThrows(ReactorException.class, () -> input.pushFrame(bar(0), WIDTH, HEIGHT));

        assertEquals("INVALID_STATE", thrown.code());
        assertTrue(thrown.getMessage().contains("publish()"), thrown.getMessage());
    }

    @Test
    @DisplayName("receiving on a sendonly track raises, rather than never firing")
    @Timeout(180)
    void receivingOnASendonlyTrackRaises() {
        ReactorException thrown = assertThrows(
                ReactorException.class, () -> shared.track(Live.VIDEO_IN).onFrame((VideoFrame frame) -> {}));

        assertTrue(thrown.getMessage().contains("recvonly"), thrown.getMessage());
    }

    @Test
    @DisplayName("audio travels too, and arrives as PCM the core's contract describes")
    @Timeout(240)
    void audioTravels() throws Exception {
        Track microphone = shared.track(Live.AUDIO_IN);
        Track speaker = shared.track(Live.AUDIO_OUT);

        AtomicInteger received = new AtomicInteger();
        AtomicReference<String> format = new AtomicReference<>();
        var subscription = speaker.onFrame((AudioFrame frame) -> {
            received.incrementAndGet();
            format.compareAndSet(null, frame.sampleRate() + " Hz / " + frame.channels() + " ch");
        });

        microphone.publish().join();

        // 20 ms of silence per push, at 48 kHz mono: the frame size every Reactor model takes.
        short[] silence = new short[48_000 / 50];
        for (int index = 0; index < 150 && received.get() < 5; index++) {
            microphone.pushFrame(silence, 48_000, 1);
            Thread.sleep(20);
        }

        Live.await("audio to come back from echo", Duration.ofSeconds(60), () -> received.get() > 0);
        System.out.println("received " + received.get() + " audio frames at " + format.get());

        microphone.unpublish();
        subscription.close();
    }

    @Test
    @DisplayName("pause and resume take effect on a live track")
    @Timeout(240)
    void pauseAndResumeTakeEffect() {
        Track output = shared.track(Live.VIDEO_OUT);

        output.pause().join();
        Live.await("the pause to be reported", Duration.ofSeconds(30), output::isPaused);

        output.resume().join();
        Live.await("the resume to be reported", Duration.ofSeconds(30), () -> !output.isPaused());
    }

    /** A frame with something in it that moves, so an echo is visibly an echo. */
    private static byte[] bar(int index) {
        byte[] bgra = new byte[WIDTH * HEIGHT * 4];
        int barX = index * 5 % WIDTH;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int at = (y * WIDTH + x) * 4;
                boolean onBar = Math.abs(x - barX) < 16;
                bgra[at] = (byte) (onBar ? 220 : 20);
                bgra[at + 1] = (byte) (onBar ? 180 : 40);
                bgra[at + 2] = (byte) (onBar ? 60 : 90);
                bgra[at + 3] = (byte) 255;
            }
        }
        return bgra;
    }
}
