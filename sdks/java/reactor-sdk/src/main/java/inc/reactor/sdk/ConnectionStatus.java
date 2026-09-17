package inc.reactor.sdk;

import java.util.Locale;
import java.util.Optional;

/** Where a client is between "not connected" and "the model is producing". */
public enum ConnectionStatus {

    /** No session, or the one there was is gone. */
    DISCONNECTED,

    /** Negotiating: the session exists and the transport is being established. */
    CONNECTING,

    /** Connected, and waiting for the model to be ready to work. */
    WAITING,

    /** The model is ready. Commands are answered and media flows. */
    READY;

    /**
     * The status the FFI reports under this name.
     *
     * @param status one of {@code "disconnected"}, {@code "connecting"}, {@code "waiting"},
     *     {@code "ready"}
     * @return the matching constant, or empty for a status this SDK does not know
     */
    public static Optional<ConnectionStatus> of(String status) {
        try {
            return Optional.of(valueOf(status.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }
}
