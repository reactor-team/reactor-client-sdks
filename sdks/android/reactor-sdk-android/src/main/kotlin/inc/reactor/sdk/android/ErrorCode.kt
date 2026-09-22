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
 */
internal enum class ErrorCode(
    val wire: String,
    val recoverable: Boolean,
    val create: (String, String, Int?, String?, Long?, Throwable?) -> ReactorException,
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
        ): ReactorException {
            val factory = byWire[wire]?.create ?: ::UnknownReactorException
            return factory(wire, message, status, operation, retryAfterMs, cause)
        }
    }
}
