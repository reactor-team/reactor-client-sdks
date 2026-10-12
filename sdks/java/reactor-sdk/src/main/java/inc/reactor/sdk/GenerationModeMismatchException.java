package inc.reactor.sdk;

import org.jspecify.annotations.Nullable;

/**
 * The runtime did not start the session in the requested generation mode — most often a runtime
 * without stepped generation, which ignores the request rather than refusing it.
 *
 * <p>Code {@code GENERATION_MODE_MISMATCH}.
 */
public final class GenerationModeMismatchException extends ReactorException {

    private static final long serialVersionUID = 1L;

    GenerationModeMismatchException(
            String message, @Nullable Integer status, @Nullable String operation, @Nullable Long retryAfterMs) {
        super(ErrorCode.GENERATION_MODE_MISMATCH.code(), message, status, operation, retryAfterMs);
    }
}
