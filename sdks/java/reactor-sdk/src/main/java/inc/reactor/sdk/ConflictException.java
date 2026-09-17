package inc.reactor.sdk;

import org.jspecify.annotations.Nullable;

/**
 * The session is in a state that does not allow this (HTTP 409) — usually one left orphaned by a
 * run that went away without disconnecting.
 *
 * <p>Code {@code CONFLICT}.
 */
public final class ConflictException extends ReactorException {

    private static final long serialVersionUID = 1L;

    ConflictException(
            String message, @Nullable Integer status, @Nullable String operation, @Nullable Long retryAfterMs) {
        super(ErrorCode.CONFLICT.code(), message, status, operation, retryAfterMs);
    }
}
