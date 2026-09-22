package inc.reactor.sdk.android

/**
 * A failure, in terms a caller can act on.
 *
 * The same object an operation throws is the one an error event delivers — `Reactor.errors` and
 * a failed `suspend` call give you this, so there is one thing to catch and one thing to match
 * on. Matching on message text is what every SDK above this boundary was reduced to before the
 * codes existed.
 *
 * [recoverable] is **derived from [code]**, never passed in per call site. Two SDKs disagreeing
 * about whether a timeout is worth retrying is the failure that produced two names for one
 * condition — `connect()` reporting `CONNECTION_FAILED` and recoverable on one channel and
 * `UNAUTHORIZED` and not on the other.
 */
public open class ReactorException internal constructor(
    /** One of [ErrorCode]'s wire values, or a code the platform sent that this SDK does not know. */
    public val code: String,
    message: String,
    /** The HTTP status, when the failure came from one. */
    public val status: Int? = null,
    /** Which call failed — `"connect"`, `"send_command"`. */
    public val operation: String? = null,
    /** How long to wait before retrying, when the platform said. */
    public val retryAfterMs: Long? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /**
     * Whether the same call could succeed later, unchanged.
     *
     * True is about the moment rather than the request: the connection went, the network did not
     * answer, the platform was busy. Everything else describes the request, and repeating it
     * unchanged fails the same way.
     */
    public val recoverable: Boolean
        get() = ErrorCode.isRecoverable(code)

    override fun toString(): String =
        buildString {
            append(this@ReactorException::class.simpleName)
            append('(')
            append(code)
            status?.let { append(", status=").append(it) }
            operation?.let { append(", operation=").append(it) }
            append("): ")
            append(message)
        }
}

/** The client is not in a state where this call means anything — publishing before connecting. */
public class InvalidStateException internal constructor(
    code: String,
    message: String,
    status: Int? = null,
    operation: String? = null,
    retryAfterMs: Long? = null,
    cause: Throwable? = null,
) : ReactorException(code, message, status, operation, retryAfterMs, cause)

/** The session is gone. Recoverable: reconnect and try again. */
public class DisconnectedException internal constructor(
    code: String,
    message: String,
    status: Int? = null,
    operation: String? = null,
    retryAfterMs: Long? = null,
    cause: Throwable? = null,
) : ReactorException(code, message, status, operation, retryAfterMs, cause)

/** The network did not answer. */
public class NetworkException internal constructor(
    code: String,
    message: String,
    status: Int? = null,
    operation: String? = null,
    retryAfterMs: Long? = null,
    cause: Throwable? = null,
) : ReactorException(code, message, status, operation, retryAfterMs, cause)

/** The reply did not arrive in time. */
public class RequestTimeoutException internal constructor(
    code: String,
    message: String,
    status: Int? = null,
    operation: String? = null,
    retryAfterMs: Long? = null,
    cause: Throwable? = null,
) : ReactorException(code, message, status, operation, retryAfterMs, cause)

/** The WebRTC transport failed. */
public class TransportException internal constructor(
    code: String,
    message: String,
    status: Int? = null,
    operation: String? = null,
    retryAfterMs: Long? = null,
    cause: Throwable? = null,
) : ReactorException(code, message, status, operation, retryAfterMs, cause)

/** The key or token was rejected. Not recoverable: the same credentials fail the same way. */
public class UnauthorizedException internal constructor(
    code: String,
    message: String,
    status: Int? = null,
    operation: String? = null,
    retryAfterMs: Long? = null,
    cause: Throwable? = null,
) : ReactorException(code, message, status, operation, retryAfterMs, cause)

/** No such model, session or clip. */
public class NotFoundException internal constructor(
    code: String,
    message: String,
    status: Int? = null,
    operation: String? = null,
    retryAfterMs: Long? = null,
    cause: Throwable? = null,
) : ReactorException(code, message, status, operation, retryAfterMs, cause)

