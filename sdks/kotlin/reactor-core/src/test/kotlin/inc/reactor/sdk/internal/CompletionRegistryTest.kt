package inc.reactor.sdk.internal

import inc.reactor.sdk.AbortedError
import inc.reactor.sdk.DecodeFailedError
import inc.reactor.sdk.RateLimitedError
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class CompletionRegistryTest {
    @Test
    fun errorMetadataAndUnknownCodes() {
        val error =
            decodeError(
                Json.parseToJsonElement(
                    """{"code":"RATE_LIMITED","message":"slow down","recoverable":false,"status":429,"operation":"connect","retry_after_ms":123.5,"timestamp_ms":8}""",
                ),
            )
        assertTrue(error is RateLimitedError)
        assertTrue(error.recoverable)
        assertEquals(429, error.status)
        assertEquals(123.5, error.retryAfterMillis)
        assertEquals("connect", error.operation)
        assertEquals(8.0, error.timestampMillis)
        val unknown = decodeError(Json.parseToJsonElement("""{"code":"FUTURE_CODE","message":"new"}"""))
        assertEquals("FUTURE_CODE", unknown.code)
        assertFalse(unknown.recoverable)
    }

    @Test
    fun invalidSuccessSettlesAsDecodeFailure() =
        runBlocking {
            supervisorScope {
                for (payload in listOf("{broken", "[]", "{\"answer\":true}")) {
                    val registry = CompletionRegistry()
                    var id = 0L
                    val task =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            registry.await({ value ->
                                value!!
                                    .jsonObject
                                    .getValue("answer")
                                    .jsonPrimitive.content
                                    .toInt()
                            }) { id = it }
                        }
                    registry.complete(id, payload.toByteArray(), null)
                    assertTrue(runCatching { task.await() }.exceptionOrNull() is DecodeFailedError)
                    assertEquals(0, registry.pendingCount)
                }
            }
        }

    @Test
    fun absentAndJsonNullRemainDifferent() =
        runBlocking {
            val registry = CompletionRegistry()
            val absent = registry.await({ it == null }) { registry.complete(it, null, null) }
            val explicit = registry.await({ it === JsonNull }) { registry.complete(it, "null".toByteArray(), null) }
            assertTrue(absent)
            assertTrue(explicit)
        }

    @Test
    fun cancellationAndCloseIgnoreLateCompletions() =
        runBlocking {
            supervisorScope {
                val registry = CompletionRegistry()
                var cancelledId = 0L
                val cancelled = async(start = CoroutineStart.UNDISPATCHED) { registry.await({ it }) { cancelledId = it } }
                cancelled.cancel()
                cancelled.join()
                registry.complete(cancelledId, "{}".toByteArray(), null)
                var closedId = 0L
                val closed = async(start = CoroutineStart.UNDISPATCHED) { registry.await({ it }) { closedId = it } }
                registry.close()
                registry.close()
                registry.complete(closedId, "{}".toByteArray(), null)
                assertTrue(runCatching { closed.await() }.exceptionOrNull() is AbortedError)
                assertEquals(0, registry.pendingCount)
                assertTrue(runCatching { registry.await({ it }) { error("must not start") } }.exceptionOrNull() is AbortedError)
            }
        }

    @Test
    fun closeWinsWhileResultIsBeingDecoded() =
        runBlocking {
            supervisorScope {
                val registry = CompletionRegistry()
                val decoding = CountDownLatch(1)
                val proceed = CountDownLatch(1)
                var id = 0L
                val task =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        registry.await({
                            decoding.countDown()
                            check(proceed.await(5, TimeUnit.SECONDS))
                            42
                        }) { id = it }
                    }
                val worker = thread { registry.complete(id, "{}".toByteArray(), null) }
                try {
                    assertTrue(decoding.await(5, TimeUnit.SECONDS))
                    registry.close()
                } finally {
                    proceed.countDown()
                    worker.join(5000)
                }
                assertFalse(worker.isAlive)
                assertTrue(runCatching { task.await() }.exceptionOrNull() is AbortedError)
            }
        }

    @Test
    fun duplicatesAndStartFailuresSettleOnce() =
        runBlocking {
            val registry = CompletionRegistry()
            val result =
                registry.await({ 7 }) {
                    registry.complete(it, "{}".toByteArray(), null)
                    registry.complete(it, "{bad".toByteArray(), null)
                }
            assertEquals(7, result)
            assertNotNull(runCatching { registry.await({ it }) { error("start failed") } }.exceptionOrNull())
            assertEquals(0, registry.pendingCount)
        }
}
