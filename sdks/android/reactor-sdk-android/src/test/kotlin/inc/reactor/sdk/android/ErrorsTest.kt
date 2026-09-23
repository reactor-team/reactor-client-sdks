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
}
