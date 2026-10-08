package inc.reactor.sdk.android

import inc.reactor.sdk.android.internal.ErrorPayloads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The error model: one code, one class, one answer about whether retrying is worth it. */
class ErrorsTest {
    @Test
    fun `every code maps to its own class`() {
        val classes =
            ErrorCode.entries.map {
                ErrorCode.toException(it.wire, "x")::class
            }
        assertEquals(
            "two codes share an exception class, so a caller cannot tell them apart by catch",
            ErrorCode.entries.size,
            classes.toSet().size,
        )
    }

    @Test
    fun `recoverability comes from the code and matches the core`() {
        // The core's own list — crates/reactor-core/src/error.rs, code_is_recoverable.
        val recoverable =
            setOf(
                "DISCONNECTED",
                "NETWORK_ERROR",
                "REQUEST_TIMEOUT",
                "TRANSPORT_ERROR",
                "RATE_LIMITED",
                "SERVER_ERROR",
            )
        for (code in ErrorCode.entries) {
            assertEquals(
                "${code.wire} disagrees with the core about recoverability",
                code.wire in recoverable,
                ErrorCode.toException(code.wire, "x").recoverable,
            )
        }
    }

    @Test
    fun `an unknown code is preserved and is not recoverable`() {
        val error = ErrorCode.toException("PROMPT_REJECTED", "the model said no")
        assertTrue(error is UnknownReactorException)
        assertEquals("PROMPT_REJECTED", error.code)
        assertFalse(
            "guessing that an unrecognised failure will pass next time turns one failure " +
                "into a retry loop",
            error.recoverable,
        )
    }

    @Test
    fun `a full payload keeps every field`() {
        val error =
            ErrorPayloads.toException(
                """{"code":"RATE_LIMITED","message":"slow down","status":429,
               "operation":"send_command","retry_after_ms":1500}""",
            )
        assertTrue(error is RateLimitedException)
        assertEquals("slow down", error.message)
        assertEquals(429, error.status)
        assertEquals("send_command", error.operation)
        assertEquals(1500L, error.retryAfterMs)
        assertTrue(error.recoverable)
    }

    @Test
    fun `optional fields are absent rather than zero`() {
        val error = ErrorPayloads.toException("""{"code":"CONFLICT","message":"nope"}""")
        assertNull("a missing status must not read as 0", error.status)
        assertNull(error.retryAfterMs)
    }

    /**
     * A malformed payload must not replace the failure with a parse error: the operation still
     * failed, and that is what the caller has to act on.
     */
    @Test
    fun `an unparseable payload still reports a failure`() {
        val error = ErrorPayloads.toException("{not json at all")
        assertEquals("INTERNAL_ERROR", error.code)
        assertTrue(
            "the raw payload should survive into the message for debugging",
            error.message!!.contains("not json at all"),
        )
    }

    @Test
    fun `a payload with no code still reports a failure`() {
        val error = ErrorPayloads.toException("""{"message":"something went wrong"}""")
        assertEquals("INTERNAL_ERROR", error.code)
    }

    @Test
    fun `an absent payload is not the same as an empty one`() {
        val error = ErrorPayloads.toException(null, fallbackOperation = "connect")
        assertEquals("INTERNAL_ERROR", error.code)
        assertEquals("connect", error.operation)
    }

    @Test
    fun `the operation falls back when the payload does not name one`() {
        val error =
            ErrorPayloads.toException(
                """{"code":"NOT_FOUND","message":"no such model"}""",
                fallbackOperation = "connect",
            )
        assertEquals("connect", error.operation)
    }

    // ── Recoverability ───────────────────────────────────────────────────────

    /**
     * What the platform said wins over what the local table thinks.
     *
     * `recoverable` is a non-optional field of the core's ErrorDetails, and `code_is_recoverable()`
     * is the single place that decides it. The table here used to be consulted instead, which made
     * it a second copy of that classification — and `check-error-codes-parity.py` explicitly does
     * not check recoverability, on the stated grounds that no binding keeps such a copy.
     */
    @Test
    fun `recoverable comes from the payload when the payload carries it`() {
        // Against the table in both directions, so neither answer can be the table's by accident.
        val notRetryable =
            ErrorPayloads.toException(
                """{"code":"SERVER_ERROR","message":"gone for good","recoverable":false}""",
                "connect",
            )
        assertEquals("SERVER_ERROR", notRetryable.code)
        assertTrue(
            "the table says SERVER_ERROR is recoverable; the payload said otherwise",
            !notRetryable.recoverable,
        )

        val retryable =
            ErrorPayloads.toException(
                """{"code":"BAD_REQUEST","message":"try again","recoverable":true}""",
                "connect",
            )
        assertTrue(
            "the table says BAD_REQUEST is not recoverable; the payload said otherwise",
            retryable.recoverable,
        )
    }

    /** A payload without the field falls back to the table rather than guessing. */
    @Test
    fun `recoverable falls back to the table when the payload omits it`() {
        val error = ErrorPayloads.toException("""{"code":"REQUEST_TIMEOUT","message":"slow"}""", "connect")
        assertTrue("a timeout is worth retrying", error.recoverable)

        val permanent = ErrorPayloads.toException("""{"code":"UNAUTHORIZED","message":"no"}""", "connect")
        assertTrue("a refused key is not", !permanent.recoverable)
    }

    /** An error this SDK raised itself never crossed the FFI, so it has only the table. */
    @Test
    fun `a locally raised refusal uses the table`() {
        val refusal =
            ErrorCode.toException(
                wire = "INVALID_STATE",
                message = "push before publish",
                operation = "pushFrame",
            )
        assertTrue(!refusal.recoverable)
    }

    /** An unknown code with no payload field stays not-recoverable: a retry loop is worse. */
    @Test
    fun `an unknown code is not recoverable unless the payload says so`() {
        val unknown = ErrorPayloads.toException("""{"code":"SOMETHING_NEW","message":"?"}""", "connect")
        assertTrue(!unknown.recoverable)

        val saidSo = ErrorPayloads.toException("""{"code":"SOMETHING_NEW","message":"?","recoverable":true}""", "connect")
        assertTrue("the platform is allowed to tell us about its own codes", saidSo.recoverable)
    }
}
