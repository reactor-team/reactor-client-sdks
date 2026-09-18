package inc.reactor.sdk;

import org.jspecify.annotations.Nullable;

/**
 * The token is missing, expired, or not scoped for this (HTTP 401 / 403).
 *
 * <p>Code {@code UNAUTHORIZED}.
 */
public final class UnauthorizedException extends ReactorException {

    private static final long serialVersionUID = 1L;

    UnauthorizedException(
            String message, @Nullable Integer status, @Nullable String operation, @Nullable Long retryAfterMs) {
        super(ErrorCode.UNAUTHORIZED.code(), message, status, operation, retryAfterMs);
    }
}
