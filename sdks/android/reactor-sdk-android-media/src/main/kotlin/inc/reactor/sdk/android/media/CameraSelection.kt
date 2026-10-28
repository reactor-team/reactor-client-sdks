package inc.reactor.sdk.android.media

/**
 * Choosing a camera, a resolution and an orientation — the arithmetic, with no Android class.
 *
 * Same split as [YuvToBgra] and [AudioFormats], and for the same reason: `CameraCharacteristics`
 * cannot be constructed on a JVM, so anything left inside the camera wiring is untestable without
 * a device. What is genuinely easy to get wrong is here — and rotation especially, which is wrong
 * on a front camera, or wrong on a tablet whose natural orientation is landscape, in ways that are
 * invisible until someone holds a particular handset sideways.
 */
public object CameraSelection {
    /** Which way a camera faces, independent of the platform's integer constants. */
    public enum class Facing { FRONT, BACK }

    /** A resolution the hardware offers. */
    public data class Size(
        val width: Int,
        val height: Int,
    ) {
        val pixels: Int get() = width * height
    }

    /**
     * The closest supported size to [targetWidth] x [targetHeight], preferring not to exceed it.
     *
     * "Closest by area" alone picks a 4:3 frame for a 16:9 request whenever the areas happen to be
     * near, and the result is a stretched image nobody traces back to this function. Aspect ratio
     * is the first sort key; area is the tie-break.
     *
     * Prefers a size at or under the request, because scaling down a frame the encoder has already
     * paid for is cheaper than upscaling one it has not.
     */
    public fun bestSize(
        available: List<Size>,
        targetWidth: Int,
        targetHeight: Int,
    ): Size {
        require(available.isNotEmpty()) {
            "The camera reported no YUV_420_888 sizes at all, which means this device cannot " +
                "deliver frames in the one format this module converts from."
        }
        require(targetWidth > 0 && targetHeight > 0) {
            "target size must be positive, got ${targetWidth}x$targetHeight"
        }
        val targetRatio = targetWidth.toDouble() / targetHeight
        val targetPixels = targetWidth * targetHeight

        fun ratioError(size: Size) = kotlin.math.abs(size.width.toDouble() / size.height - targetRatio)

        return available.minWith(
            compareBy(
                { ratioError(it) > RATIO_TOLERANCE },
                // Outside the tolerance the shape still decides first: when nothing matches, the
                // nearest shape beats the nearest area, or a 16:9 request falls to a 4:3 size of
                // similar area while a 16:10 one was on offer. Within it, every match ties here.
                { if (ratioError(it) > RATIO_TOLERANCE) ratioError(it) else 0.0 },
                // Within the tolerance, prefer at-or-under the request over larger.
                { it.pixels > targetPixels },
                { kotlin.math.abs(it.pixels - targetPixels) },
            ),
        )
    }

    /** How far from the request an aspect ratio may be before it counts as a different shape. */
    private const val RATIO_TOLERANCE = 0.05

    /**
     * How many degrees to rotate a captured frame so it is upright for the viewer.
     *
     * The three inputs do different jobs and are routinely conflated:
     *
     *  * [sensorOrientation] is fixed in the hardware — how the sensor is mounted relative to the
     *    device's *natural* orientation, which is not necessarily portrait. It never changes.
     *  * [deviceRotation] is how far the user has turned the device from natural, in degrees.
     *  * [facing] matters because a front camera is mirrored, so its correction runs the other
     *    way. Getting this wrong gives an image that is upright when held one way and upside down
     *    when held the other — the classic symptom.
     */
    public fun rotationDegrees(
        sensorOrientation: Int,
        deviceRotation: Int,
        facing: Facing,
    ): Int {
        require(deviceRotation % 90 == 0) { "deviceRotation must be a multiple of 90, got $deviceRotation" }
        val normalised = ((deviceRotation % 360) + 360) % 360
        return if (facing == Facing.FRONT) {
            // Mirrored: the sensor and the screen turn in opposite directions.
            (sensorOrientation + normalised) % 360
        } else {
            (sensorOrientation - normalised + 360) % 360
        }
    }

