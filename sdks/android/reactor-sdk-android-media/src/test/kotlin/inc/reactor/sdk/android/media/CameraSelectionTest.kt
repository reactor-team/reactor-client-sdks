package inc.reactor.sdk.android.media

import inc.reactor.sdk.android.media.CameraSelection.Facing
import inc.reactor.sdk.android.media.CameraSelection.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Picking a resolution and an orientation.
 *
 * `CameraCharacteristics` cannot be constructed on a JVM, so this is the half of [CameraCapture] a
 * test can reach — and rotation in particular is the half that is wrong on somebody's handset for
 * months, because the symptom only appears when the device is held a particular way, with a
 * particular camera, on hardware the developer does not own.
 */
class CameraSelectionTest {
    private val typical =
        listOf(
            Size(176, 144),
            Size(320, 240),
            Size(352, 288),
            Size(640, 480),
            Size(720, 480),
            Size(1280, 720),
            Size(1920, 1080),
            Size(3840, 2160),
        )

    // ── Resolution ───────────────────────────────────────────────────────────

    @Test
    fun `an exact match is chosen`() {
        assertEquals(Size(640, 480), CameraSelection.bestSize(typical, 640, 480))
        assertEquals(Size(1280, 720), CameraSelection.bestSize(typical, 1280, 720))
    }

    /**
     * The case "closest by area" alone gets wrong.
     *
     * 720x480 (3:2) is nearer 640x480 in area than 1280x720 is to a 16:9 request, so an
     * area-only rule picks the wrong *shape* and the result is a stretched image nobody traces
     * back here.
     */
    @Test
    fun `aspect ratio beats area`() {
        val chosen = CameraSelection.bestSize(typical, 960, 540) // 16:9
        assertEquals("a 16:9 request must not get a 3:2 frame", 16.0 / 9, chosen.width.toDouble() / chosen.height, 0.05)
    }

    @Test
    fun `a size at or under the request is preferred over a larger one`() {
        val chosen = CameraSelection.bestSize(listOf(Size(320, 240), Size(1920, 1440)), 640, 480)
        assertEquals(Size(320, 240), chosen)
    }

    @Test
    fun `a larger size is taken when nothing smaller shares the shape`() {
        val chosen = CameraSelection.bestSize(listOf(Size(1280, 720)), 640, 360)
        assertEquals(Size(1280, 720), chosen)
    }

    @Test
    fun `a camera offering no YUV sizes is refused, saying why`() {
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                CameraSelection.bestSize(emptyList(), 640, 480)
            }
        assertTrue(error.message!!.contains("YUV_420_888"))
    }

    @Test
    fun `a non-positive target is a caller error`() {
        assertThrows(IllegalArgumentException::class.java) { CameraSelection.bestSize(typical, 0, 480) }
        assertThrows(IllegalArgumentException::class.java) { CameraSelection.bestSize(typical, 640, -1) }
    }

    // ── Rotation ─────────────────────────────────────────────────────────────

    /**
     * A back camera on a typical phone: sensor mounted at 90°, device held upright.
     */
    @Test
    fun `a back camera held naturally needs the sensor's own offset`() {
        assertEquals(90, CameraSelection.rotationDegrees(90, 0, Facing.BACK))
    }

    /**
     * The one that is wrong in the field. A front camera is mirrored, so its correction runs the
     * other way — and the symptom is an image that is upright held one way and upside down held
     * the other, which reads as "sometimes broken" rather than as a sign error.
     */
    @Test
    fun `a front camera corrects in the opposite direction from a back one`() {
        for (device in listOf(0, 90, 180, 270)) {
            val back = CameraSelection.rotationDegrees(90, device, Facing.BACK)
            val front = CameraSelection.rotationDegrees(90, device, Facing.FRONT)
            if (device != 0 && device != 180) {
                assertTrue(
                    "front and back must not agree at $device°, or the mirror is not handled",
                    back != front,
                )
            }
        }
        assertEquals(0, CameraSelection.rotationDegrees(90, 90, Facing.BACK))
        assertEquals(180, CameraSelection.rotationDegrees(90, 90, Facing.FRONT))
    }

    /** A tablet whose natural orientation is landscape has a 0° sensor, and must still work. */
    @Test
    fun `a zero-degree sensor is handled`() {
        assertEquals(0, CameraSelection.rotationDegrees(0, 0, Facing.BACK))
        assertEquals(270, CameraSelection.rotationDegrees(0, 90, Facing.BACK))
        assertEquals(90, CameraSelection.rotationDegrees(0, 90, Facing.FRONT))
    }

    @Test
    fun `the result is always a normalised multiple of 90`() {
        for (sensor in listOf(0, 90, 180, 270)) {
            for (device in listOf(0, 90, 180, 270)) {
                for (facing in Facing.entries) {
                    val result = CameraSelection.rotationDegrees(sensor, device, facing)
                    assertTrue("$sensor/$device/$facing gave $result", result in 0..270 && result % 90 == 0)
                }
            }
        }
    }

    @Test
    fun `a device rotation that is not a multiple of 90 is a caller error`() {
        assertThrows(IllegalArgumentException::class.java) {
            CameraSelection.rotationDegrees(90, 45, Facing.BACK)
        }
    }

    @Test
    fun `only the quarter turns swap width and height`() {
        assertTrue(CameraSelection.swapsDimensions(90))
        assertTrue(CameraSelection.swapsDimensions(270))
        assertFalse(CameraSelection.swapsDimensions(0))
        assertFalse(CameraSelection.swapsDimensions(180))
    }
}
