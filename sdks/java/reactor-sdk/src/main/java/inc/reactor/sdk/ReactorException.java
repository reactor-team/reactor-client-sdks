package inc.reactor.sdk;

import org.jspecify.annotations.Nullable;

/**
 * A Reactor failure, in terms a caller can act on.
 *
 * <p>The same object is what a failed call throws and what an error event delivers. That is
 * deliberate: two vocabularies for one failure is how a 401 during connect ended up being
 * {@code UNAUTHORIZED} and not recoverable on one path, and {@code CONNECTION_FAILED} and
 * recoverable on the other.
 *
 * <p>Unchecked, because none of these is a condition a caller can be forced to handle usefully at
 * every call site. Catch the subclass you can do something about — {@link RateLimitedException},
 * {@link DisconnectedException} — and let the rest travel.
 *
 * <p>{@link #isRecoverable()} is derived from {@link #code()} and never stored per site, so two
 * SDKs cannot disagree about whether a timeout is worth retrying.
 */
public class ReactorException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * The code a failure carries when nothing classified it.
     *
     * <p>Not an {@link ErrorCode} constant: it is the base class's own code, the one left when
     * the platform named none, and it has no typed subclass to catch.
     */
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private final String code;
    private final @Nullable Integer status;
    private final @Nullable String operation;
    private final @Nullable Long retryAfterMs;

    /**
     * @param code the stable, matchable code — one of {@link ErrorCode}, or one the platform sent
     * @param message the human-readable message
     * @param status the HTTP status, when the failure came from one
     * @param operation which call failed, e.g. {@code "connect"}
     * @param retryAfterMs a backoff hint, when the platform sent one
     */
    protected ReactorException(
            String code,
            String message,
            @Nullable Integer status,
            @Nullable String operation,
            @Nullable Long retryAfterMs) {
        super(message);
        this.code = code;
        this.status = status;
        this.operation = operation;
        this.retryAfterMs = retryAfterMs;
    }

    /**
     * The typed exception for a code.
     *
     * <p>A code this SDK knows becomes its own class, so a caller can catch the one thing they can
     * act on. A code it does not know becomes a plain {@code ReactorException} carrying that code
     * unchanged — the platform's set is open-ended, and an unrecognised code is a failure that
     * could not be classified rather than a payload that failed to parse.
     *
     * @param code the stable code
     * @param message the human-readable message
     * @param status the HTTP status, when the failure came from one
     * @param operation which call failed
     * @param retryAfterMs a backoff hint, when the platform sent one
     * @return the exception for that code
     */
    public static ReactorException of(
            String code,
            String message,
            @Nullable Integer status,
            @Nullable String operation,
            @Nullable Long retryAfterMs) {
        return ErrorCode.of(code)
                .map(known -> known.create(message, status, operation, retryAfterMs))
                .orElseGet(() -> new ReactorException(code, message, status, operation, retryAfterMs));
    }

    /**
     * The stable code for this failure.
     *
     * <p>Usually one of {@link ErrorCode}'s, but the platform's set is open-ended: a rejected
     * control request, command or recording may carry a code this SDK has never heard of. An
     * unrecognised code is an error that could not be classified — never a parse failure.
     *
     * @return the code, never empty
     */
    public String code() {
        return code;
    }

    /**
     * Whether the same call could succeed later.
     *
     * <p>True is about the moment — a timeout, a 5xx, a transport that dropped. False is about the
     * request, and repeating it unchanged fails the same way. A code this SDK does not recognise is
     * not recoverable, which is the safe direction: never promise a retry will help.
     *
     * @return whether retrying is worth anything
     */
    public boolean isRecoverable() {
        return ErrorCode.isRecoverable(code);
    }

    /**
     * @return the HTTP status behind this failure, or {@code null} when it did not come from one
     */
    public @Nullable Integer status() {
        return status;
    }

    /**
     * @return which call failed, or {@code null} when the failure names none
     */
    public @Nullable String operation() {
        return operation;
    }

    /**
     * @return how long to wait before retrying, or {@code null} when the platform sent no hint
     */
    public @Nullable Long retryAfterMs() {
        return retryAfterMs;
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder(getClass().getName())
                .append(": [")
                .append(code)
                .append("] ")
                .append(getMessage());
        if (operation != null) {
            out.append(" (operation: ").append(operation).append(')');
        }
        if (status != null) {
            out.append(" (HTTP ").append(status).append(')');
        }
        return out.toString();
    }
}
