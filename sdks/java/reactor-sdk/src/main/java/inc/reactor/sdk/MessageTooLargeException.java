package inc.reactor.sdk;

import org.jspecify.annotations.Nullable;

/**
 * The payload exceeds what the data channel accepts.
 *
 * <p>Code {@code MESSAGE_TOO_LARGE}.
 */
public final class MessageTooLargeException extends ReactorException {

    private static final long serialVersionUID = 1L;

    MessageTooLargeException(
            String message, @Nullable Integer status, @Nullable String operation, @Nullable Long retryAfterMs) {
        super(ErrorCode.MESSAGE_TOO_LARGE.code(), message, status, operation, retryAfterMs);
    }
}
