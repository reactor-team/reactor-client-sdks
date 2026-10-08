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

        return available.minWith(
            compareBy(
                { kotlin.math.abs(it.width.toDouble() / it.height - targetRatio) > RATIO_TOLERANCE },
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

    /** Whether [rotationDegrees] swaps the frame's width and height. */
    public fun swapsDimensions(rotation: Int): Boolean = rotation == 90 || rotation == 270
}
