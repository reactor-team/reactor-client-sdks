package inc.reactor.sdk.kotlin

import inc.reactor.sdk.PublishState
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * What cancelling one of these actually cancels.
 *
 * The answer is "the await", and it is worth a test rather than only a line of KDoc: a Kotlin
 * caller reads `suspend` and reasonably expects structured concurrency to reach all the way down.
 * It cannot. The native call is already in flight by the time there is anything to cancel, the FFI
 * has no way to recall it, and the model has already been told. A command whose caller walked away
 * still ran.
 */
class CancellationTest : FacadeFixture() {

    @Test
    fun `cancelling the await does not stop the operation`() = runBlocking {
        val awaiting = async(Dispatchers.Default) { client.sendCommand("set_prompt") }

        // The command has reached the fake, which is holding its completion.
        awaitCallReached()

        awaiting.cancel()
        assertThrows<CancellationException> { awaiting.await() }

        // The operation was never told. Settling it now is what the FFI would have done anyway, and
        // it must not throw into a client that has no caller left for it.
        fake.settleLastCall(true, """{"type":"ok"}""", null)
        assertFalse(client.isClosed)
    }

    @Test
    fun `an uncancelled await still gets its answer`() = runBlocking {
        val awaiting = async(Dispatchers.Default) { client.sendCommand("set_prompt") }
        awaitCallReached()
        fake.settleLastCall(true, """{"type":"ok"}""", null)
        val reply = awaiting.await()
        assertNotNull(reply)
        assertTrue(reply!!.type().orElseThrow() == "ok", reply.toString())
    }

    @Test
    fun `cancelling a publish does not strand the track in PUBLISHING`() = runBlocking {
        val camera = client.track("camera_in")
        val publishing = async(Dispatchers.Default) { camera.publish() }
        awaitCallReached()

        publishing.cancel()
        withContext(Dispatchers.Default) { publishing.join() }

        // The native publish was never told, and goes on to succeed. What the caller abandoned is
        // the wait; what must not be abandoned is the bookkeeping that says a sender exists.
        //
        // It was. `publish()` used to return the stage its own `whenComplete` produced, and a
        // dependent stage that is already complete is skipped when its source settles — so
        // cancelling it dropped the state update, the track sat in PUBLISHING for the rest of the
        // session, and every pushFrame was refused with "still publishing. Await the future".
        fake.settleLastCall(true, "{}", null)
        awaitPublished(camera)
        assertEquals(PublishState.PUBLISHED, camera.publishState)
        assertTrue(camera.isPublished)
        // The proof that matters to a caller: the track is usable.
        camera.pushFrame(ByteArray(4), 1, 1)
    }

    private suspend fun awaitPublished(track: ReactorTrack) {
        withContext(Dispatchers.Default) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (track.publishState != PublishState.PUBLISHED) {
                check(System.nanoTime() < deadline) { "still ${track.publishState} after 5s" }
                Thread.sleep(1)
            }
        }
    }

    /** The call is made on another thread; settling before it arrives settles nothing. */
    private suspend fun awaitCallReached() {
        withContext(Dispatchers.Default) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!fake.hasPendingCall()) {
                check(System.nanoTime() < deadline) { "the command never reached the library" }
                Thread.sleep(1)
            }
        }
    }
}
