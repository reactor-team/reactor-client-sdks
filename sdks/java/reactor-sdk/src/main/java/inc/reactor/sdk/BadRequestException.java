package inc.reactor.sdk;

import org.jspecify.annotations.Nullable;

/**
 * The request itself was wrong — a 4xx other than the others here, or an argument rejected
 * before it was sent.
 *
 * <p>Code {@code BAD_REQUEST}.
 */
public final class BadRequestException extends ReactorException {

    private static final long serialVersionUID = 1L;

    BadRequestException(
            String message, @Nullable Integer status, @Nullable String operation, @Nullable Long retryAfterMs) {
        super(ErrorCode.BAD_REQUEST.code(), message, status, operation, retryAfterMs);
    }
}
