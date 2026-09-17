package inc.reactor.sdk.audio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Captures from a microphone and hands the samples on.
 *
 * <p>Nothing here happens because a model declared a sendonly audio track. This module is a
 * separate artifact and opening a device is an explicit call, which is the whole reason the core
 * pins the synthetic audio module and has no dependency on {@code java.desktop}.
 *
 * <pre>{@code
 * Track mic = reactor.track("mic_in");
 * mic.publish().join();
 * try (Microphone microphone = Microphone.open(new PcmFormat(48_000, 1), pcm ->
 *         mic.pushFrame(pcm, 48_000, 1))) {
 *     microphone.start();
 *     // ...
 * }
 * }</pre>
 *
 * <p><b>On stopping.</b> The C++ SDK has an asymmetry here worth explaining rather than copying:
 * there the OS calls you back, so closing a capture device waits for the capture callback, and a
 * callback taking the same mutex the closer holds deadlocks. Java has no such callback — this owns
 * its reader thread — so the shape is different and the rule is the same. {@link #close()} holds no
 * lock the reader needs: it clears an atomic, stops the line so a blocked {@code read} returns,
 * joins, and only then closes. Holding a lock across any of that is what would hang.
 */
public final class Microphone implements AutoCloseable {

    /** How much audio each read asks for. 20 ms is a frame size every Reactor model accepts. */
    private static final int MILLIS_PER_READ = 20;

    private final AudioLines.CaptureLine line;
    private final PcmFormat format;
    private final Consumer<short[]> samples;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Thread reader;

    private Microphone(AudioLines.CaptureLine line, PcmFormat format, Consumer<short[]> samples) {
        this.line = line;
        this.format = format;
        this.samples = samples;
    }

    /**
     * Opens the host's microphone.
     *
     * @param format what to capture; the core carries signed 16-bit PCM and nothing else
     * @param samples told each block of interleaved samples, on the capture thread
     * @return the microphone, not yet capturing
     * @throws AudioDeviceException when no device can provide that format
     */
    public static Microphone open(PcmFormat format, Consumer<short[]> samples) {
        return open(AudioLines.system(), format, samples);
    }

    /**
     * Opens a microphone from a given backend.
     *
     * @param lines where the device comes from
     * @param format what to capture
     * @param samples told each block of interleaved samples, on the capture thread
     * @return the microphone, not yet capturing
     */
    public static Microphone open(AudioLines lines, PcmFormat format, Consumer<short[]> samples) {
        return new Microphone(lines.openCapture(format), format, samples);
    }

    /**
     * Starts capturing.
     *
     * <p>Idempotent. Samples arrive on this microphone's own thread, so whatever the consumer does
     * with them is not on the caller's.
     */
    public void start() {
        if (closed.get()) {
            throw new AudioDeviceException("this microphone is closed");
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        line.start();
        Thread thread = new Thread(this::capture, "reactor-microphone");
        thread.setDaemon(true);
        reader = thread;
        thread.start();
    }

    /**
     * Whether this microphone is capturing.
     *
     * @return true while the reader thread is running
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Stops capturing and releases the device.
     *
     * <p>Idempotent, and safe to call while a read is blocked.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // An atomic rather than a lock, and cleared first. The reader checks it after every read,
        // so a block captured while stopping is dropped rather than delivered to a consumer that
        // has already been torn down.
        running.set(false);
        // Unblocks a reader waiting inside read(). Without this the join below waits for a thread
        // that is waiting for audio that will never come.
        line.stop();
        Thread thread = reader;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(2_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        line.close();
    }

    private void capture() {
        byte[] buffer = new byte[format.sampleRate() / (1000 / MILLIS_PER_READ) * format.frameBytes()];
        try {
            capture(buffer);
        } finally {
            // Whichever way the loop ends — a device that stopped, a consumer that threw, an
            // interrupt — the public state says capture has stopped, because it has.
            running.set(false);
        }
    }

    private void capture(byte[] buffer) {
        while (running.get()) {
            int read;
            try {
                read = line.read(buffer);
            } catch (RuntimeException deviceLost) {
                // The device went away — unplugged, or taken by something else. Stopping is the
                // only honest answer; pretending to still capture would produce silence nobody
                // could explain.
                running.set(false);
                return;
            }
            if (read < 0) {
                // Not the same as an empty read. The line says -1 when it has stopped, which a
                // backend can do without close() and without throwing — a device lost quietly. The
                // old branch treated it as "nothing this time" and went straight round again, so
                // the daemon thread spun at full speed forever while isRunning() went on saying
                // yes.
                running.set(false);
                return;
            }
            if (read == 0) {
                continue;
            }
            // Checked again after the read: the block that was in flight while close() ran belongs
            // to a consumer that may no longer be there.
            if (!running.get()) {
                return;
            }
            try {
                samples.accept(toSamples(buffer, read));
            } catch (RuntimeException | Error thrown) {
                // The consumer is application code. An exception here used to end the reader
                // thread without clearing the flag, so isRunning() reported capture that had
                // stopped and nothing arrived again — the state said one thing and the device
                // another. Stopping is the honest answer, and it is what the flag now says.
                running.set(false);
                throw thrown;
            }
        }
    }

    private static short[] toSamples(byte[] buffer, int length) {
        short[] pcm = new short[length / 2];
        ByteBuffer.wrap(buffer, 0, length)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()
                .get(pcm);
        return pcm;
    }
}
