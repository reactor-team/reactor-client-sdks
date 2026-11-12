package inc.reactor.sdk.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import inc.reactor.sdk.AudioFrame;
import inc.reactor.sdk.ConnectionStatus;
import inc.reactor.sdk.PublishState;
import inc.reactor.sdk.ReactorException;
import inc.reactor.sdk.Track;
import inc.reactor.sdk.VideoFrame;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
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
    @DisplayName("a track published again after reconnect() is granted, and its frames come back")
    @Timeout(300)
    void republishAfterReconnectDeliversFramesAgain() throws Exception {
        // A publish does not survive a reconnect — the client drops it, and the session has to drop
        // its side too, or the second publish() is refused as "track already published" while the
        // client has no sender left. Two blues far apart, one per connection, so a frame read after
        // the republish can only have come from a frame pushed after it.
        //
        // On the shared client: a reconnect keeps the session, so this costs nothing against the
        // session ceiling, and one that does not complete is what the fixture puts back before the
        // next test. A failure anywhere else ends the session instead: the regression this checks
        // leaves the client READY and UNPUBLISHED while the session still holds the old publish, and
        // a session kept in that state would fail the next test's publish() the same way.
        Track input = shared.track(Live.VIDEO_IN);
        Track output = shared.track(Live.VIDEO_OUT);
        if (input.isPublished()) {
            input.unpublish();
        }
        AtomicInteger lastBlue = new AtomicInteger(-1);
        var subscription = output.onFrame((VideoFrame frame) -> lastBlue.set(meanBlue(frame)));

        boolean passed = false;
        try {
            input.publish().join();
            pushUntil("a pushed frame to come back before reconnecting", input, solid(0x10), () -> {
                int blue = lastBlue.get();
                return blue >= 0 && blue <= 0x28;
            });

            shared.reconnect().orTimeout(90, TimeUnit.SECONDS).join();
            Live.await(
                    "the transport to come back",
                    Duration.ofSeconds(60),
                    () -> shared.status() == ConnectionStatus.READY);

            assertEquals(PublishState.UNPUBLISHED, input.publishState(), "a publish must not survive reconnect()");
            ReactorException thrown =
                    assertThrows(ReactorException.class, () -> input.pushFrame(solid(0x10), WIDTH, HEIGHT));
            assertEquals("INVALID_STATE", thrown.code());

            input.publish().orTimeout(30, TimeUnit.SECONDS).join();
            assertEquals(PublishState.PUBLISHED, input.publishState(), "the republish must be granted");

            // Waits for a frame of the new colour rather than any frame: the first few after the
            // reconnect can still be the decoder catching up.
            pushUntil(
                    "a frame pushed after republishing to come back", input, solid(0xF0), () -> lastBlue.get() >= 0xB0);
            passed = true;
        } finally {
            subscription.close();
            if (!passed) {
                discardTheSession();
            } else if (input.isPublished()) {
                input.unpublish();
            }
        }
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

    /** Pushes {@code frame} on a loop until {@code condition} holds, or fails after 60 seconds. */
    private static void pushUntil(String what, Track input, byte[] frame, BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            input.pushFrame(frame, WIDTH, HEIGHT);
            Thread.sleep(33);
        }
        throw new AssertionError("waited 60s for " + what + " and it never happened");
    }

    /** A frame of one colour, tagged by its blue byte. */
    private static byte[] solid(int blue) {
        byte[] bgra = new byte[WIDTH * HEIGHT * 4];
        for (int at = 0; at < bgra.length; at += 4) {
            bgra[at] = (byte) blue;
            bgra[at + 1] = (byte) 40;
            bgra[at + 2] = (byte) 40;
            bgra[at + 3] = (byte) 255;
        }
        return bgra;
    }

    /** The mean of a frame's blue channel — the byte {@link #solid} tags — read inside the handler. */
    private static int meanBlue(VideoFrame frame) {
        MemorySegment pixels = frame.pixels();
        long sum = 0;
        long count = 0;
        // Every 16th pixel is plenty for a solid frame, and keeps the delivery thread unblocked.
        for (long at = 0; at + 3 < pixels.byteSize(); at += 64) {
            sum += pixels.get(ValueLayout.JAVA_BYTE, at) & 0xFF;
            count++;
        }
        return count == 0 ? -1 : (int) (sum / count);
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
