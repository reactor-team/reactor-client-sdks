package inc.reactor.sdk.android.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The crossing itself, against the real library.
 *
 * None of this connects: `reactor_create_with_adm` builds a client without touching the network,
 * which is exactly what makes the boundary testable without a live model. What is under test is
 * the part that ends a process rather than failing a call — who frees which string, whether the
 * global references survive teardown, and whether destroying twice is a no-op or a double free.
 */
class NativeBoundaryTest {
    private class RecordingEvents : NativeEvents {
        val statuses = mutableListOf<String?>()
        val errors = mutableListOf<String?>()
        val sessionIds = mutableListOf<String?>()
        val received = CountDownLatch(1)

        override fun onStatus(status: String?) {
            synchronized(statuses) { statuses += status }
            received.countDown()
        }

        override fun onError(errorJson: String?) {
            synchronized(errors) { errors += errorJson }
        }

        override fun onSessionId(sessionId: String?) {
            synchronized(sessionIds) { sessionIds += sessionId }
        }

        // Media never arrives here: nothing in this class connects, so no track is ever
        // negotiated. Implemented because the interface requires it, and counted rather than
        // ignored so that a frame showing up in a test that cannot receive one is loud.
        var unexpectedFrames = 0
            private set

        override fun onVideoFrame(
            trackName: String?,
            pixels: java.nio.ByteBuffer?,
            width: Int,
            height: Int,
            frameId: Long,
            timestampUs: Long,
            userData: ByteArray?,
        ) {
            synchronized(this) { unexpectedFrames++ }
        }

        override fun onAudioFrame(
            trackName: String?,
            pcm: java.nio.ByteBuffer?,
            sampleCount: Int,
            sampleRate: Int,
            channels: Int,
        ) {
            synchronized(this) { unexpectedFrames++ }
        }
    }

    private fun handle(listener: NativeEvents = RecordingEvents()) =
        NativeClient.create(
            apiUrl = "https://api.invalid",
            modelName = "reactor/echo",
            listener = listener,
            sdkVersion = "0.1.0-test",
        )

    /**
     * `reactor_status` returns a static literal the FFI owns forever. Reading it repeatedly is
     * the shape that would corrupt the heap if the bridge passed it to `reactor_free_string`:
     * once is survivable by luck, a hundred times is not.
     */
    @Test
    fun staticStringIsNeverFreed() {
        handle().use { client ->
            val first = client.status
            assertNotNull("reactor_status never returns null", first)
            repeat(100) { assertEquals(first, client.status) }
        }
    }

    /**
     * `reactor_tracks` and `reactor_paused_tracks` hand over ownership on every call. Reading
     * them in a loop leaks steadily if the bridge forgets `reactor_free_string`, and double-frees
     * if it frees a borrowed one by mistake; either shows up here under a sanitizer, and the leak
     * shows up in A14's endurance suite.
     */
    @Test
    fun ownedStringsSurviveRepeatedReads() {
        handle().use { client ->
            repeat(200) {
                assertNotNull(client.tracks)
                assertNotNull(client.pausedTracks)
            }
        }
    }

    /** No session before connecting, and null is an answer rather than an error. */
    @Test
    fun sessionIdIsNullBeforeConnecting() {
        handle().use { client -> assertNull(client.sessionId) }
    }

    /**
     * Teardown twice. The second `close()` must not reach `reactor_destroy` again: that is a
     * double free of the handle, which aborts the process rather than throwing.
     */
    @Test
    fun closingTwiceIsANoOp() {
        val client = handle()
        client.close()
        client.close()
    }

    /** Using a closed handle is a caller error, and must be refused rather than dereferenced. */
    @Test
    fun useAfterCloseIsRefused() {
        val client = handle()
        client.close()
        assertThrows(IllegalStateException::class.java) { client.status }
    }

    /**
     * Control events arrive on threads the FFI owns, which have no JNIEnv until the bridge
     * attaches one. If `ScopedEnv` were wrong — caching an env across threads, or detaching a
     * thread the JVM owns — this is where the process would die rather than fail.
     */
    @Test
    fun eventsArriveOnForeignThreads() {
        val listener = RecordingEvents()
        handle(listener).use {
            // Creation alone emits at least an initial status; a real connection is not needed to
            // prove the callback path crosses back into Kotlin.
            listener.received.await(5, TimeUnit.SECONDS)
        }
        synchronized(listener.statuses) {
            // The *contents*, not the list. assertNotNull on a list that is constructed non-null
            // passes whether or not anything ever crossed — which is the whole claim.
            assertTrue(
                "no control event crossed back into Kotlin within 5s",
                listener.statuses.isNotEmpty(),
            )
        }
    }

    /**
     * A handler that throws must not take the process with it. The bridge clears the pending
     * exception rather than returning into Rust with one set, which is undefined behaviour and
     * ends the process at the next JNI call.
     */
    @Test
    fun aThrowingHandlerDoesNotKillTheProcess() {
        // Counted, because the containment claim is only tested if a handler actually threw. With
        // a bare sleep and no count this passes when *no event arrives at all* — it proves the
        // process is alive, which it would be either way.
        val threw =
            java.util.concurrent.atomic
                .AtomicInteger()
        val throwing =
            object : NativeEvents {
                override fun onStatus(status: String?): Unit = fail()

                override fun onError(errorJson: String?): Unit = fail()

                override fun onSessionId(sessionId: String?): Unit = fail()

                override fun onVideoFrame(
                    trackName: String?,
                    pixels: java.nio.ByteBuffer?,
                    width: Int,
                    height: Int,
                    frameId: Long,
                    timestampUs: Long,
                    userData: ByteArray?,
                ): Unit = fail()

                override fun onAudioFrame(
                    trackName: String?,
                    pcm: java.nio.ByteBuffer?,
                    sampleCount: Int,
                    sampleRate: Int,
                    channels: Int,
                ): Unit = fail()

                private fun fail(): Nothing {
                    threw.incrementAndGet()
                    throw RuntimeException("handler bug")
                }
            }
        handle(throwing).use { client ->
            Live.await("a handler to throw") { threw.get() > 0 }
            // Still usable: the exception was the handler's, not the client's.
            assertNotNull(client.status)
        }
        assertTrue("no handler ever threw, so nothing was contained", threw.get() > 0)
    }

    /** Polls for something the native side produces when it is ready to. */
    private object Live {
        fun await(
            what: String,
            timeoutMs: Long = 5_000,
            condition: () -> Boolean,
        ) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (condition()) return
                Thread.sleep(25)
            }
            throw AssertionError("waited ${timeoutMs}ms for $what and it never happened")
        }
    }

    /** A clean teardown releases its references, so nothing is orphaned. */
    @Test
    fun cleanTeardownOrphansNothing() {
        val before = NativeClient.orphanedContexts
        repeat(5) { handle().use { } }
        assertEquals(
            "a quiesced reactor_destroy must release its global references",
            before,
            NativeClient.orphanedContexts,
        )
    }
}
