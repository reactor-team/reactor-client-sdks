package inc.reactor.sdk.android

/**
 * Every error code the core defines, with the exception it raises and whether it is worth
 * retrying.
 *
 * One declaration per code rather than two lists that can fall out of step: the constant names
 * its exception class in the same line, so the compiler enforces that half — a constant naming a
 * class that does not exist does not build. `scripts/check-error-codes-parity.py` enforces the
 * other half, that this set matches `crates/reactor-core/src/error.rs`.
 *
 * The recoverable ones are the ones where nothing about the *request* was wrong: the connection
 * went, the network did not answer, the platform was busy or slow. Everything else describes the
 * request, and repeating it unchanged fails the same way.
 *
 * **This column is a fallback, not the answer.** `recoverable` rides on every error payload the
 * core sends and `code_is_recoverable()` is the single place that decides it, so an exception
 * built from a payload uses what the payload said — see [ReactorException.recoverable]. These
 * values are for errors this SDK raises itself, which never crossed the FFI and have no payload
 * to read. Keeping them as the primary source made them a second copy of the core's
 * classification that nothing checks: `check-error-codes-parity.py` deliberately does not cover
 * recoverability, on the stated grounds that no binding keeps such a copy.
 */
internal enum class ErrorCode(
    val wire: String,
    val recoverable: Boolean,
    val create: (String, String, Int?, String?, Long?, Throwable?, Boolean?) -> ReactorException,
) {
    INVALID_STATE("INVALID_STATE", false, ::InvalidStateException),
    DISCONNECTED("DISCONNECTED", true, ::DisconnectedException),
    NETWORK_ERROR("NETWORK_ERROR", true, ::NetworkException),
    REQUEST_TIMEOUT("REQUEST_TIMEOUT", true, ::RequestTimeoutException),
    TRANSPORT_ERROR("TRANSPORT_ERROR", true, ::TransportException),
    UNAUTHORIZED("UNAUTHORIZED", false, ::UnauthorizedException),
    NOT_FOUND("NOT_FOUND", false, ::NotFoundException),
    CONFLICT("CONFLICT", false, ::ConflictException),
    RATE_LIMITED("RATE_LIMITED", true, ::RateLimitedException),
    BAD_REQUEST("BAD_REQUEST", false, ::BadRequestException),
    SERVER_ERROR("SERVER_ERROR", true, ::ServerException),
    VERSION_MISMATCH("VERSION_MISMATCH", false, ::VersionMismatchException),
    DECODE_FAILED("DECODE_FAILED", false, ::DecodeFailedException),
    SESSION_TERMINAL("SESSION_TERMINAL", false, ::SessionTerminalException),
    MESSAGE_TOO_LARGE("MESSAGE_TOO_LARGE", false, ::MessageTooLargeException),
    ABORTED("ABORTED", false, ::AbortedException),
    RECORDER_DISABLED("RECORDER_DISABLED", false, ::RecorderDisabledException),
    ;

    internal companion object {
        private val byWire: Map<String, ErrorCode> = entries.associateBy { it.wire }

        fun of(wire: String?): ErrorCode? = wire?.let { byWire[it] }

        /**
         * Whether a code is worth retrying.
         *
         * An unknown code is **not** recoverable. The platform's own codes are open-ended, and
         * guessing that an unrecognised failure will pass next time turns one failed call into a
         * retry loop against something that will never succeed.
         */
        fun isRecoverable(wire: String): Boolean = byWire[wire]?.recoverable ?: false

        /**
         * Build the typed exception for a code, falling back to [UnknownReactorException].
         *
         * The fallback keeps the original code rather than remapping it to `INTERNAL_ERROR`: a
         * caller logging `error.code` should see what the platform actually said.
         */
        fun toException(
            wire: String,
            message: String,
            status: Int? = null,
            operation: String? = null,
            retryAfterMs: Long? = null,
            cause: Throwable? = null,
            /**
             * What the payload said, when there was one.
             *
             * Null for an error this SDK raised itself — a refusal that never crossed the FFI
             * has no payload to read. See [ReactorException.recoverable].
             */
            wireRecoverable: Boolean? = null,
        ): ReactorException {
            val factory = byWire[wire]?.create ?: ::UnknownReactorException
            return factory(wire, message, status, operation, retryAfterMs, cause, wireRecoverable)
        }
    }
}
