package inc.reactor.sdk.kotlin

import inc.reactor.sdk.ReactorException
import inc.reactor.sdk.TrackDirection
import inc.reactor.sdk.TrackKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Every refusal the binding makes, made through the Kotlin surface.
 *
 * The point is not that the facade refuses — it is that it refuses by *forwarding*, so the
 * exception a Kotlin caller catches is the same object a Java caller would, thrown by the same
 * code. A facade that re-implemented the checks could disagree with the binding, and this is what
 * would notice.
 */
class RefusalsTest : FacadeFixture() {

    @Test
    fun `a name the session never declared raises, listing the ones it did`() {
        val raised = assertThrows<ReactorException> { client.track("nope") }
        assertTrue(raised.message!!.contains("camera_in"), raised.message)
        assertTrue(raised.message!!.contains("screen_out"), raised.message)
    }

    @Test
    fun `pushing into a recvonly track raises, naming the direction`() {
        val raised =
            assertThrows<ReactorException> {
                client.track("screen_out").pushFrame(ByteArray(4), 1, 1)
            }
        assertTrue(raised.message!!.contains("recvonly"), raised.message)
    }

    @Test
    fun `receiving on a sendonly track raises`() {
        assertThrows<ReactorException> { client.track("camera_in").onVideoFrame {} }
    }

    @Test
    fun `an audio handler on a video track raises, naming the type it wanted`() {
        val raised = assertThrows<ReactorException> { client.track("screen_out").onAudioFrame {} }
        assertTrue(raised.message!!.contains("VideoFrameHandler"), raised.message)
    }

    @Test
    fun `pushing before publishing raises`() {
        assertThrows<ReactorException> { client.track("camera_in").pushFrame(ByteArray(4), 1, 1) }
    }

    @Test
    fun `pixel bytes of the wrong length raise, naming both numbers`() {
        val camera = client.track("camera_in")
        camera.java.publish()
        fake.settleLastCall(true, "{}", null)
        val raised = assertThrows<ReactorException> { camera.pushFrame(ByteArray(7), 2, 2) }
        assertTrue(raised.message!!.contains("7"), raised.message)
        assertTrue(raised.message!!.contains("16"), raised.message)
    }

    @Test
    fun `the exception is the binding's own type, not one this module invented`() {
        val fromKotlin = assertThrows<ReactorException> { client.track("nope") }
        val fromJava = assertThrows<ReactorException> { client.java.track("nope") }
        assertEquals(fromJava.javaClass, fromKotlin.javaClass)
        assertEquals(fromJava.code(), fromKotlin.code())
    }

    // ── Choosing a track ────────────────────────────────────────────────────

    @Test
    fun `tracks keep the order the session declared them in`() {
        assertEquals(listOf("camera_in", "screen_out", "mic_in"), client.tracks.map { it.name })
    }

    @Test
    fun `a track is the same object however it is reached`() {
        assertEquals(client.track("camera_in"), client.tracks[0])
        assertEquals(client.track("camera_in"), client.tracks["camera_in"])
    }

    @Test
    fun `get by name says what the session did declare`() {
        val raised = assertThrows<IllegalArgumentException> { client.tracks["nope"] }
        assertTrue(raised.message!!.contains("camera_in"), raised.message)
    }

    @Test
    fun `filters chain in either order and one picks the survivor`() {
        assertEquals(
            "camera_in",
            client.tracks
                .withKind(TrackKind.VIDEO)
                .withDirection(TrackDirection.SENDONLY)
                .one()
                .name,
        )
        assertEquals(
            "camera_in",
            client.tracks
                .withDirection(TrackDirection.SENDONLY)
                .withKind(TrackKind.VIDEO)
                .one()
                .name,
        )
    }

    @Test
    fun `one refuses to guess`() {
        assertThrows<IllegalArgumentException> { client.tracks.withKind(TrackKind.VIDEO).one() }
        assertThrows<IllegalArgumentException> { emptyList<ReactorTrack>().one() }
    }
}
