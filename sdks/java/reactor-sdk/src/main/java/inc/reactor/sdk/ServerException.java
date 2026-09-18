package inc.reactor.sdk;

import org.jspecify.annotations.Nullable;

/**
 * The platform failed, and the same request may work later (HTTP 5xx).
 *
 * <p>Code {@code SERVER_ERROR}.
 */
public final class ServerException extends ReactorException {

    private static final long serialVersionUID = 1L;

    ServerException(String message, @Nullable Integer status, @Nullable String operation, @Nullable Long retryAfterMs) {
        super(ErrorCode.SERVER_ERROR.code(), message, status, operation, retryAfterMs);
    }
}
