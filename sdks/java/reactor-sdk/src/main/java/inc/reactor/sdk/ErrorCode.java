package inc.reactor.sdk;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * The error codes this platform reports, and the exception each one raises.
 *
 * <p>One flat list, shared with {@code crates/reactor-core/src/error.rs} and with every other
 * Reactor SDK, so a caller who learns a code in one language has learned it in all of them.
 * {@code scripts/check-error-codes-parity.py} compares this list against the core's on every CI
 * run — the copies have drifted before, and an SDK quietly falling back to its generic base error
 * for a code the platform genuinely reports is the kind of gap nothing else notices.
 *
 * <p>Unlike the C++ and Swift SDKs, which keep the code strings and the class list as two separate
 * hand-copies, each entry here names both at once. The second half needs no script: a constant
 * naming a class that does not exist does not compile.
 *
 * <p>The platform's set is open-ended. A rejected control request, command or recording may carry
 * a code absent from this enum, and that is an error which could not be classified — never a parse
 * failure.
 */
public enum ErrorCode {

    /** @see InvalidStateException */
    INVALID_STATE("INVALID_STATE", false, InvalidStateException::new),
    /** @see DisconnectedException */
    DISCONNECTED("DISCONNECTED", true, DisconnectedException::new),
    /** @see NetworkException */
    NETWORK_ERROR("NETWORK_ERROR", true, NetworkException::new),
    /** @see RequestTimeoutException */
    REQUEST_TIMEOUT("REQUEST_TIMEOUT", true, RequestTimeoutException::new),
    /** @see TransportException */
    TRANSPORT_ERROR("TRANSPORT_ERROR", true, TransportException::new),
    /** @see UnauthorizedException */
    UNAUTHORIZED("UNAUTHORIZED", false, UnauthorizedException::new),
    /** @see NotFoundException */
    NOT_FOUND("NOT_FOUND", false, NotFoundException::new),
    /** @see ConflictException */
    CONFLICT("CONFLICT", false, ConflictException::new),
    /** @see RateLimitedException */
    RATE_LIMITED("RATE_LIMITED", true, RateLimitedException::new),
    /** @see BadRequestException */
    BAD_REQUEST("BAD_REQUEST", false, BadRequestException::new),
    /** @see ServerException */
    SERVER_ERROR("SERVER_ERROR", true, ServerException::new),
    /** @see VersionMismatchException */
    VERSION_MISMATCH("VERSION_MISMATCH", false, VersionMismatchException::new),
    /** @see DecodeFailedException */
    DECODE_FAILED("DECODE_FAILED", false, DecodeFailedException::new),
    /** @see SessionTerminalException */
    SESSION_TERMINAL("SESSION_TERMINAL", false, SessionTerminalException::new),
    /** @see MessageTooLargeException */
    MESSAGE_TOO_LARGE("MESSAGE_TOO_LARGE", false, MessageTooLargeException::new),
    /** @see AbortedException */
    ABORTED("ABORTED", false, AbortedException::new),
    /** @see RecorderDisabledException */
    RECORDER_DISABLED("RECORDER_DISABLED", false, RecorderDisabledException::new);

    /** Builds the exception for one code. */
    @FunctionalInterface
    private interface Factory {
        ReactorException create(
                String message, @Nullable Integer status, @Nullable String operation, @Nullable Long retryAfterMs);
    }

    private static final Map<String, ErrorCode> BY_CODE =
            Stream.of(values()).collect(Collectors.toUnmodifiableMap(ErrorCode::code, Function.identity()));

    private final String code;
    private final boolean recoverable;
    private final Factory factory;

    ErrorCode(String code, boolean recoverable, Factory factory) {
        this.code = code;
        this.recoverable = recoverable;
        this.factory = factory;
    }

    /**
     * @return the code as it travels on the wire
     */
    public String code() {
        return code;
    }

    /**
     * Whether a call failing with this code could succeed later.
     *
     * <p>True for the six that describe the moment — the connection went, the network did not
     * answer, the platform was busy or broken, the reply did not arrive in time. Everything else
     * describes the request, and repeating it unchanged fails the same way.
     *
     * @return whether retrying is worth anything
     */
    public boolean isRecoverable() {
        return recoverable;
    }

    /**
     * The code with this name.
     *
     * @param code the wire value
     * @return the matching constant, or empty when the platform sent one this SDK does not know
     */
    public static Optional<ErrorCode> of(String code) {
        return Optional.ofNullable(BY_CODE.get(code));
    }

    /**
     * Whether a code is recoverable, including ones this SDK does not know.
     *
     * @param code the wire value
     * @return whether retrying is worth anything; {@code false} for an unrecognised code, which is
     *     the safe direction — never promise a retry will help
     */
    public static boolean isRecoverable(String code) {
        return of(code).map(ErrorCode::isRecoverable).orElse(false);
    }

    ReactorException create(
            String message, @Nullable Integer status, @Nullable String operation, @Nullable Long retryAfterMs) {
        return factory.create(message, status, operation, retryAfterMs);
    }
}
