package inc.reactor.sdk.internal

import inc.reactor.sdk.AbortedError
import inc.reactor.sdk.ControlEvent
import inc.reactor.sdk.InvalidStateError
import inc.reactor.sdk.Reactor
import inc.reactor.sdk.ReactorStatus
import inc.reactor.sdk.TokenProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LifecycleTest {
    init {
        val library = System.getProperty("reactor.jni.test.library")
        if (library == null) System.loadLibrary("reactor_jni_test") else System.load(library)
    }

    private external fun resetFake(mode: Int)

    private external fun destroyCount(): Int

    private external fun createCount(): Int

    private external fun connectionId(): Long

    private external fun clientIdentity(): ByteArray

    private external fun clientToken(): ByteArray

    private external fun finishLate()

    private external fun waitForStart()

    private external fun releaseStart()

    private external fun emitStatus()

    @Test fun lifecycleUsesSyntheticAdmIdentityAndAdoptedIds() =
        runBlocking {
            resetFake(0)
            val client = Reactor("owner/model", TokenProvider { "short-lived-token" })
            try {
                assertEquals(ReactorStatus.DISCONNECTED, client.status)
                client.connect("adopted-session", 0xffff_ffffL)
                assertEquals(ReactorStatus.READY, client.status)
                assertEquals("adopted-session", client.sessionId)
                assertEquals(0xffff_ffffL, connectionId())
                assertEquals("kotlin/$SDK_VERSION", clientIdentity().decodeToString())
                assertEquals("short-lived-token", clientToken().decodeToString())
                client.reconnect()
                assertEquals("adopted-session", client.sessionId)
                client.disconnect()
                assertNull(client.sessionId)
                val failure = runCatching { client.reconnect() }.exceptionOrNull()
                assertTrue(failure is InvalidStateError)
            } finally {
                client.close()
            }
            client.close()
            assertEquals(1, destroyCount())
            assertTrue(runCatching { client.connect() }.exceptionOrNull() is InvalidStateError)
        }

    @Test fun tokenRefreshRecreatesHandleAndInvalidIdsHaveNoSideEffects() =
        runBlocking {
            resetFake(0)
            var calls = 0
            val client = Reactor("model", TokenProvider { "token-${++calls}" })
            try {
                assertTrue(runCatching { client.connect(connectionId = -1) }.isFailure)
                assertEquals(0, calls)
                client.connect()
                client.connect("existing")
                assertEquals(2, createCount())
                assertEquals(1, destroyCount())
                assertEquals("token-2", clientToken().decodeToString())
            } finally {
                client.close()
            }
        }

    @Test fun throwingListenerDoesNotSilenceOthersAndRemovalWorks() =
        runBlocking {
            resetFake(0)
            val reports = AtomicInteger()
            val delivered = AtomicInteger()
            val first = CompletableDeferred<Unit>()
            val client = Reactor("model", local = true, onHandlerFailure = { reports.incrementAndGet() })
            try {
                client.onEvent { error("broken listener") }
                val subscription =
                    client.onEvent {
                        delivered.incrementAndGet()
                        first.complete(Unit)
                    }
                client.connect()
                withTimeout(5000) {
                    first.await()
                    while (delivered.get() < 4) delay(1)
                }
                assertEquals(1, reports.get())
                subscription.close()
                val before = delivered.get()
                emitStatus()
                client.disconnect()
                delay(50)
                assertEquals(before, delivered.get())
            } finally {
                client.close()
            }
        }

    @Test fun closeSettlesPendingAndLateCallbacksSurviveFailedDestroy() =
        runBlocking {
            resetFake(1)
            val client = Reactor("model", local = true)
            val ready = CompletableDeferred<Unit>()
            client.onEvent { if (it is ControlEvent.StatusChanged) ready.complete(Unit) }
            val pending = async { runCatching { client.connect() } }
            withTimeout(5000) { ready.await() }
            client.close()
            assertTrue(withTimeout(5000) { pending.await() }.exceptionOrNull() is AbortedError)
            finishLate()
            client.close()
            assertEquals(1, destroyCount())
        }

    @Test fun cancellationDoesNotFreeNativeCompletion() =
        runBlocking {
            resetFake(2)
            val client = Reactor("model", local = true)
            val ready = CompletableDeferred<Unit>()
            client.onEvent { ready.complete(Unit) }
            val pending = launch { client.connect() }
            withTimeout(5000) { ready.await() }
            pending.cancelAndJoin()
            finishLate()
            client.close()
            assertEquals(1, destroyCount())
        }

    @Test fun closeWaitsForNativeInitiationWithoutBlockingCallerDispatcher() =
        runBlocking {
            resetFake(3)
            val client = Reactor("model", local = true)
            val pending = async(Dispatchers.IO) { runCatching { client.connect() } }
            withContext(Dispatchers.IO) { waitForStart() }
            val closing = async(start = CoroutineStart.UNDISPATCHED) { client.close() }
            try {
                // close has suspended to IO, so this caller can release the native call.
                assertFalse(closing.isCompleted)
                assertEquals(0, destroyCount())
            } finally {
                releaseStart()
            }
            withTimeout(5000) {
                pending.await()
                closing.await()
            }
            assertEquals(1, destroyCount())
        }

    @Test fun listenerCanCloseAndDropLastClientReference() =
        runBlocking {
            resetFake(0)
            var client: Reactor? = Reactor("model", local = true, eventDispatcher = Dispatchers.Unconfined)
            val finished = CountDownLatch(1)
            client!!.onEvent {
                if (it is ControlEvent.StatusChanged) {
                    runBlocking { client?.close() }
                    client = null
                    finished.countDown()
                }
            }
            // close may abort connect before its queued completion is delivered.
            runCatching { client!!.connect() }
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertNull(client)
            assertEquals(1, destroyCount())
        }
}
