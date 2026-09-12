package inc.reactor.sdk.internal

import inc.reactor.sdk.AbortedError
import inc.reactor.sdk.DecodeFailedError
import inc.reactor.sdk.ErrorDetails
import inc.reactor.sdk.ReactorError
import inc.reactor.sdk.reactorError
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

internal fun decodeError(payload: JsonElement): ReactorError =
    try {
        decodeErrorObject(payload)
    } catch (failure: Exception) {
        DecodeFailedError(ErrorDetails("DECODE_FAILED", "Invalid native error: ${failure.message}"))
    }

private fun decodeErrorObject(payload: JsonElement): ReactorError {
    val value = payload as? JsonObject ?: error("Error payload must be an object")

    fun string(
        key: String,
        required: Boolean = false,
    ): String? {
        val field = value[key]
        if (field == null || field == JsonNull) {
            require(!required) { "Missing $key" }
            return null
        }
        require(field is JsonPrimitive && field.isString) { "$key must be a string" }
        return field.content
    }

    fun number(key: String): Double? {
        val field = value[key] ?: return null
        if (field == JsonNull) return null
        require(field is JsonPrimitive && !field.isString) { "$key must be numeric" }
        return requireNotNull(field.doubleOrNull?.takeIf { it.isFinite() }) { "Invalid $key" }
    }
    val status = number("status")
    require(status == null || (status >= 0 && status <= 65535 && status % 1 == 0.0)) { "Invalid status" }
    return reactorError(
        ErrorDetails(
            code = requireNotNull(string("code", true)).also { require(it.isNotEmpty()) },
            message = requireNotNull(string("message", true)),
            status = status?.toInt(),
            operation = string("operation"),
            retryAfterMillis = number("retry_after_ms"),
            timestampMillis = number("timestamp_ms"),
        ),
    )
}

/** Manages awaiters only. Native handle leases and callback tickets have separate lifetimes. */
internal class CompletionRegistry : AutoCloseable {
    private val lock = Any()
    private var nextId = 1L
    private var closed = false
    private val entries = mutableMapOf<Long, Entry>()

    private interface Entry {
        fun prepare(
            result: ByteArray?,
            error: ByteArray?,
        ): () -> Unit

        fun abort()
    }

    private class Pending<T>(
        val continuation: CancellableContinuation<T>,
        val decode: (JsonElement?) -> T,
    ) : Entry {
        override fun prepare(
            result: ByteArray?,
            error: ByteArray?,
        ): () -> Unit {
            val outcome =
                runCatching {
                    if (error != null) {
                        val parsed =
                            try {
                                decodeError(parse(error))
                            } catch (failure: Exception) {
                                throw DecodeFailedError(ErrorDetails("DECODE_FAILED", "Invalid native error: ${failure.message}"))
                            }
                        throw parsed
                    }
                    try {
                        decode(result?.let(::parse))
                    } catch (failure: Exception) {
                        throw DecodeFailedError(ErrorDetails("DECODE_FAILED", "Invalid native result: ${failure.message}"))
                    }
                }
            return { continuation.resumeWith(outcome) }
        }

        override fun abort() {
            continuation.resumeWith(
                Result.failure(
                    AbortedError(
                        ErrorDetails(
                            "ABORTED",
                            "Client closed; native work may still finish, including writing a download file",
                        ),
                    ),
                ),
            )
        }
    }

    val pendingCount: Int get() = synchronized(lock) { entries.size }

    suspend fun <T> await(
        decode: (JsonElement?) -> T,
        start: (Long) -> Unit,
    ): T =
        suspendCancellableCoroutine { continuation ->
            val entry = Pending(continuation, decode)
            val id =
                synchronized(lock) {
                    if (closed) {
                        null
                    } else {
                        check(nextId != Long.MAX_VALUE) { "Operation identifiers exhausted" }
                        nextId++.also { entries[it] = entry }
                    }
                }
            if (id == null) {
                entry.abort()
            } else {
                continuation.invokeOnCancellation { synchronized(lock) { entries.remove(id) } }
                // The caller supplies a native handle lease around start. Cancellation
                // removes the awaiter, but never claims to cancel native work.
                try {
                    start(id)
                } catch (failure: Throwable) {
                    val removed = synchronized(lock) { entries.remove(id) }
                    if (removed != null) continuation.resumeWith(Result.failure(failure))
                }
            }
        }

    fun complete(
        id: Long,
        result: ByteArray?,
        error: ByteArray?,
    ) {
        val entry = synchronized(lock) { entries[id] } ?: return
        // Decode before claiming completion. Cancellation/close may win during decode.
        val settle = entry.prepare(result, error)
        val claimed = synchronized(lock) { entries.remove(id) === entry }
        if (claimed) settle()
    }

    override fun close() {
        val pending =
            synchronized(lock) {
                closed = true
                entries.values.toList().also { entries.clear() }
            }
        pending.forEach { it.abort() }
    }
}

private fun parse(bytes: ByteArray): JsonElement = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true))
