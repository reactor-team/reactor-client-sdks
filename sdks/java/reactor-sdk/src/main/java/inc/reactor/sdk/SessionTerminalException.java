package inc.reactor.sdk;

import org.jspecify.annotations.Nullable;

/**
 * The session reached a state it cannot leave. Start a new one.
 *
 * <p>Code {@code SESSION_TERMINAL}.
 */
public final class SessionTerminalException extends ReactorException {

    private static final long serialVersionUID = 1L;

    SessionTerminalException(
            String message, @Nullable Integer status, @Nullable String operation, @Nullable Long retryAfterMs) {
        super(ErrorCode.SESSION_TERMINAL.code(), message, status, operation, retryAfterMs);
    }
}
