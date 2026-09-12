package inc.reactor.sdk.internal

import inc.reactor.sdk.AbortedError
import inc.reactor.sdk.BadRequestError
import inc.reactor.sdk.DecodeFailedError
import inc.reactor.sdk.InvalidStateError
import inc.reactor.sdk.Reactor
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandTest {
    init {
        val library = System.getProperty("reactor.jni.test.library")
        if (library == null) System.loadLibrary("reactor_jni_test") else System.load(library)
    }

    private external fun configure(mode: Int)

    private external fun replyWith(payload: ByteArray?)

    private external fun finishReverse()

    private external fun orphanNextDestroy()

    private external fun emitMessage(runtime: Boolean)

    private external fun statsFixture(): ByteArray

    private suspend fun client(): Reactor {
        configure(0)
        return Reactor("model", local = true).also { it.connect() }
    }

    @Test fun immediateReplyIsReturnedWithoutAnyMessageEvent() =
        runBlocking<Unit> {
            val client = client()
            try {
                val messages = Channel<JsonObject>(Channel.UNLIMITED)
                client.onMessage { messages.trySend(it) }
                val args = JsonObject(mapOf("text" to JsonPrimitive("hello 🌍\u0000")))
                val reply = withTimeout(5000) { client.sendCommand("echo", args) }
                assertEquals("reply", reply?.type)
                assertEquals(args, reply?.data)
                assertTrue(messages.tryReceive().isFailure)
                configure(3)
                assertNull(client.sendCommand("ack"))
            } finally {
                client.close()
            }
        }

    @Test fun concurrentRepliesRemainCorrelatedWhenCompletedInReverse() =
        runBlocking<Unit> {
            val client = client()
            try {
                configure(1)
                val commands =
                    (1..30).map { value ->
                        async(start = CoroutineStart.UNDISPATCHED) {
                            client.sendCommand(
                                "echo",
                                JsonObject(
                                    mapOf(
                                        "id" to JsonPrimitive(value),
                                    ),
                                ),
                            )
                        }
                    }
                finishReverse()
                withTimeout(5000) {
                    commands.forEachIndexed { index, pending ->
                        assertEquals(JsonObject(mapOf("id" to JsonPrimitive(index + 1))), pending.await()?.data)
                    }
                }
            } finally {
                client.close()
            }
        }

    @Test fun errorsAndMalformedPayloadsNeverBecomeSuccessfulEmptyResults() =
        runBlocking<Unit> {
            val client = client()
            try {
                configure(2)
                assertTrue(runCatching { client.sendCommand("bad") }.exceptionOrNull() is BadRequestError)
                for (mode in listOf(4, 5, 6)) {
                    configure(mode)
                    assertTrue(runCatching { client.sendCommand("bad") }.exceptionOrNull() is DecodeFailedError)
                }
                for (mode in listOf(3, 4, 5)) {
                    configure(mode)
                    assertTrue(runCatching { client.requestSchema() }.exceptionOrNull() is DecodeFailedError)
                }
                configure(0)
                assertEquals(JsonPrimitive("3.1.0"), client.requestSchema()["openapi"])
                assertThrows(IllegalArgumentException::class.java) { runBlocking { client.sendCommand("\u0000") } }
            } finally {
                client.close()
            }
        }

    @Test fun cancelledCommandsIgnoreLateRepliesAndCloseSettlesOrphanedWork() =
        runBlocking<Unit> {
            val client = client()
            try {
                configure(1)
                val cancelled = async(start = CoroutineStart.UNDISPATCHED) { client.sendCommand("cancelled") }
                cancelled.cancel()
                cancelled.join()
                finishReverse()
                val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { client.requestSchema() } }
                orphanNextDestroy()
                client.close()
                assertTrue(withTimeout(5000) { pending.await() }.exceptionOrNull() is AbortedError)
                finishReverse() // Actual C completion after destroy(-1), under ASan too.
                assertTrue(runCatching { client.sendCommand("closed") }.exceptionOrNull() is InvalidStateError)
            } finally {
                client.close()
            }
        }

    @Test fun unsolicitedApplicationAndRuntimeMessagesRemainSeparateAndRemovable() =
        runBlocking<Unit> {
            val client = client()
            try {
                val messages = Channel<JsonObject>(Channel.UNLIMITED)
                val runtime = Channel<JsonObject>(Channel.UNLIMITED)
                val subscription = client.onMessage { messages.trySend(it) }
                client.onRuntimeMessage { if (it["type"] == JsonPrimitive("notice")) runtime.trySend(it) }
                emitMessage(false)
                assertEquals(JsonPrimitive("notice"), withTimeout(5000) { messages.receive() }["type"])
                subscription.close()
                emitMessage(false)
                emitMessage(true) // Ordered control queue drains the removed subscription first.
                assertEquals(JsonPrimitive("notice"), withTimeout(5000) { runtime.receive() }["type"])
                assertTrue(messages.tryReceive().isFailure)
            } finally {
                client.close()
            }
        }

    @Test fun statisticsPreserveFullWidthCountersSignedLossAndNullMeasurements() =
        runBlocking<Unit> {
            val client = client()
            try {
                val measured = client.getStats()
                assertEquals(21.5, measured.rttMs!!, 0.0)
                assertEquals(9_115_038_255_631_187_199uL, measured.candidatePairs.single().priority)
                assertEquals(5_000_000_001uL, measured.candidatePairs.single().packetsReceived)
                assertEquals(111u, measured.inbound.single().ssrc)
                assertEquals("audio", measured.outbound.single().kind)
                val fixture = Json.parseToJsonElement(statsFixture().decodeToString()) as JsonObject
                val modified = fixture.toMutableMap()
                modified["rtt_ms"] = JsonNull
                modified["incoming_bitrate_bps"] = JsonNull
                modified["packets_lost"] = JsonPrimitive(Long.MIN_VALUE)
                modified["bytes_received"] = Json.parseToJsonElement("18446744073709551615")
                modified["inbound"] =
                    JsonArray(
                        listOf(
                            JsonObject(
                                (fixture["inbound"] as JsonArray).single().let { it as JsonObject }.toMutableMap().apply {
                                    put("packets_lost", JsonPrimitive(-2))
                                },
                            ),
                        ),
                    )
                replyWith(JsonObject(modified).toString().encodeToByteArray())
                val result = client.getStats()
                assertNull(result.rttMs)
                assertNull(result.incomingBitrateBps)
                assertEquals(Long.MIN_VALUE, result.packetsLost)
                assertEquals(ULong.MAX_VALUE, result.bytesReceived)
                assertEquals(-2, result.inbound.single().packetsLost)
            } finally {
                client.close()
            }
        }

    @Test fun invalidStatisticsFailInsteadOfSubstitutingZeroes() =
        runBlocking<Unit> {
            val client = client()
            try {
                val fixture = Json.parseToJsonElement(statsFixture().decodeToString()) as JsonObject
                for ((key, value) in listOf(
                    "packets_lost" to JsonPrimitive("2"),
                    "bytes_sent" to JsonPrimitive(-1),
                    "bytes_received" to Json.parseToJsonElement("18446744073709551616"),
                    "rtt_ms" to JsonPrimitive("NaN"),
                    "inbound" to JsonNull,
                )) {
                    replyWith(JsonObject(fixture.toMutableMap().apply { put(key, value) }).toString().encodeToByteArray())
                    assertTrue("Rejected $key", runCatching { client.getStats() }.exceptionOrNull() is DecodeFailedError)
                }
                replyWith("{}".encodeToByteArray())
                assertTrue(runCatching { client.getStats() }.exceptionOrNull() is DecodeFailedError)
                configure(0)
                client.disconnect()
                assertTrue(runCatching { client.getStats() }.exceptionOrNull() is InvalidStateError)
            } finally {
                client.close()
            }
        }
}
