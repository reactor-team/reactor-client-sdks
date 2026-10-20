package inc.reactor.sdk;

import org.jspecify.annotations.Nullable;

/**
 * The runtime refused a step — the request or the model turned it down. The runtime checks the
 * whole request before it applies any of it, so a refused step changed nothing.
 *
 * <p>Code {@code step_refused}: lowercase because it is the runtime's own code, passed through.
 */
public final class StepRefusedException extends ReactorException {

    private static final long serialVersionUID = 1L;

    StepRefusedException(
            String message, @Nullable Integer status, @Nullable String operation, @Nullable Long retryAfterMs) {
        super(ErrorCode.STEP_REFUSED.code(), message, status, operation, retryAfterMs);
    }
}
