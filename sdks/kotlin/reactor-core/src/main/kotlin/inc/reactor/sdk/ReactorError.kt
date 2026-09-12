package inc.reactor.sdk

internal data class ErrorDetails(
    val code: String,
    val message: String,
    val status: Int? = null,
    val operation: String? = null,
    val retryAfterMillis: Double? = null,
    val timestampMillis: Double? = null,
)

open class ReactorError internal constructor(
    details: ErrorDetails,
) : Exception(details.message) {
    val code: String = details.code
    val status: Int? = details.status
    val operation: String? = details.operation
    val retryAfterMillis: Double? = details.retryAfterMillis
    val timestampMillis: Double? = details.timestampMillis
    val recoverable: Boolean =
        code in
            setOf(
                "DISCONNECTED",
                "NETWORK_ERROR",
                "REQUEST_TIMEOUT",
                "TRANSPORT_ERROR",
                "RATE_LIMITED",
                "SERVER_ERROR",
            )
}

class InvalidStateError internal constructor(
    details: ErrorDetails,
) : ReactorError(details)

class DisconnectedError internal constructor(
    details: ErrorDetails,
) : ReactorError(details)

class NetworkError internal constructor(
    details: ErrorDetails,
) : ReactorError(details)

class RequestTimeoutError internal constructor(
    details: ErrorDetails,
) : ReactorError(details)

class TransportError internal constructor(
    details: ErrorDetails,
) : ReactorError(details)

class UnauthorizedError internal constructor(
    details: ErrorDetails,
) : ReactorError(details)

class NotFoundError internal constructor(
    details: ErrorDetails,
) : ReactorError(details)

class ConflictError internal constructor(
    details: ErrorDetails,
) : ReactorError(details)

class RateLimitedError internal constructor(
    details: ErrorDetails,
) : ReactorError(details)

class BadRequestError internal constructor(
    details: ErrorDetails,
) : ReactorError(details)

class ServerError internal constructor(
    details: ErrorDetails,
) : ReactorError(details)

class VersionMismatchError internal constructor(
    details: ErrorDetails,
) : ReactorError(details)

class DecodeFailedError internal constructor(
    details: ErrorDetails,
) : ReactorError(details)

class SessionTerminalError internal constructor(
    details: ErrorDetails,
) : ReactorError(details)

class MessageTooLargeError internal constructor(
    details: ErrorDetails,
) : ReactorError(details)

class AbortedError internal constructor(
    details: ErrorDetails,
) : ReactorError(details)

class RecorderDisabledError internal constructor(
    details: ErrorDetails,
) : ReactorError(details)

internal fun reactorError(details: ErrorDetails): ReactorError =
    when (details.code) {
        "INVALID_STATE" -> InvalidStateError(details)
        "DISCONNECTED" -> DisconnectedError(details)
        "NETWORK_ERROR" -> NetworkError(details)
        "REQUEST_TIMEOUT" -> RequestTimeoutError(details)
        "TRANSPORT_ERROR" -> TransportError(details)
        "UNAUTHORIZED" -> UnauthorizedError(details)
        "NOT_FOUND" -> NotFoundError(details)
        "CONFLICT" -> ConflictError(details)
        "RATE_LIMITED" -> RateLimitedError(details)
        "BAD_REQUEST" -> BadRequestError(details)
        "SERVER_ERROR" -> ServerError(details)
        "VERSION_MISMATCH" -> VersionMismatchError(details)
        "DECODE_FAILED" -> DecodeFailedError(details)
        "SESSION_TERMINAL" -> SessionTerminalError(details)
        "MESSAGE_TOO_LARGE" -> MessageTooLargeError(details)
        "ABORTED" -> AbortedError(details)
        "RECORDER_DISABLED" -> RecorderDisabledError(details)
        else -> ReactorError(details)
    }
