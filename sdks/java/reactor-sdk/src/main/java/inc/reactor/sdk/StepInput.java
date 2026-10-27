package inc.reactor.sdk;

import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One input frame of a {@link Reactor#step(JsonValue, java.util.List, java.util.Map) step}'s media:
 * an {@link EncodedFrame}, or a raw frame as {@link Track#pushFrame} takes it.
 *
 * <pre>{@code
 * reactor.step(null, List.of(), Map.of(
 *         "webcam", List.of(EncodedFrame.h264(idr, 1280, 720), EncodedFrame.h264(p, 1280, 720)),
 *         "mic", List.of(StepInput.audio(pcm, 48_000, 1))));
 * }</pre>
 *
 * <p>A raw frame holds the array it was given rather than a copy; the step copies it into native
 * memory when it pushes it, so the array can be reused once {@code step} has returned.
 */
public sealed interface StepInput permits EncodedFrame, StepInput.Video, StepInput.Audio {

    /**
     * A raw BGRA video frame, as {@link Track#pushFrame(byte[], int, int)} takes it.
     *
     * @param bgra exactly {@code width * height * 4} bytes, in B, G, R, A order
     * @param width frame width in pixels
     * @param height frame height in pixels
     * @return the frame
     */
    static Video video(byte[] bgra, int width, int height) {
        return new Video(bgra, width, height, null, null, PixelFormat.BGRA);
    }

    /**
     * A raw video frame laid out as {@code format}, as {@link Track#pushFrame(byte[], PixelFormat,
     * int, int)} takes it.
     *
     * @param pixels exactly {@code width * height * format.bytesPerPixel()} bytes
     * @param format how the pixels are laid out
     * @param width frame width in pixels
     * @param height frame height in pixels
     * @return the frame
     */
    static Video video(byte[] pixels, PixelFormat format, int width, int height) {
        return new Video(pixels, width, height, null, null, format);
    }

    /**
     * Interleaved 16-bit PCM, as {@link Track#pushFrame(short[], int, int)} takes it.
     *
     * @param pcm the samples, across every channel
     * @param sampleRate samples per second
     * @param channels how many channels the samples are interleaved across
     * @return the frame
     */
    static Audio audio(short[] pcm, int sampleRate, int channels) {
        return new Audio(pcm, sampleRate, channels);
    }

    /**
     * A raw video frame, BGRA or RGB. Checked when it is pushed, as {@link Track#pushFrame(byte[],
     * PixelFormat, int, int, byte[], Long)} checks one.
     *
     * @param pixels exactly {@code width * height * format.bytesPerPixel()} bytes
     * @param width frame width in pixels
     * @param height frame height in pixels
     * @param userData a tag for the far end, or {@code null}
     * @param captureTimeUs when it was captured on the engine clock, or {@code null} for now
     * @param format how the pixels are laid out
     */
    record Video(
            byte[] pixels,
            int width,
            int height,
            byte @Nullable [] userData,
            @Nullable Long captureTimeUs,
            PixelFormat format)
            implements StepInput {

        /** @throws NullPointerException when {@code bgra} is null */
        public Video {
            Objects.requireNonNull(pixels, "pixels");
            Objects.requireNonNull(format, "format");
        }

        /** Equal to another frame with the same pixels, size, tag and capture time. */
        @Override
        public boolean equals(@Nullable Object other) {
            return other instanceof Video that
                    && width == that.width
                    && height == that.height
                    && format == that.format
                    && Arrays.equals(pixels, that.pixels)
                    && Arrays.equals(userData, that.userData)
                    && Objects.equals(captureTimeUs, that.captureTimeUs);
        }

        @Override
        public int hashCode() {
            int hash = Objects.hash(width, height, captureTimeUs, format);
            hash = 31 * hash + Arrays.hashCode(pixels);
            return 31 * hash + Arrays.hashCode(userData);
        }

        @Override
        public String toString() {
            return "StepInput.Video[" + width + "x" + height + " " + format + ", " + pixels.length + " bytes]";
        }
    }

    /**
     * Interleaved 16-bit PCM. Checked when it is pushed, as {@link Track#pushFrame(short[], int,
     * int)} checks it.
     *
     * @param pcm the samples, across every channel
     * @param sampleRate samples per second
     * @param channels how many channels the samples are interleaved across
     */
    record Audio(short[] pcm, int sampleRate, int channels) implements StepInput {

        /** @throws NullPointerException when {@code pcm} is null */
        public Audio {
            Objects.requireNonNull(pcm, "pcm");
        }

        /** Equal to another frame with the same samples, rate and channel count. */
        @Override
        public boolean equals(@Nullable Object other) {
            return other instanceof Audio that
                    && sampleRate == that.sampleRate
                    && channels == that.channels
                    && Arrays.equals(pcm, that.pcm);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hash(sampleRate, channels) + Arrays.hashCode(pcm);
        }

        @Override
        public String toString() {
            return "StepInput.Audio[" + sampleRate + " Hz x" + channels + ", " + pcm.length + " samples]";
        }
    }
}
