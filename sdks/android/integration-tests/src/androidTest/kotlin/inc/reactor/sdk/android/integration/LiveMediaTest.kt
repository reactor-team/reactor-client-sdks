package inc.reactor.sdk.android.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import inc.reactor.sdk.android.AudioFrame
import inc.reactor.sdk.android.InvalidStateException
import inc.reactor.sdk.android.VideoFrame
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Media over the wire, both directions.
 *
 * Echo returns what it is sent, which is what makes a round trip assertable at all: a frame
 * pushed into `webcam` comes back on `main_video`. Nothing else published does that.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
internal class LiveMediaTest : LiveFixture() {
    private companion object {
        const val WIDTH = 64
        const val HEIGHT = 64
    }

    /**
     * 10ms of interleaved signed 16-bit PCM at 48kHz mono, direct because the native layer reads
     * it in place. 480 samples *per channel* — the ABI's units, not the total.
     */
    private fun pcm(): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(480 * 2).order(ByteOrder.nativeOrder())
        repeat(480) { buffer.putShort((it * 64).toShort()) }
        buffer.rewind()
        return buffer
    }

    /** A solid-colour BGRA frame, tagged so the frame that comes back is identifiably ours. */
    private fun frame(tag: Byte): ByteBuffer {
        val pixels = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4).order(ByteOrder.nativeOrder())
        repeat(WIDTH * HEIGHT) {
            pixels.put(tag) // B
            pixels.put(0x20) // G
            pixels.put(0x40) // R
            pixels.put(0xFF.toByte()) // A
        }
        pixels.rewind()
        return pixels
    }

    @Test
    fun publishedFramesComeBack() =
        runBlocking {
            val outgoing = reactor.track(Live.VIDEO_IN)
            val incoming = reactor.track(Live.VIDEO_OUT)
            val received = AtomicInteger()
            val shape = AtomicReference<String>()

            incoming.onFrame { frame: VideoFrame ->
                received.incrementAndGet()
                shape.compareAndSet(null, "${frame.width}x${frame.height}")
            }
            outgoing.publish()

            try {
                // Pushed repeatedly rather than once: the first frames of a freshly negotiated
                // track are routinely dropped while the encoder settles, and a single push would
                // make this test a coin flip.
                Live.await("a pushed frame to come back", timeoutMs = 60_000) {
                    outgoing.pushFrame(frame(0x7F), WIDTH, HEIGHT)
                    received.get() > 0
                }
                assertNotNull("the returned frame must carry its shape", shape.get())
            } finally {
                incoming.removeHandlers()
                runCatching { outgoing.unpublish() }
            }
        }

    /**
     * Pushing before publishing is refused rather than dropped.
     *
     * The FFI takes the frame and discards it — there is no sender behind the slot yet — so the
     * caller sees a loop pushing at 30fps and a model receiving nothing. This is the invariant
     * that failure mode exists to prevent, checked against a real session.
     */
    @Test
    fun pushingBeforePublishingIsRefused() {
        val outgoing = reactor.track(Live.AUDIO_IN)
        assertTrue("this test needs an unpublished track", !outgoing.published)
        assertThrows(InvalidStateException::class.java) {
            outgoing.pushFrame(pcm(), samplesPerChannel = 480, sampleRate = 48_000, channels = 1)
        }
    }

    @Test
    fun receivingOnASendonlyTrackIsRefused() {
        assertThrows(InvalidStateException::class.java) {
            reactor.track(Live.VIDEO_IN).onFrame { _: VideoFrame -> }
        }
    }

    @Test
    fun pushingIntoARecvonlyTrackIsRefused() {
        assertThrows(InvalidStateException::class.java) {
            reactor.track(Live.VIDEO_OUT).pushFrame(frame(0x01), WIDTH, HEIGHT)
        }
    }

    @Test
    fun audioTravels() =
        runBlocking {
            val outgoing = reactor.track(Live.AUDIO_IN)
            val incoming = reactor.track(Live.AUDIO_OUT)
            val received = AtomicInteger()
            val rate = AtomicInteger()

            // The parameter is typed because onFrame is overloaded on it — one frame API for both
            // kinds, as every Reactor SDK has — and the kind is not inferable from the body.
            incoming.onFrame { frame: AudioFrame ->
                received.incrementAndGet()
                rate.compareAndSet(0, frame.sampleRate)
            }
            outgoing.publish()

            try {
                Live.await("audio to come back", timeoutMs = 60_000) {
                    outgoing.pushFrame(pcm(), samplesPerChannel = 480, sampleRate = 48_000, channels = 1)
                    received.get() > 0
                }
                assertTrue("the returned audio must declare a sample rate", rate.get() > 0)
            } finally {
                incoming.removeHandlers()
                runCatching { outgoing.unpublish() }
            }
        }

    /**
     * Pause stops delivery and resume restores it.
     *
     * Counted across a window rather than asserted on the next frame: "paused" is a request that
     * takes effect when the platform acts on it, and the frame already in flight when it was sent
     * is not a bug.
     */
    @Test
    fun pauseAndResumeTakeEffect() =
        runBlocking {
            val incoming = reactor.track(Live.VIDEO_OUT)
            val outgoing = reactor.track(Live.VIDEO_IN)
            val received = AtomicInteger()
            incoming.onFrame { _: VideoFrame -> received.incrementAndGet() }
            outgoing.publish()

            try {
                Live.await("frames before pausing", timeoutMs = 60_000) {
                    outgoing.pushFrame(frame(0x11), WIDTH, HEIGHT)
                    received.get() > 0
                }

                incoming.pause()
                assertTrue("the track must report itself paused", incoming.paused)
                kotlinx.coroutines.delay(2_000) // let anything in flight land
                val whilePaused = received.get()
                repeat(20) {
                    outgoing.pushFrame(frame(0x22), WIDTH, HEIGHT)
                    kotlinx.coroutines.delay(50)
                }
                assertEquals(
                    "a paused track must deliver nothing",
                    whilePaused,
                    received.get(),
                )

                incoming.resume()
                assertTrue("the track must report itself resumed", !incoming.paused)
                Live.await("frames after resuming", timeoutMs = 60_000) {
                    outgoing.pushFrame(frame(0x33), WIDTH, HEIGHT)
                    received.get() > whilePaused
                }
            } finally {
                incoming.removeHandlers()
                runCatching { if (incoming.paused) incoming.resume() }
                runCatching { outgoing.unpublish() }
            }
        }
}
