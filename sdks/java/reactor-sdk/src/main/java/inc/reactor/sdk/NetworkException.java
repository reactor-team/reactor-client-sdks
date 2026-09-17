package inc.reactor.sdk;

import org.jspecify.annotations.Nullable;

/**
 * The request never got a reply — DNS, TLS, a refused socket.
 *
 * <p>Code {@code NETWORK_ERROR}.
 */
public final class NetworkException extends ReactorException {

    private static final long serialVersionUID = 1L;

    NetworkException(
            String message, @Nullable Integer status, @Nullable String operation, @Nullable Long retryAfterMs) {
        super(ErrorCode.NETWORK_ERROR.code(), message, status, operation, retryAfterMs);
    }
}
