package inc.reactor.sdk;

import org.jspecify.annotations.Nullable;

/**
 * A step ran, but its result does not fit in one data-channel message. Ask for less per step —
 * fewer frames, a smaller resolution.
 *
 * <p>Code {@code step_result_too_large}: lowercase because it is the runtime's own code, passed
 * through.
 */
public final class StepResultTooLargeException extends ReactorException {

    private static final long serialVersionUID = 1L;

    StepResultTooLargeException(
            String message, @Nullable Integer status, @Nullable String operation, @Nullable Long retryAfterMs) {
        super(ErrorCode.STEP_RESULT_TOO_LARGE.code(), message, status, operation, retryAfterMs);
    }
}