/** The request conflicts with the session's current state. */
public class ConflictException internal constructor(
    code: String,
    message: String,
    status: Int? = null,
    operation: String? = null,
    retryAfterMs: Long? = null,
    cause: Throwable? = null,
) : ReactorException(code, message, status, operation, retryAfterMs, cause)

/** Too many requests. Recoverable, and [retryAfterMs] says when. */
public class RateLimitedException internal constructor(
    code: String,
    message: String,
    status: Int? = null,
    operation: String? = null,
    retryAfterMs: Long? = null,
    cause: Throwable? = null,
) : ReactorException(code, message, status, operation, retryAfterMs, cause)

/** The request was malformed. */
public class BadRequestException internal constructor(
    code: String,
    message: String,
    status: Int? = null,
    operation: String? = null,
    retryAfterMs: Long? = null,
    cause: Throwable? = null,
) : ReactorException(code, message, status, operation, retryAfterMs, cause)

/** The platform failed. Recoverable: a 5xx is about the moment. */
public class ServerException internal constructor(
    code: String,
    message: String,
    status: Int? = null,
    operation: String? = null,
    retryAfterMs: Long? = null,
    cause: Throwable? = null,
) : ReactorException(code, message, status, operation, retryAfterMs, cause)

/** This SDK and the platform disagree about the protocol. */
public class VersionMismatchException internal constructor(
    code: String,
    message: String,
    status: Int? = null,
    operation: String? = null,
    retryAfterMs: Long? = null,
    cause: Throwable? = null,
) : ReactorException(code, message, status, operation, retryAfterMs, cause)

/**
 * A payload did not parse.
 *
 * Distinct from an absent payload, which is an answer. A successful completion whose result will
 * not decode is this, never an empty object: substituting `{}` makes a schema request answer with
 * a schema declaring nothing, which no caller can tell from a model that declares nothing.
 */
public class DecodeFailedException internal constructor(
    code: String,
    message: String,
    status: Int? = null,
    operation: String? = null,
    retryAfterMs: Long? = null,
    cause: Throwable? = null,
) : ReactorException(code, message, status, operation, retryAfterMs, cause)

/** The session ended and will not come back. */
public class SessionTerminalException internal constructor(
    code: String,
    message: String,
    status: Int? = null,
    operation: String? = null,
    retryAfterMs: Long? = null,
    cause: Throwable? = null,
) : ReactorException(code, message, status, operation, retryAfterMs, cause)

/** The message exceeded the channel's limit. */
public class MessageTooLargeException internal constructor(
    code: String,
    message: String,
    status: Int? = null,
    operation: String? = null,
    retryAfterMs: Long? = null,
    cause: Throwable? = null,
) : ReactorException(code, message, status, operation, retryAfterMs, cause)

/** The operation was cancelled or the client went away before it finished. */
public class AbortedException internal constructor(
    code: String,
    message: String,
    status: Int? = null,
    operation: String? = null,
    retryAfterMs: Long? = null,
    cause: Throwable? = null,
) : ReactorException(code, message, status, operation, retryAfterMs, cause)

/** Recording is not enabled for this model or session. */
public class RecorderDisabledException internal constructor(
    code: String,
    message: String,
    status: Int? = null,
    operation: String? = null,
    retryAfterMs: Long? = null,
    cause: Throwable? = null,
) : ReactorException(code, message, status, operation, retryAfterMs, cause)

/**
 * A code this SDK does not know.
 *
 * The platform can reject a control request, command or recording with a code of its own, so the
 * set is open-ended. An unrecognised code is an error you cannot classify — never a parse
 * failure, and never silently reshaped into one of the known ones.
 */
public class UnknownReactorException internal constructor(
    code: String,
    message: String,
    status: Int? = null,
    operation: String? = null,
    retryAfterMs: Long? = null,
    cause: Throwable? = null,
) : ReactorException(code, message, status, operation, retryAfterMs, cause)
