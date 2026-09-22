package inc.reactor.sdk.android.internal

import inc.reactor.sdk.android.ErrorCode
import inc.reactor.sdk.android.ReactorException

/**
 * Turns the ABI's error JSON into a typed exception.
 *
 * The shape is the same on both channels — a failed operation's `error_json` and an `on_error`
 * event — which is what lets one object be both what a call throws and what an event delivers.
 */
internal object ErrorPayloads {
    /**
     * Parse an error payload.
     *
     * A payload that will not parse is still an error: the operation failed, and reporting a
     * decode problem *instead of* the failure would lose the thing the caller needs to know. So a
     * malformed payload becomes [ReactorException] with the code the platform sent if it is
     * legible, and `INTERNAL_ERROR` carrying the raw text if it is not — never a thrown
     * [JsonException] that replaces one failure with another.
     */
    fun toException(
        errorJson: String?,
        fallbackOperation: String? = null,
    ): ReactorException {
        if (errorJson.isNullOrBlank()) {
            return ErrorCode.toException(
                wire = "INTERNAL_ERROR",
                message = "The operation failed and the native layer reported no detail",
                operation = fallbackOperation,
            )
        }

        val fields =
            try {
                Json.parseObject(errorJson)
            } catch (e: JsonException) {
                return ErrorCode.toException(
                    wire = "INTERNAL_ERROR",
                    message = "Unparseable error payload from the native layer: $errorJson",
                    operation = fallbackOperation,
                    cause = e,
                )
            }

        val code =
            fields["code"] as? String
                ?: return ErrorCode.toException(
                    wire = "INTERNAL_ERROR",
                    message = "Error payload carried no code: $errorJson",
                    operation = fallbackOperation,
                )

        return ErrorCode.toException(
            wire = code,
            message = fields["message"] as? String ?: code,
            status = (fields["status"] as? Double)?.toInt(),
            operation = fields["operation"] as? String ?: fallbackOperation,
            retryAfterMs = (fields["retry_after_ms"] as? Double)?.toLong(),
        )
    }
}
