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

    /** With no size of the requested shape, the nearest shape wins over the nearest area. */
    @Test
    fun `the nearest shape is chosen when no size matches the request`() {
        // 16:9 requested; 1024x768 (4:3) is nearer in area, 1280x800 (16:10) nearer in shape.
        val chosen = CameraSelection.bestSize(listOf(Size(1024, 768), Size(1280, 800)), 1280, 720)
        assertEquals(Size(1280, 800), chosen)
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

    // ── Rotating the pixels ──────────────────────────────────────────────────

    /**
     * A 3x2 frame whose every pixel is distinct, so a rotation that lands any pixel in the wrong
     * place shows up as a mismatch rather than as two identical values swapping:
     *
     *     1 2 3
     *     4 5 6
     */
    private fun frame3x2(): java.nio.ByteBuffer {
        val bytes =
            java.nio.ByteBuffer
                .allocateDirect(3 * 2 * 4)
                .order(java.nio.ByteOrder.nativeOrder())
        for (pixel in 1..6) bytes.putInt(pixel)
        return bytes
    }

    private fun pixels(
        buffer: java.nio.ByteBuffer,
        count: Int,
    ): List<Int> {
        val view = buffer.duplicate().order(java.nio.ByteOrder.nativeOrder())
        return List(count) { view.getInt(it * 4) }
    }

    @Test
    fun `a quarter turn clockwise puts the bottom-left pixel top-left`() {
        val out = java.nio.ByteBuffer.allocateDirect(24)
        val size = CameraSelection.rotateBgra(frame3x2(), 3, 2, 90, out)
        assertEquals(Size(2, 3), size)
        //  4 1
        //  5 2
        //  6 3
        assertEquals(listOf(4, 1, 5, 2, 6, 3), pixels(out, 6))
    }

    @Test
    fun `a half turn reverses the frame`() {
        val out = java.nio.ByteBuffer.allocateDirect(24)
        assertEquals(Size(3, 2), CameraSelection.rotateBgra(frame3x2(), 3, 2, 180, out))
        assertEquals(listOf(6, 5, 4, 3, 2, 1), pixels(out, 6))
    }

    @Test
    fun `three quarter turns put the top-right pixel top-left`() {
        val out = java.nio.ByteBuffer.allocateDirect(24)
        assertEquals(Size(2, 3), CameraSelection.rotateBgra(frame3x2(), 3, 2, 270, out))
        //  3 6
        //  2 5
        //  1 4
        assertEquals(listOf(3, 6, 2, 5, 1, 4), pixels(out, 6))
    }

    @Test
    fun `no rotation copies the frame unchanged`() {
        val out = java.nio.ByteBuffer.allocateDirect(24)
        assertEquals(Size(3, 2), CameraSelection.rotateBgra(frame3x2(), 3, 2, 0, out))
        assertEquals(listOf(1, 2, 3, 4, 5, 6), pixels(out, 6))
    }

    /**
     * The bytes inside a pixel stay in order. The rotation moves whole ints, and an int read and
     * written in the same byte order is the same four bytes — but a version that mixed orders
     * would swap B and A, and every frame would come out the wrong colour.
     */
    @Test
    fun `the channels inside a pixel are not reordered`() {
        val source = java.nio.ByteBuffer.allocateDirect(4 * 2)
        source.put(byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80))
        val out = java.nio.ByteBuffer.allocateDirect(8)
        CameraSelection.rotateBgra(source, 2, 1, 90, out)
        val written = ByteArray(8).also { out.duplicate().get(it) }
        assertEquals(listOf<Byte>(10, 20, 30, 40, 50, 60, 70, 80), written.toList())
    }

    /** The result is ready to push: positioned at 0, limited to the frame. */
    @Test
    fun `the output is rewound and limited to the frame`() {
        val out = java.nio.ByteBuffer.allocateDirect(64)
        out.position(10)
        CameraSelection.rotateBgra(frame3x2(), 3, 2, 90, out)
        assertEquals(0, out.position())
        assertEquals(24, out.limit())
    }

    @Test
    fun `a rotation that is not a quarter turn is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            CameraSelection.rotateBgra(frame3x2(), 3, 2, 45, java.nio.ByteBuffer.allocateDirect(24))
        }
    }

    @Test
    fun `an output buffer too small for the frame is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            CameraSelection.rotateBgra(frame3x2(), 3, 2, 90, java.nio.ByteBuffer.allocateDirect(20))
        }
    }

    // ── Following the device ─────────────────────────────────────────────────

    /** The four quadrants, in the listener's clockwise degrees and the display's convention. */
    @Test
    fun `a physical orientation maps to the display rotation convention`() {
        assertEquals(0, CameraSelection.deviceRotationFor(0, current = 90))
        assertEquals(270, CameraSelection.deviceRotationFor(90, current = 0))
        assertEquals(180, CameraSelection.deviceRotationFor(180, current = 0))
        assertEquals(90, CameraSelection.deviceRotationFor(270, current = 0))
        assertEquals("359 is upright, across the seam", 0, CameraSelection.deviceRotationFor(359, current = 90))
    }

    /** The flip that never reaches onConfigurationChanged must still be followed. */
    @Test
    fun `turning from one landscape to the other is followed`() {
        val left = CameraSelection.deviceRotationFor(90, current = 0)
        assertEquals(90, CameraSelection.deviceRotationFor(270, current = left))
    }

    @Test
    fun `lying flat keeps the last orientation`() {
        assertEquals(270, CameraSelection.deviceRotationFor(ORIENTATION_FLAT, current = 270))
    }

    /** Near the diagonal, rounding alone would toggle the stream on every reading. */
    @Test
    fun `a reading near the diagonal does not flip the rotation`() {
        assertEquals("50° is not yet landscape", 0, CameraSelection.deviceRotationFor(50, current = 0))
        assertEquals("40° is not yet back to portrait", 270, CameraSelection.deviceRotationFor(40, current = 270))
        assertEquals("60° is landscape", 270, CameraSelection.deviceRotationFor(60, current = 0))
        assertEquals("30° is portrait again", 0, CameraSelection.deviceRotationFor(30, current = 270))
    }

    private companion object {
        /** OrientationEventListener.ORIENTATION_UNKNOWN, which a JVM test cannot load. */
        const val ORIENTATION_FLAT = -1
    }
}
