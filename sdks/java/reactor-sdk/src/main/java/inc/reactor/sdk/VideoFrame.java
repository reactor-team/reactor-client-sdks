package inc.reactor.sdk;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * One decoded video frame.
 *
 * <p><b>Valid only for the duration of the handler call.</b> The pixels belong to the FFI, which
 * reuses them the moment the handler returns. Keeping a frame and reading it later would be a
 * use-after-free in any other binding; here the underlying memory is scoped to the callback, so
 * doing it throws {@link IllegalStateException} instead. Copy what you keep — {@link
 * #toByteArray()} is the copy.
 *
 * <p>Handlers run inline on the FFI's delivery thread, on purpose. Blocking there is the
 * backpressure: the FFI keeps only the newest frame while the handler runs, so a slow handler drops
 * frames rather than growing a queue.
 */
public final class VideoFrame {

    private final MemorySegment pixels;
    private final int width;
    private final int height;
    private final long frameId;
    private final long timestampUs;
    private final byte @Nullable [] userData;

    VideoFrame(
            MemorySegment pixels, int width, int height, long frameId, long timestampUs, byte @Nullable [] userData) {
        this.pixels = pixels;
        this.width = width;
        this.height = height;
        this.frameId = frameId;
        this.timestampUs = timestampUs;
        this.userData = userData;
    }

    /** @return frame width in pixels */
    public int width() {
        return width;
    }

    /** @return frame height in pixels */
    public int height() {
        return height;
    }

    /**
     * The raw BGRA pixels — {@code width * height * 4} bytes, in B, G, R, A order.
     *
     * <p>Reading this after the handler has returned throws {@link IllegalStateException}.
     *
     * @return a read-only view of the frame's memory
     */
    public MemorySegment pixels() {
        return pixels;
    }

    /**
     * Copies the pixels onto the Java heap.
     *
     * <p>{@code width * height * 4} bytes — 8 MB for 1080p, so this is a real cost at frame rate.
     * Read {@link #pixels()} directly when the handler can do its work before returning.
     *
     * @return a copy that outlives the handler
     */
    public byte[] toByteArray() {
        return pixels.toArray(ValueLayout.JAVA_BYTE);
    }

    /**
     * @return the sender's frame id, or 0 when the frame carried no metadata trailer
     */
    public long frameId() {
        return frameId;
    }

    /**
     * When the sender says it captured this frame, in microseconds on the sender's own clock.
     *
     * <p>Differences between stamps from one sender are what this supports. It is not comparable
     * with a local clock, and it is 0 when the frame carried no metadata.
     *
     * @return the sender's capture time
     */
    public long timestampUs() {
        return timestampUs;
    }

    /**
     * The tag the sender attached to this frame.
     *
     * <p>Already copied, so unlike {@link #pixels()} it outlives the handler. A tag is dropped
     * unless the far end declared that it reads tags.
     *
     * @return the tag, or empty when the frame carried none
     */
    public Optional<byte[]> userData() {
        return Optional.ofNullable(userData).map(bytes -> bytes.clone());
    }
}
