package inc.reactor.sdk.internal;

import inc.reactor.sdk.ErrorCode;
import inc.reactor.sdk.ReactorException;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Turning the FFI's error JSON into a typed exception.
 *
 * <p>The shape is the header's: {@code {code, message, recoverable, status?, operation?,
 * retry_after_ms?}}, plus {@code timestamp_ms} on the event channel. {@code recoverable} is read
 * past deliberately — it is derived from the code here, and in every other SDK, so that two of them
 * cannot disagree about whether a timeout is worth retrying.
 */
public final class ErrorPayloads {

    private ErrorPayloads() {}

    /**
     * Parses an error object.
     *
     * <p>Never throws for a payload it dislikes: an error that cannot be parsed is still an error,
     * and losing it would leave a caller with nothing at all. A malformed payload comes back as
     * {@link ErrorCode#DECODE_FAILED} carrying the text that could not be read.
     *
     * @param json the object, or {@code null}
     * @param fallbackOperation which call this came from, when the payload does not say
     * @return the exception, never {@code null}
     */
    public static ReactorException parse(@Nullable String json, @Nullable String fallbackOperation) {
        if (json == null || json.isBlank()) {
            return ReactorException.of(
                    ReactorException.INTERNAL_ERROR,
                    "the operation failed and the native layer reported no detail",
                    null,
                    fallbackOperation,
                    null);
        }
        Map<String, Object> fields;
        try {
            fields = Json.parseObject(json);
        } catch (JsonException e) {
            return ReactorException.of(
                    ErrorCode.DECODE_FAILED.code(),
                    "the error the native layer reported could not be parsed: " + e.getMessage() + ". The payload was: "
                            + json,
                    null,
                    fallbackOperation,
                    null);
        }
        String code = string(fields.get("code"));
        String message = string(fields.get("message"));
        return ReactorException.of(
                code == null || code.isBlank() ? ReactorException.INTERNAL_ERROR : code,
                message == null || message.isBlank() ? "the operation failed" : message,
                integer(fields.get("status")),
                string(fields.get("operation")) == null ? fallbackOperation : string(fields.get("operation")),
                longValue(fields.get("retry_after_ms")));
    }

    private static @Nullable String string(@Nullable Object value) {
        return value instanceof String text ? text : null;
    }

    /**
     * A field as an {@code int}, or nothing.
     *
     * <p>Never an exception. {@code Math.toIntExact} throws on a value that does not fit, and this
     * runs while the SDK is already decoding an error — so a status the wire made too large would
     * have replaced the failure the caller needs to see with an ArithmeticException that says
     * nothing about it. A status out of range is not a status; that is the honest answer and it
     * keeps the real error intact.
     */
    private static @Nullable Integer integer(@Nullable Object value) {
        Long asLong = longValue(value);
        if (asLong == null || asLong < Integer.MIN_VALUE || asLong > Integer.MAX_VALUE) {
            return null;
        }
        return asLong.intValue();
    }

    private static @Nullable Long longValue(@Nullable Object value) {
        if (value instanceof Long number) {
            return number;
        }
        if (value instanceof Double number) {
            return number.longValue();
        }
        return null;
    }
}
