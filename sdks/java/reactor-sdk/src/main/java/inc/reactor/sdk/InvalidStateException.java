package inc.reactor.sdk;

import org.jspecify.annotations.Nullable;

/**
 * The call is not allowed from the state the client is in — most often one needing a live
 * session, made before connecting or after {@code ready} was lost.
 *
 * <p>Code {@code INVALID_STATE}.
 */
public final class InvalidStateException extends ReactorException {

    private static final long serialVersionUID = 1L;

    InvalidStateException(
            String message, @Nullable Integer status, @Nullable String operation, @Nullable Long retryAfterMs) {
        super(ErrorCode.INVALID_STATE.code(), message, status, operation, retryAfterMs);
    }
}
