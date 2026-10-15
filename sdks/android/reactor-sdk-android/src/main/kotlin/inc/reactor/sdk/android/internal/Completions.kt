package inc.reactor.sdk.android.internal

import inc.reactor.sdk.android.ErrorCode
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * One async FFI operation, bridged to a `suspend` function.
 *
 * Three rules, each of which the boundary punishes differently when broken.
 *
 * **Decode before claiming the promise.** A future settles once. If settling marks the operation
 * done and *then* converts the payload, a field of the wrong type throws where nothing can be
 * settled any more — the fallback meant to fail the call finds it already claimed, and the caller
 * gets a hang instead of the typed error this SDK documents. So [settle] converts first and
 * completes second.
 *
 * **A successful completion whose payload will not parse is a decode failure, not `{}`.**
 * Substituting an empty object makes a schema request answer with a schema declaring nothing,
 * which no caller can tell from a model that declares nothing. An *absent* payload — the ABI
 * sends null for a void operation — is a different answer, and does mean "nothing to report".
 *
 * **The ticket is independent of the client handle.** `reactor_fetch_jwt` takes no handle at all,
 * and `reactor_download_clip` is documented as outliving the one it was given, so a completion
 * can arrive after the client is gone. The registry therefore outlives any single client, and
 * [abandonAll] settles waiters rather than dropping them — a caller left holding an unresolved
 * promise waits for the life of the process.
 */
internal object Completions {
    private val nextTicket = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, Pending<*>>()

    private class Pending<T>(
        val operation: String,
        val decode: (String?) -> T,
        val deferred: CompletableDeferred<T>,
    )

    /**
     * Settle a completion from the native trampoline.
     *
     * `@JvmStatic` because `client.cpp` resolves it with `GetStaticMethodID`: a Kotlin `object`'s
     * members are instance methods on INSTANCE by default, and the bridge would have to carry a
     * reference to that instance for no reason. The ticket is all the native side holds.
     */
    @JvmStatic
    fun settleFromNative(
        ticket: Long,
        ok: Boolean,
        resultJson: String?,
        errorJson: String?,
    ) {
        settle(ticket, ok, resultJson, errorJson)
    }

    /** How many completions are outstanding. Zero at rest; the endurance suite watches it. */
    val pendingCount: Int
        get() = pending.size

    /**
     * Register a completion and hand its ticket to [start].
     *
     * [decode] runs on the native completion thread, before the promise is claimed. It receives
     * the raw `result_json`, which is null when the operation has nothing to report.
     */
    suspend fun <T> await(
        operation: String,
        decode: (String?) -> T,
        start: (ticket: Long) -> Unit,
    ): T {
        val ticket = nextTicket.getAndIncrement()
        val deferred = CompletableDeferred<T>()
        pending[ticket] = Pending(operation, decode, deferred)
        try {
            start(ticket)
        } catch (t: Throwable) {
            // The operation never started, so no completion will ever arrive for this ticket.
            pending.remove(ticket)
            throw t
        }
        return try {
            deferred.await()
        } finally {
            // On cancellation the ticket is dropped here, but the *native* operation keeps
            // running — nothing in this ABI can cancel one. A late completion for a ticket that
            // is gone is dropped by settle(), which is the correct outcome and not a leak: the
            // native side owns its own resources.
            pending.remove(ticket)
        }
    }

    /**
     * Settle a completion. Called from the native completion thread.
     *
     * Returns silently for an unknown ticket, which is the ordinary case after cancellation.
     */
    fun settle(
        ticket: Long,
        ok: Boolean,
        resultJson: String?,
        errorJson: String?,
    ) {
        @Suppress("UNCHECKED_CAST")
        val entry = pending.remove(ticket) as? Pending<Any?> ?: return

        if (!ok) {
            entry.deferred.completeExceptionally(
                ErrorPayloads.toException(errorJson, fallbackOperation = entry.operation),
            )
            return
        }

        // Decode first. Claiming the promise and then converting is the shape that produces a
        // hang rather than an error.
        val decoded =
            try {
                entry.decode(resultJson)
            } catch (e: Throwable) {
                entry.deferred.completeExceptionally(decodeFailure(entry.operation, resultJson, e))
                return
            }
        entry.deferred.complete(decoded)
    }

    /**
     * Settle every outstanding completion, because the client is going away.
     *
     * Says what actually happened: the native operation may well still be running — a download
     * whose client was destroyed is still downloading — and telling the caller the file may yet
     * arrive is worth more than "aborted".
     */
    fun abandonAll(reason: String) {
        val snapshot = pending.keys.toList()
        for (ticket in snapshot) {
            @Suppress("UNCHECKED_CAST")
            val entry = pending.remove(ticket) as? Pending<Any?> ?: continue
            entry.deferred.completeExceptionally(
                ErrorCode.toException(
                    wire = "ABORTED",
                    message = reason,
                    operation = entry.operation,
                ),
            )
        }
    }

    private fun decodeFailure(
        operation: String,
        payload: String?,
        cause: Throwable,
    ) = ErrorCode.toException(
        wire = "DECODE_FAILED",
        message = "The $operation completion succeeded but its payload did not decode: $payload",
        operation = operation,
        cause = cause,
    )

    /**
     * A duration a caller gave us, as milliseconds the ABI will accept.
     *
     * Numbers cross this boundary as whatever the host had, and a NaN or an infinity turned into
     * a duration panics *inside a detached native task* — which drops the completion instead of
     * firing it, leaving the binding waiting for a callback that can no longer come. Checked here
     * so the refusal is an ordinary exception on the calling thread.
     *
     * Negative and infinite both mean "no bound"; a NaN is a caller bug; a finite value too large
     * for the ABI saturates rather than wrapping.
     */
    fun timeoutMillis(seconds: Double?): Long {
        if (seconds == null) return 0L
        if (seconds.isNaN()) {
            throw ErrorCode.toException(
                wire = "BAD_REQUEST",
                message = "A timeout of NaN is not a duration",
            )
        }
        if (seconds.isInfinite() || seconds < 0.0) return 0L
        val millis = seconds * 1000.0
        return if (millis >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE else millis.toLong()
    }
}
