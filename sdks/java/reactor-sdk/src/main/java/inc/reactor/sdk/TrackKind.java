package inc.reactor.sdk;

import java.util.Locale;
import java.util.Optional;

/** What kind of media a track carries. */
public enum TrackKind {
    /** BGRA video frames. */
    VIDEO,
    /** Interleaved 16-bit PCM audio. */
    AUDIO;

    /**
     * @param kind {@code "video"} or {@code "audio"}, as the session declares it
     * @return the matching constant, or empty for a kind this SDK does not know
     */
    public static Optional<TrackKind> of(String kind) {
        try {
            return Optional.of(valueOf(kind.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }
}
