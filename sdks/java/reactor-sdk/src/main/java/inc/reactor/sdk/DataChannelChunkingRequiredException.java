package inc.reactor.sdk;

import org.jspecify.annotations.Nullable;

/**
 * A stepped session connected over a data channel that did not negotiate chunking: this client or
 * the runtime does not support it. A step's inputs and result each ride one data-channel message,
 * and without chunking a single camera frame outgrows it.
 *
 * <p>Code {@code DATA_CHANNEL_CHUNKING_REQUIRED}.
 */
public final class DataChannelChunkingRequiredException extends ReactorException {

    private static final long serialVersionUID = 1L;

    DataChannelChunkingRequiredException(
            String message, @Nullable Integer status, @Nullable String operation, @Nullable Long retryAfterMs) {
        super(ErrorCode.DATA_CHANNEL_CHUNKING_REQUIRED.code(), message, status, operation, retryAfterMs);
    }
}
