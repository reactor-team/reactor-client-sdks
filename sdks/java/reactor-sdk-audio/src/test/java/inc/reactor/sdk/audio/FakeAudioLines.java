package inc.reactor.sdk.audio;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Audio devices that do what a test needs: block, fail, or disappear.
 *
 * <p>The point is that {@link Microphone} and {@link Speaker} run unchanged against these, so the
 * races they are written to survive can be caused on purpose rather than waited for. A race nobody
 * can reproduce is an anecdote.
 */
final class FakeAudioLines implements AudioLines {

    final Capture capture = new Capture();
    final Playback playback = new Playback();

    /** Set to make opening fail, the way a device already in use does. */
    boolean captureUnavailable;

    boolean playbackUnavailable;

    @Override
    public CaptureLine openCapture(PcmFormat format) {
        if (captureUnavailable) {
            throw new AudioDeviceException("no microphone is available");
        }
        return capture;
    }

    @Override
    public PlaybackLine openPlayback(PcmFormat format) {
        if (playbackUnavailable) {
            throw new AudioDeviceException("no speaker is available");
        }
        return playback;
    }

    /** A microphone whose reads block until a test feeds it, the way a real one does. */
    static final class Capture implements CaptureLine {

        private final LinkedBlockingQueue<byte[]> blocks = new LinkedBlockingQueue<>();
        private final AtomicBoolean stopped = new AtomicBoolean();

        /** Counted down the first time a read blocks, so a test can close at exactly that moment. */
        final CountDownLatch blockedInRead = new CountDownLatch(1);

        final AtomicInteger starts = new AtomicInteger();
        final AtomicBoolean closed = new AtomicBoolean();

        /** Set to make the next read throw, the way an unplugged device does. */
        volatile boolean failNextRead;

        void feed(byte[] block) {
            blocks.add(block);
        }

        @Override
        public void start() {
            starts.incrementAndGet();
        }

        @Override
        public int read(byte[] buffer) {
            if (failNextRead) {
                throw new IllegalStateException("the device went away");
            }
            blockedInRead.countDown();
            byte[] block;
            try {
                // Blocks, like a real line with no audio yet. stop() is what releases it.
                block = blocks.poll(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return -1;
            }
            if (block == null || stopped.get()) {
                return -1;
            }
            int length = Math.min(block.length, buffer.length);
            System.arraycopy(block, 0, buffer, 0, length);
            return length;
        }

        @Override
        public void stop() {
            stopped.set(true);
            // Releases a blocked read, which is what a real line's stop() + flush() does. Without
            // it, close() would join a thread waiting for audio that never comes.
            blocks.add(new byte[0]);
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    /** A speaker whose writes can be made to block until a test lets them through. */
    static final class Playback implements PlaybackLine {

        private final AtomicBoolean stopped = new AtomicBoolean();

        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger bytesWritten = new AtomicInteger();
        final AtomicBoolean closed = new AtomicBoolean();
        final CountDownLatch blockedInWrite = new CountDownLatch(1);

        /** Set to make writes block until the line is stopped, the way a full buffer does. */
        volatile boolean bufferFull;

        /** Set to make the next write throw, the way a device that vanished does. */
        volatile boolean failNextWrite;

        @Override
        public void start() {
            starts.incrementAndGet();
        }

        @Override
        public int write(byte[] buffer, int length) {
            if (failNextWrite) {
                throw new IllegalStateException("the device went away");
            }
            if (bufferFull) {
                blockedInWrite.countDown();
                while (!stopped.get()) {
                    Thread.onSpinWait();
                }
                return 0;
            }
            bytesWritten.addAndGet(length);
            return length;
        }

        @Override
        public void stop() {
            stopped.set(true);
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }
}
