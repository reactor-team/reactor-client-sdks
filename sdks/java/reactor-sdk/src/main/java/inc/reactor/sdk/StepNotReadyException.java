package inc.reactor.sdk;

import org.jspecify.annotations.Nullable;

/**
 * The session cannot run a step yet: it is not in a running state that can take one. The step did
 * not run and nothing it carried was applied.
 *
 * <p>Code {@code step_not_ready}: lowercase, as the runtime names the outcome.
 */
public final class StepNotReadyException extends ReactorException {

    private static final long serialVersionUID = 1L;

    StepNotReadyException(
            String message, @Nullable Integer status, @Nullable String operation, @Nullable Long retryAfterMs) {
        super(ErrorCode.STEP_NOT_READY.code(), message, status, operation, retryAfterMs);
    }
}
