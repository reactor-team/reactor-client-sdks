package inc.reactor.sdk.audio;

import inc.reactor.sdk.AudioFrame;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Plays audio a model sent.
 *
 * <pre>{@code
 * try (Speaker speaker = Speaker.open(new PcmFormat(48_000, 1))) {
 *     speaker.start();
 *     reactor.track("voice_out").onFrame((AudioFrame frame) -> speaker.play(frame));
 * }
 * }</pre>
 *
 * <p><b>Where this is called from matters.</b> A track's frame handler runs inline on the FFI's own
 * delivery thread, so {@link #play} usually runs there too — and it blocks while the line's buffer
 * is full, which is exactly the backpressure that path is designed around. What it must never do is
 * block forever: {@link #close()} stops the line, which unblocks a write that is waiting, before it
 * closes anything.
 *
 * <p>Same rule as {@link Microphone} and for the same reason, even though the mechanism differs:
 * closing holds no lock that the writing thread needs.
 */
public final class Speaker implements AutoCloseable {

    private final AudioLines.PlaybackLine line;
    private final PcmFormat format;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private Speaker(AudioLines.PlaybackLine line, PcmFormat format) {
        this.line = line;
        this.format = format;
    }

    /**
     * Opens the host's speaker.
     *
     * @param format what will be played; the core carries signed 16-bit PCM and nothing else
     * @return the speaker, not yet playing
     * @throws AudioDeviceException when no device can provide that format
     */
    public static Speaker open(PcmFormat format) {
        return open(AudioLines.system(), format);
    }

    /**
     * Opens a speaker from a given backend.
     *
     * @param lines where the device comes from
     * @param format what will be played
     * @return the speaker, not yet playing
     */
    public static Speaker open(AudioLines lines, PcmFormat format) {
        return new Speaker(lines.openPlayback(format), format);
    }

    /** Starts playing. Idempotent. */
    public void start() {
        if (closed.get()) {
            throw new AudioDeviceException("this speaker is closed");
        }
        if (running.compareAndSet(false, true)) {
            line.start();
        }
    }

    /** @return whether this speaker is playing */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Plays one frame the model sent.
     *
     * @param frame the frame, valid only for the duration of its handler — this copies it before
     *     returning, which is what makes calling it from a frame handler safe
     * @throws AudioDeviceException when the frame's format is not the one this speaker opened
     */
    public void play(AudioFrame frame) {
        if (frame.sampleRate() != format.sampleRate() || frame.channels() != format.channels()) {
            // Playing it anyway would produce audio at the wrong pitch, which sounds like a model
            // problem rather than a configuration one.
            throw new AudioDeviceException("this speaker was opened for " + format.sampleRate() + " Hz / "
                    + format.channels() + " channel(s), and this frame is " + frame.sampleRate() + " Hz / "
                    + frame.channels() + ". Open the speaker with the format the track declares.");
        }
        play(frame.toShortArray());
    }

    /**
     * Plays interleaved samples.
     *
     * @param pcm signed 16-bit samples, across every channel
     */
    public void play(short[] pcm) {
        if (!running.get()) {
            // Dropped rather than buffered. A speaker that is not playing has nowhere to put this,
            // and holding it would mean audio arriving late once it starts.
            return;
        }
        byte[] bytes = new byte[pcm.length * 2];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm);
        try {
            line.write(bytes, bytes.length);
        } catch (RuntimeException deviceLost) {
            running.set(false);
            throw new AudioDeviceException(
                    "this speaker stopped accepting audio: " + deviceLost.getMessage(), deviceLost);
        }
    }

    /**
     * Stops playing and releases the device.
     *
     * <p>Idempotent, and safe to call while a write is blocked.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        running.set(false);
        // Unblocks a write waiting for buffer space. Closing a line a thread is blocked writing to
        // is the hang this ordering avoids.
        line.stop();
        line.close();
    }
}
