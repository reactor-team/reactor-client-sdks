package inc.reactor.sdk;

import java.util.Locale;
import java.util.Optional;

/**
 * Which way media flows on a track.
 *
 * <p>From the client's point of view: {@link #RECVONLY} is the model producing and this client
 * receiving, {@link #SENDONLY} is this client producing and the model receiving.
 */
public enum TrackDirection {
    /** This client sends. Push frames into it, after publishing. */
    SENDONLY,
    /** This client receives. Register a frame handler on it. */
    RECVONLY;

    /**
     * @param direction {@code "sendonly"} or {@code "recvonly"}, as the session declares it
     * @return the matching constant, or empty for a direction this SDK does not know
     */
    public static Optional<TrackDirection> of(String direction) {
        try {
            return Optional.of(valueOf(direction.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }
}
