package inc.reactor.sdk;

import org.jspecify.annotations.Nullable;

/**
 * A reply arrived and could not be understood.
 *
 * <p>Also what this SDK raises for a payload it could not decode itself. A successful completion
 * whose result will not parse is a decode failure, not an empty object — substituting {@code {}}
 * would make a schema declaring nothing indistinguishable from a model declaring nothing.
 *
 * <p>Code {@code DECODE_FAILED}.
 */
public final class DecodeFailedException extends ReactorException {

    private static final long serialVersionUID = 1L;

    DecodeFailedException(
            String message, @Nullable Integer status, @Nullable String operation, @Nullable Long retryAfterMs) {
        super(ErrorCode.DECODE_FAILED.code(), message, status, operation, retryAfterMs);
    }
}
