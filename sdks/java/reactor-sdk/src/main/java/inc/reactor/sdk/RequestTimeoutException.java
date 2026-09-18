package inc.reactor.sdk;

import org.jspecify.annotations.Nullable;

/**
 * Sent, and nothing came back in time.
 *
 * <p>Code {@code REQUEST_TIMEOUT}.
 */
public final class RequestTimeoutException extends ReactorException {

    private static final long serialVersionUID = 1L;

    RequestTimeoutException(
            String message, @Nullable Integer status, @Nullable String operation, @Nullable Long retryAfterMs) {
        super(ErrorCode.REQUEST_TIMEOUT.code(), message, status, operation, retryAfterMs);
    }
}
