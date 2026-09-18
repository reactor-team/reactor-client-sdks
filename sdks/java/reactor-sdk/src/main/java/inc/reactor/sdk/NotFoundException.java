package inc.reactor.sdk;

import org.jspecify.annotations.Nullable;

/**
 * No such model, session or upload (HTTP 404).
 *
 * <p>Code {@code NOT_FOUND}.
 */
public final class NotFoundException extends ReactorException {

    private static final long serialVersionUID = 1L;

    NotFoundException(
            String message, @Nullable Integer status, @Nullable String operation, @Nullable Long retryAfterMs) {
        super(ErrorCode.NOT_FOUND.code(), message, status, operation, retryAfterMs);
    }
}
