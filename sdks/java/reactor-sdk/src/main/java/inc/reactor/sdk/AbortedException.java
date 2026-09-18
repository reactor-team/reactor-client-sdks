package inc.reactor.sdk;

import org.jspecify.annotations.Nullable;

/**
 * The operation was abandoned before it finished.
 *
 * <p>Code {@code ABORTED}.
 */
public final class AbortedException extends ReactorException {

    private static final long serialVersionUID = 1L;

    AbortedException(
            String message, @Nullable Integer status, @Nullable String operation, @Nullable Long retryAfterMs) {
        super(ErrorCode.ABORTED.code(), message, status, operation, retryAfterMs);
    }
}