    /**
     * The device rotation to use for a physical orientation reading, or [current] to keep it.
     *
     * [orientationDegrees] is what `OrientationEventListener` reports: how far the device is turned
     * *clockwise* from its natural orientation, 0 to 359, or -1 when it is lying flat and names no
     * way up. The result is in [rotationDegrees]' convention — `Display.getRotation()` times 90,
     * which turns the other way — so a phone turned 90° clockwise is a device rotation of 270.
     *
     * Two things keep the stream from flickering between orientations:
     *
     * - **Flat keeps the last reading.** Face up on a table names no up; turning the frame there
     *   would be a guess, so the last real orientation stands. The Swift SDK's `Camera` does the
     *   same with face up, face down and unknown.
     * - **A 15° dead band past each 45° boundary.** Rounding to the nearest quadrant alone toggles
     *   every frame for a phone held near the diagonal, so a reading only moves the rotation once
     *   it is within 30° of the new quadrant's centre.
     */
    public fun deviceRotationFor(
        orientationDegrees: Int,
        current: Int,
    ): Int {
        if (orientationDegrees < 0) return current
        val clockwise = orientationDegrees % 360
        val nearest = ((clockwise + 45) / 90 % 4) * 90
        val rotation = (360 - nearest) % 360
        if (rotation == current) return current
        // Distance from the candidate quadrant's centre, across the 0/360 seam.
        val offset = Math.abs(clockwise - nearest).let { minOf(it, 360 - it) }
        return if (offset <= ORIENTATION_HYSTERESIS) rotation else current
    }

    private const val ORIENTATION_HYSTERESIS = 30

    /** Whether [rotationDegrees] swaps the frame's width and height. */
    public fun swapsDimensions(rotation: Int): Boolean = rotation == 90 || rotation == 270

    /**
     * Rotate a BGRA frame clockwise by [degrees] into [out], and return the rotated size.
     *
     * Whole pixels are moved through `IntBuffer` views, so the byte order inside a pixel is
     * preserved and no channel is touched. [source] is read from its position 0 and [out] is
     * written from 0 and left rewound, ready to push; neither buffer's position is otherwise
     * relied on. [out] must not be [source] — a rotation in place overwrites pixels it has not
     * read yet.
     *
     * Here rather than inside the camera wiring for the reason the rest of this object is: the
     * index arithmetic is where a sideways or upside-down image comes from, and it is testable on
     * a JVM only if no Android class is in the way.
     */
    public fun rotateBgra(
        source: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        degrees: Int,
        out: java.nio.ByteBuffer,
    ): Size {
        require(degrees == 0 || degrees == 90 || degrees == 180 || degrees == 270) {
            "degrees must be 0, 90, 180 or 270, got $degrees"
        }
        require(width > 0 && height > 0) { "frame size must be positive, got ${width}x$height" }
        val bytes = width * height * 4
        require(source.capacity() >= bytes && out.capacity() >= bytes) {
            "a ${width}x$height BGRA frame needs $bytes bytes; source holds ${source.capacity()} " +
                "and out holds ${out.capacity()}"
        }
        val swap = swapsDimensions(degrees)
        val outWidth = if (swap) height else width
        val outHeight = if (swap) width else height
        // Duplicates, so the caller's positions and limits are left alone; native order on both
        // sides, because only whole pixels move and a pixel read and written in the same order is
        // the same four bytes. Cleared through `Buffer`, not `ByteBuffer`: the covariant
        // `ByteBuffer.clear()` does not exist below API 33, and calling it there is a
        // NoSuchMethodError at run time that compiles cleanly against a newer SDK.
        val src = intView(source)
        val dst = intView(out)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val target =
                    when (degrees) {
                        90 -> x * outWidth + (height - 1 - y)
                        180 -> (height - 1 - y) * outWidth + (width - 1 - x)
                        270 -> (width - 1 - x) * outWidth + y
                        else -> row + x
                    }
                dst.put(target, src.get(row + x))
            }
        }
        (out as java.nio.Buffer).clear()
        (out as java.nio.Buffer).limit(bytes)
        return Size(outWidth, outHeight)
    }

    private fun intView(bytes: java.nio.ByteBuffer): java.nio.IntBuffer {
        val view = bytes.duplicate().order(java.nio.ByteOrder.nativeOrder())
        (view as java.nio.Buffer).clear()
        return view.asIntBuffer()
    }
}
