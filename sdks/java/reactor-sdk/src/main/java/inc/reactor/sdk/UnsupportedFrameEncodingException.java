package inc.reactor.sdk;

import org.jspecify.annotations.Nullable;

/**
 * A step result carried a frame in an encoding this client cannot decode.
 *
 * <p>Code {@code UNSUPPORTED_FRAME_ENCODING}.
 */
public final class UnsupportedFrameEncodingException extends ReactorException {

    private static final long serialVersionUID = 1L;

    UnsupportedFrameEncodingException(
            String message, @Nullable Integer status, @Nullable String operation, @Nullable Long retryAfterMs) {
        super(ErrorCode.UNSUPPORTED_FRAME_ENCODING.code(), message, status, operation, retryAfterMs);
    }
}
