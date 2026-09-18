package inc.reactor.sdk;

import org.jspecify.annotations.Nullable;

/**
 * A clip or recording request failed because the model's recorder is disabled or has crashed.
 *
 * <p>Code {@code RECORDER_DISABLED}.
 */
public final class RecorderDisabledException extends ReactorException {

    private static final long serialVersionUID = 1L;

    RecorderDisabledException(
            String message, @Nullable Integer status, @Nullable String operation, @Nullable Long retryAfterMs) {
        super(ErrorCode.RECORDER_DISABLED.code(), message, status, operation, retryAfterMs);
    }
}
