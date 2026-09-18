package inc.reactor.sdk;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * One decoded audio frame: interleaved 16-bit PCM.
 *
 * <p>Same lifetime rule as {@link VideoFrame}: the samples belong to the FFI and are valid only
 * while the handler runs. Reading them afterwards throws {@link IllegalStateException}; {@link
 * #toShortArray()} is the copy.
 */
public final class AudioFrame {

    private final MemorySegment samples;
    private final int sampleCount;
    private final int sampleRate;
    private final int channels;

    AudioFrame(MemorySegment samples, int sampleCount, int sampleRate, int channels) {
        this.samples = samples;
        this.sampleCount = sampleCount;
        this.sampleRate = sampleRate;
        this.channels = channels;
    }

    /**
     * The interleaved samples.
     *
     * <p>Reading this after the handler has returned throws {@link IllegalStateException}.
     *
     * @return a view of the frame's memory
     */
    public MemorySegment samples() {
        return samples;
    }

    /** @return the total number of samples, across every channel */
    public int sampleCount() {
        return sampleCount;
    }

    /** @return samples per second */
    public int sampleRate() {
        return sampleRate;
    }

    /** @return how many channels the samples are interleaved across */
    public int channels() {
        return channels;
    }

    /**
     * Copies the samples onto the Java heap.
     *
     * @return a copy that outlives the handler
     */
    public short[] toShortArray() {
        return samples.toArray(ValueLayout.JAVA_SHORT);
    }
}
