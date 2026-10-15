package inc.reactor.sdk;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.OptionalLong;
import org.jspecify.annotations.Nullable;

/**
 * One frame a {@link Reactor#step step} produced on an output track: an n-dimensional array, its
 * element type and its shape.
 *
 * <p>Video is {@code (height, width, 3)} {@code uint8} RGB — three bytes a pixel, in R, G, B order,
 * unlike the BGRA a streamed {@link VideoFrame} carries. Audio is {@code (channels, samples)}
 * {@code int16} PCM, planar rather than interleaved. Multi-byte elements are little-endian, which is
 * the order {@link #data()} is already set to, so {@code frame.data().asShortBuffer()} reads audio
 * samples directly.
 *
 * <p>Unlike a streamed frame this is <b>not</b> scoped to a callback: it was copied onto the heap
 * once, when the step completed, and the native result was freed straight away. Keep it as long as
 * you like.
 */
public final class StepFrame {

    private final String encoding;
    private final String dtype;
    private final int[] shape;
    private final byte[] data;
    private final byte @Nullable [] metadata;
    private final @Nullable Long captureTimeUs;

    StepFrame(
            String encoding,
            String dtype,
            int[] shape,
            byte[] data,
            byte @Nullable [] metadata,
            @Nullable Long captureTimeUs) {
        this.encoding = encoding;
        this.dtype = dtype;
        this.shape = shape;
        this.data = data;
        this.metadata = metadata;
        this.captureTimeUs = captureTimeUs;
    }

    /**
     * How the frame travelled on the wire — {@code "raw"} today. The bytes in {@link #data()} are
     * already decoded whatever this says.
     *
     * @return the wire encoding
     */
    public String encoding() {
        return encoding;
    }

    /**
     * The element type, in NumPy notation: {@code "uint8"} for video, {@code "int16"} for audio.
     *
     * @return the element type
     */
    public String dtype() {
        return dtype;
    }

    /**
     * The array's dimensions: {@code (height, width, 3)} for video, {@code (channels, samples)} for
     * audio.
     *
     * @return a copy of the shape
     */
    public int[] shape() {
        return shape.clone();
    }

    /**
     * The array's bytes, row-major: the product of {@link #shape()} times the size of {@link
     * #dtype()}.
     *
     * @return a read-only, little-endian view over this frame's bytes; each call answers a fresh
     *     view, so its position is the caller's own
     */
    public ByteBuffer data() {
        return ByteBuffer.wrap(data).asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    /** @return a copy of the array's bytes */
    public byte[] toByteArray() {
        return data.clone();
    }

    /** @return how many bytes {@link #data()} holds */
    public int byteSize() {
        return data.length;
    }

    /**
     * What the sender attached to the frame, opaque to the transport.
     *
     * @return a copy of the tag, or empty when the sender attached nothing — an attached but empty
     *     tag is present and empty
     */
    public Optional<byte[]> metadata() {
        return Optional.ofNullable(metadata).map(bytes -> bytes.clone());
    }

    /**
     * When the sender says the frame was captured, in microseconds.
     *
     * @return the capture time, or empty when the frame carries none
     */
    public OptionalLong captureTimeUs() {
        return captureTimeUs == null ? OptionalLong.empty() : OptionalLong.of(captureTimeUs);
    }

    /**
     * Equal to another frame with the same encoding, dtype, shape, bytes, metadata and capture time,
     * so the records that hold frames ({@link StepTrackOutput}, {@link StepResult}) compare by value
     * too. No metadata and empty metadata are different frames, as they are on the wire.
     */
    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof StepFrame that
                && encoding.equals(that.encoding)
                && dtype.equals(that.dtype)
                && java.util.Arrays.equals(shape, that.shape)
                && java.util.Arrays.equals(data, that.data)
                && java.util.Arrays.equals(metadata, that.metadata)
                && java.util.Objects.equals(captureTimeUs, that.captureTimeUs);
    }

    @Override
    public int hashCode() {
        int hash = java.util.Objects.hash(encoding, dtype, captureTimeUs);
        hash = 31 * hash + java.util.Arrays.hashCode(shape);
        hash = 31 * hash + java.util.Arrays.hashCode(data);
        return 31 * hash + java.util.Arrays.hashCode(metadata);
    }

    @Override
    public String toString() {
        return "StepFrame[" + dtype + " " + java.util.Arrays.toString(shape) + ", " + data.length + " bytes]";
    }
}
