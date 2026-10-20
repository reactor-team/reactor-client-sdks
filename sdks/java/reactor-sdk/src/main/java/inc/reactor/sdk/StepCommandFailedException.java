package inc.reactor.sdk;

import org.jspecify.annotations.Nullable;

/**
 * The handler of one of the step's state keys or commands failed. The calls before it stay applied,
 * the ones after it were skipped, and the step did not run. {@link #entry()} names the call that
 * failed ({@code "state.<key>"} or {@code "commands[<i>]"}) and {@link #commandCode()} the code its
 * handler raised, when the runtime reported them.
 *
 * <p>Code {@code step_command_failed}: lowercase, as the runtime names the outcome.
 */
public final class StepCommandFailedException extends ReactorException {

    private static final long serialVersionUID = 1L;

    StepCommandFailedException(
            String message, @Nullable Integer status, @Nullable String operation, @Nullable Long retryAfterMs) {
        super(ErrorCode.STEP_COMMAND_FAILED.code(), message, status, operation, retryAfterMs);
    }
}
