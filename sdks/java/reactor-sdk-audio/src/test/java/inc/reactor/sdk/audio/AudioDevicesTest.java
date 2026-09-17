package inc.reactor.sdk.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Microphone and speaker, against devices a test can make misbehave on purpose. */
final class AudioDevicesTest {

    private static final PcmFormat FORMAT = new PcmFormat(48_000, 1);

    @Test
    @DisplayName("opening a microphone does not start it capturing")
    void openingDoesNotCapture() {
        FakeAudioLines lines = new FakeAudioLines();

        try (Microphone microphone = Microphone.open(lines, FORMAT, pcm -> {})) {
            // The whole reason this module is a separate artifact: nothing captures until asked.
            assertEquals(0, lines.capture.starts.get());
            assertFalse(microphone.isRunning());
        }
    }

    @Test
    @DisplayName("captured bytes reach the consumer as little-endian samples")
    @Timeout(10)
    void capturedBytesBecomeSamples() throws Exception {
        FakeAudioLines lines = new FakeAudioLines();
        List<short[]> heard = new CopyOnWriteArrayList<>();
        CountDownLatch delivered = new CountDownLatch(1);

        try (Microphone microphone = Microphone.open(lines, FORMAT, pcm -> {
            heard.add(pcm);
            delivered.countDown();
        })) {
            microphone.start();
            // 0x0100 = 256 and 0xFF7F = 32767, little-endian. Reading these big-endian would give
            // 1 and -129, which is the kind of wrong that sounds like noise rather than an error.
            lines.capture.feed(new byte[] {0x00, 0x01, (byte) 0xFF, 0x7F});

            assertTrue(delivered.await(5, TimeUnit.SECONDS), "nothing was captured");
            assertArrayEquals(new short[] {256, 32767}, heard.get(0));
        }
    }

    @Test
    @DisplayName("closing while a read is blocked returns, rather than hanging")
    @Timeout(10)
    void closingWhileBlockedInReadReturns() throws Exception {
        FakeAudioLines lines = new FakeAudioLines();

        Microphone microphone = Microphone.open(lines, FORMAT, pcm -> {});
        microphone.start();
        // Close at exactly the moment the reader is inside a blocking read. This is the race the
        // ordering in close() exists for: clear the flag, stop the line so the read returns, then
        // join. Joining first would wait for a thread waiting for audio that never comes.
        assertTrue(lines.capture.blockedInRead.await(5, TimeUnit.SECONDS), "the reader never blocked");

        long start = System.nanoTime();
        microphone.close();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertFalse(microphone.isRunning());
        assertTrue(lines.capture.closed.get(), "the line must be closed, and only after the reader stopped");
        // Timed, because the bounded waits on both sides hide the bug otherwise: without the
        // stop() that releases a blocked read, close() falls through to its own join timeout and
        // still returns — two seconds later, every time a microphone is closed.
        assertTrue(
                elapsedMillis < 1_000,
                "closing took " + elapsedMillis + "ms; the reader was not released, only waited out");
    }

    @Test
    @DisplayName("a block captured while stopping is dropped, not handed to a torn-down consumer")
    @Timeout(10)
    void aBlockCapturedWhileStoppingIsDropped() throws Exception {
        FakeAudioLines lines = new FakeAudioLines();
        List<short[]> heard = new CopyOnWriteArrayList<>();

        Microphone microphone = Microphone.open(lines, FORMAT, heard::add);
        microphone.start();
        assertTrue(lines.capture.blockedInRead.await(5, TimeUnit.SECONDS));

        // The block and the close race. Whichever wins, the consumer must not be called after
        // close() returned — it may have been torn down by then.
        lines.capture.feed(new byte[] {0x01, 0x02});
        microphone.close();

        int afterClose = heard.size();
        Thread.sleep(100);
        assertEquals(afterClose, heard.size(), "the consumer was called after close() returned");
    }

    @Test
    @DisplayName("close is idempotent, and safe to call twice")
    void closeIsIdempotent() {
        FakeAudioLines lines = new FakeAudioLines();
        Microphone microphone = Microphone.open(lines, FORMAT, pcm -> {});

        microphone.start();
        microphone.close();
        microphone.close();

        assertTrue(lines.capture.closed.get());
    }

    @Test
    @DisplayName("a device that goes away stops the microphone instead of producing silence")
    @Timeout(10)
    void aLostDeviceStopsCapture() throws Exception {
        FakeAudioLines lines = new FakeAudioLines();
        lines.capture.failNextRead = true;

        try (Microphone microphone = Microphone.open(lines, FORMAT, pcm -> {})) {
            microphone.start();

            // Pretending to still capture would produce silence nobody could explain.
            for (int attempt = 0; attempt < 50 && microphone.isRunning(); attempt++) {
                Thread.sleep(20);
            }
            assertFalse(microphone.isRunning(), "a lost device must stop the microphone");
        }
    }

    @Test
    @DisplayName("a microphone that cannot be opened says so, rather than capturing nothing")
    void anUnavailableMicrophoneIsRefused() {
        FakeAudioLines lines = new FakeAudioLines();
        lines.captureUnavailable = true;

        assertThrows(AudioDeviceException.class, () -> Microphone.open(lines, FORMAT, pcm -> {}));
    }

    @Test
    @DisplayName("a speaker plays what it is given, and drops what arrives before it starts")
    void aSpeakerDropsWhatArrivesBeforeItStarts() {
        FakeAudioLines lines = new FakeAudioLines();

        try (Speaker speaker = Speaker.open(lines, FORMAT)) {
            speaker.play(new short[] {1, 2, 3});
            // Buffering this would mean audio arriving late once the speaker starts, which is
            // worse than not hearing it at all.
            assertEquals(0, lines.playback.bytesWritten.get());

            speaker.start();
            speaker.play(new short[] {1, 2, 3});
            assertEquals(6, lines.playback.bytesWritten.get());
        }
    }

    @Test
    @DisplayName("closing while a write is blocked returns, rather than hanging")
    @Timeout(10)
    void closingWhileBlockedInWriteReturns() throws Exception {
        FakeAudioLines lines = new FakeAudioLines();
        lines.playback.bufferFull = true;
        Speaker speaker = Speaker.open(lines, FORMAT);
        speaker.start();

        // A frame handler runs inline on the FFI's delivery thread, so this is that thread stuck
        // in a write while something else closes the speaker.
        Thread writer = new Thread(() -> speaker.play(new short[] {1, 2, 3}), "test-writer");
        writer.setDaemon(true);
        writer.start();
        assertTrue(lines.playback.blockedInWrite.await(5, TimeUnit.SECONDS), "the writer never blocked");

        speaker.close();

        writer.join(5_000);
        assertFalse(writer.isAlive(), "closing must unblock the writer, not wait for it");
    }

    @Test
    @DisplayName("a frame in another format is refused, rather than played at the wrong pitch")
    void aMismatchedFrameIsRefused() {
        FakeAudioLines lines = new FakeAudioLines();

        try (Speaker speaker = Speaker.open(lines, new PcmFormat(48_000, 1))) {
            speaker.start();

            AudioDeviceException thrown = assertThrows(
                    AudioDeviceException.class, () -> speaker.play(TestFrames.audio(new short[] {1}, 16_000, 2)));

            // Playing it anyway sounds like a model problem rather than a configuration one.
            assertTrue(thrown.getMessage().contains("16000"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("48000"), thrown.getMessage());
        }
    }
}
