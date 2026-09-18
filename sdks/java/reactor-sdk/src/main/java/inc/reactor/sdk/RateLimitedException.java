package inc.reactor.sdk;

import org.jspecify.annotations.Nullable;

/**
 * Too many requests (HTTP 429). {@link #retryAfterMs()} carries the platform's own hint when it
 * sent one.
 *
 * <p>Code {@code RATE_LIMITED}.
 */
public final class RateLimitedException extends ReactorException {

    private static final long serialVersionUID = 1L;

    RateLimitedException(
            String message, @Nullable Integer status, @Nullable String operation, @Nullable Long retryAfterMs) {
        super(ErrorCode.RATE_LIMITED.code(), message, status, operation, retryAfterMs);
    }
}
