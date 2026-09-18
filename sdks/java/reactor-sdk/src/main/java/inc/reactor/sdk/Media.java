package inc.reactor.sdk;

import java.lang.foreign.MemorySegment;
import org.jspecify.annotations.Nullable;

/**
 * Builds frames for the internals.
 *
 * <p>{@link VideoFrame} and {@link AudioFrame} have package-private constructors so nothing outside
 * this package can invent one; the code that receives them lives in another package and needs a way
 * in. This is that way, and nothing else.
 */
public final class Media {

    private Media() {}

    /**
     * @param pixels BGRA, scoped to the callback
     * @param width frame width
     * @param height frame height
     * @param frameId the sender's frame id, or 0
     * @param timestampUs the sender's capture time, or 0
     * @param userData the sender's tag, already copied, or {@code null}
     * @return the frame
     */
    public static VideoFrame videoFrame(
            MemorySegment pixels, int width, int height, long frameId, long timestampUs, byte @Nullable [] userData) {
        return new VideoFrame(pixels, width, height, frameId, timestampUs, userData);
    }

    /**
     * @param samples interleaved 16-bit PCM, scoped to the callback
     * @param sampleCount total samples across every channel
     * @param sampleRate samples per second
     * @param channels how many channels
     * @return the frame
     */
    public static AudioFrame audioFrame(MemorySegment samples, int sampleCount, int sampleRate, int channels) {
        return new AudioFrame(samples, sampleCount, sampleRate, channels);
    }
}
