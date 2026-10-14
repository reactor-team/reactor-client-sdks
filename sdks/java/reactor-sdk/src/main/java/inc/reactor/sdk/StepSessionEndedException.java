package inc.reactor.sdk;

import org.jspecify.annotations.Nullable;

/**
 * The session ended before the step's result could be sent, so whether the step ran is unknown.
 *
 * <p>Code {@code step_session_ended}: lowercase, as the runtime names the outcome.
 */
public final class StepSessionEndedException extends ReactorException {

    private static final long serialVersionUID = 1L;

    StepSessionEndedException(
            String message, @Nullable Integer status, @Nullable String operation, @Nullable Long retryAfterMs) {
        super(ErrorCode.STEP_SESSION_ENDED.code(), message, status, operation, retryAfterMs);
    }
}
