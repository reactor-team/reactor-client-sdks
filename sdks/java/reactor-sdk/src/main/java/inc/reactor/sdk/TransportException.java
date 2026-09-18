package inc.reactor.sdk;

import org.jspecify.annotations.Nullable;

/**
 * The media transport failed.
 *
 * <p>Code {@code TRANSPORT_ERROR}.
 */
public final class TransportException extends ReactorException {

    private static final long serialVersionUID = 1L;

    TransportException(
            String message, @Nullable Integer status, @Nullable String operation, @Nullable Long retryAfterMs) {
        super(ErrorCode.TRANSPORT_ERROR.code(), message, status, operation, retryAfterMs);
    }
}
