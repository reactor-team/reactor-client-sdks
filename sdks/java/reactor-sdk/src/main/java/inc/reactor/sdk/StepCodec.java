package inc.reactor.sdk;

import java.util.Locale;
import java.util.Objects;

/**
 * A codec a stepped session's video step frames can travel in between this client and the runtime.
 *
 * <p>Declared in the default order of preference: H.264, VP9, AV1, VP8. See {@link
 * Reactor#setCodecPreference(java.util.List, String)} for choosing others. Whatever the codec, the
 * host pushes and receives RGB: the encoding is between this client and the runtime.
 */
public enum StepCodec {
    /** H.264. The default first choice. */
    H264("h264"),
    /** VP9. */
    VP9("vp9"),
    /** AV1. */
    AV1("av1"),
    /** VP8. */
    VP8("vp8");

    private final String wireName;

    StepCodec(String wireName) {
        this.wireName = wireName;
    }

    /**
     * @return the name the native library and {@link Reactor#stepFrameEncodings()} use for it:
     *     {@code "h264"}, {@code "vp9"}, {@code "av1"} or {@code "vp8"}
     */
    public String wireName() {
        return wireName;
    }

    /**
     * Reads a codec by name, in any case.
     *
     * @param name {@code "h264"}, {@code "vp9"}, {@code "av1"} or {@code "vp8"}
     * @return the codec
     * @throws IllegalArgumentException when {@code name} names none of them
     */
    public static StepCodec of(String name) {
        Objects.requireNonNull(name, "name");
        String lower = name.toLowerCase(Locale.ROOT);
        for (StepCodec codec : values()) {
            if (codec.wireName.equals(lower)) {
                return codec;
            }
        }
        throw new IllegalArgumentException("unknown step codec \"" + name + "\": expected one of h264, vp9, av1, vp8");
    }
}
