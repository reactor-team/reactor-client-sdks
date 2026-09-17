package inc.reactor.sdk;

import org.jspecify.annotations.Nullable;

/**
 * This client and the platform disagree on the protocol (HTTP 426 / 501).
 *
 * <p>Code {@code VERSION_MISMATCH}.
 */
public final class VersionMismatchException extends ReactorException {

    private static final long serialVersionUID = 1L;

    VersionMismatchException(
            String message, @Nullable Integer status, @Nullable String operation, @Nullable Long retryAfterMs) {
        super(ErrorCode.VERSION_MISMATCH.code(), message, status, operation, retryAfterMs);
    }
}
