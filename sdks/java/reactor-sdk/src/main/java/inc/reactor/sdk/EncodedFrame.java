package inc.reactor.sdk;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import org.jspecify.annotations.Nullable;

/**
 * An encoded video frame for a {@link Reactor#step step}: one H.264 access unit, its bytes as they
 * are.
 *
 * <p>{@link #width()} and {@link #height()} are the frame it decodes to. Pass it in a step's media,
 * or push it with {@link Track#pushEncodedFrame(EncodedFrame)}. The runtime decodes a track's
 * encoded frames of one step as one stream, before the step runs, and the model reads the RGB
 * frames as if they had been pushed raw. So give them in decode order, starting at a keyframe — the
 * access units from the last keyframe to the frame the step is about:
 *
 * <pre>{@code
 * List<EncodedFrame> frames = window.stream()
 *         .map(accessUnit -> EncodedFrame.h264(accessUnit, 1280, 720))
 *         .toList();
 * StepResult result = reactor.step(null, List.of(), Map.of("webcam", frames)).join();
 * }</pre>
 *
 * <p>Immutable: the bytes are copied in when it is built, and {@link #data()} hands out a copy.
 */
public final class EncodedFrame implements StepInput {

    private final byte[] data;
    private final int width;
    private final int height;
    private final VideoCodec codec;
    private final byte @Nullable [] userData;
    private final @Nullable Long captureTimeUs;

    private EncodedFrame(Builder builder) {
        this.data = builder.data.clone();
        this.width = builder.width;
        this.height = builder.height;
        this.codec = builder.codec;
        this.userData = builder.userData == null ? null : builder.userData.clone();
        this.captureTimeUs = builder.captureTimeUs;
    }

    /**
     * One H.264 access unit, untagged and stamped with the time it is pushed.
     *
     * @param data the access unit, Annex B
     * @param width the width of the frame it decodes to, in pixels
     * @param height the height of the frame it decodes to, in pixels
     * @return the frame
     * @throws IllegalArgumentException when {@code data} is empty or a dimension is not positive
     */
    public static EncodedFrame h264(byte[] data, int width, int height) {
        return builder(data, width, height).build();
    }

    /**
     * Starts a frame that needs more than {@link #h264}: a tag, a capture time, another codec.
     *
     * @param data the encoded frame
     * @param width the width of the frame it decodes to, in pixels
     * @param height the height of the frame it decodes to, in pixels
     * @return a builder, with the codec set to {@link VideoCodec#H264}
     */
    public static Builder builder(byte[] data, int width, int height) {
        return new Builder(data, width, height);
    }

    /** @return a copy of the encoded bytes */
    public byte[] data() {
        return data.clone();
    }

    /** The bytes themselves, for the push, which copies them into native memory. */
    byte[] dataUnsafe() {
        return data;
    }

    /** @return the width of the frame it decodes to, in pixels */
    public int width() {
        return width;
    }

    /** @return the height of the frame it decodes to, in pixels */
    public int height() {
        return height;
    }

    /** @return how the bytes are encoded */
    public VideoCodec codec() {
        return codec;
    }

    /**
     * The tag the decoded frame carries, as {@link Track#pushFrame(byte[], int, int, byte[], Long)}
     * attaches one.
     *
     * @return a copy of the tag, or empty when the frame is untagged
     */
    public Optional<byte[]> userData() {
        return Optional.ofNullable(userData).map(bytes -> bytes.clone());
    }

    /** The tag itself, for the push. */
    byte @Nullable [] userDataUnsafe() {
        return userData;
    }

    /**
     * When the frame was captured, on the engine clock — see {@link Reactor#timeMicros()}.
     *
     * @return the capture time, or empty to stamp it with the time it is pushed
     */
    public OptionalLong captureTimeUs() {
        return captureTimeUs == null ? OptionalLong.empty() : OptionalLong.of(captureTimeUs);
    }

    /** The capture time itself, for the push. */
    @Nullable
    Long captureTimeUsOrNull() {
        return captureTimeUs;
    }

    /** Equal to another frame with the same bytes, size, codec, tag and capture time. */
    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof EncodedFrame that
                && width == that.width
                && height == that.height
                && codec == that.codec
                && Arrays.equals(data, that.data)
                && Arrays.equals(userData, that.userData)
                && Objects.equals(captureTimeUs, that.captureTimeUs);
    }

    @Override
    public int hashCode() {
        int hash = Objects.hash(width, height, codec, captureTimeUs);
        hash = 31 * hash + Arrays.hashCode(data);
        return 31 * hash + Arrays.hashCode(userData);
    }

    @Override
    public String toString() {
        return "EncodedFrame[" + codec + " " + width + "x" + height + ", " + data.length + " bytes]";
    }

    /** Builds an {@link EncodedFrame}. */
    public static final class Builder {

        private final byte[] data;
        private final int width;
        private final int height;
        private VideoCodec codec = VideoCodec.H264;
        private byte @Nullable [] userData;
        private @Nullable Long captureTimeUs;

        private Builder(byte[] data, int width, int height) {
            this.data = Objects.requireNonNull(data, "data");
            this.width = width;
            this.height = height;
        }

        /**
         * @param codec how the bytes are encoded; {@link VideoCodec#H264} unless set
         * @return this builder
         */
        public Builder codec(VideoCodec codec) {
            this.codec = Objects.requireNonNull(codec, "codec");
            return this;
        }

        /**
         * @param userData a tag for the decoded frame, which the model drops unless it declared
         *     that it reads tags; {@code null} for none
         * @return this builder
         */
        public Builder userData(byte @Nullable [] userData) {
            this.userData = userData;
            return this;
        }

        /**
         * @param captureTimeUs when the frame was captured, on the engine clock — see {@link
         *     Reactor#timeMicros()}. {@code null} stamps it with the time it is pushed
         * @return this builder
         */
        public Builder captureTimeUs(@Nullable Long captureTimeUs) {
            this.captureTimeUs = captureTimeUs;
            return this;
        }

        /**
         * @return the frame
         * @throws IllegalArgumentException when the data is empty or a dimension is not positive —
         *     the native library would drop the frame with nothing but a log line, and the step
         *     would run without it
         */
        public EncodedFrame build() {
            if (data.length == 0) {
                throw new IllegalArgumentException("an encoded frame needs data: one access unit, not zero bytes");
            }
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("an encoded frame's width and height are the decoded frame's,"
                        + " and must both be positive; got " + width + "x" + height);
            }
            return new EncodedFrame(this);
        }
    }
}
