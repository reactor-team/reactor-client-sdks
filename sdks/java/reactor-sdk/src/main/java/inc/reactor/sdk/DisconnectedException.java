package inc.reactor.sdk;

import org.jspecify.annotations.Nullable;

/**
 * The connection went away: dropped while a request was in flight, or lost after it had been
 * established.
 *
 * <p>Code {@code DISCONNECTED}.
 */
public final class DisconnectedException extends ReactorException {

    private static final long serialVersionUID = 1L;

    DisconnectedException(
            String message, @Nullable Integer status, @Nullable String operation, @Nullable Long retryAfterMs) {
        super(ErrorCode.DISCONNECTED.code(), message, status, operation, retryAfterMs);
    }
}
