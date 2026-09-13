package inc.reactor.sdk.android.media

import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.os.Looper
import android.os.SystemClock
import inc.reactor.sdk.Track
import inc.reactor.sdk.TrackDirection
import inc.reactor.sdk.TrackKind
import inc.reactor.sdk.VideoFrame
import inc.reactor.sdk.timeMicros
import kotlinx.coroutines.CompletableDeferred
import java.nio.ByteBuffer

enum class YuvColorSpace { BT601_LIMITED, BT709_LIMITED, BT601_FULL }

enum class CameraTimestampSource { REALTIME, UNKNOWN }

internal data class PixelCrop(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

internal data class PixelPlane(
    val buffer: ByteBuffer,
    val rowStride: Int,
    val pixelStride: Int,
) {
    fun sample(
        x: Int,
        y: Int,
        component: Int = 0,
    ): Int {
        require(rowStride > 0 && pixelStride > 0 && x >= 0 && y >= 0)
        val index = buffer.position().toLong() + y.toLong() * rowStride + x.toLong() * pixelStride + component
        require(index >= buffer.position() && index < buffer.limit()) { "Image plane is truncated or has invalid strides" }
        return buffer.get(index.toInt()).toInt() and 255
    }
}

/** Copies crop/planes into owned packed BGRA. Rotation is clockwise, then optional horizontal mirroring.
 * The caller retains Image ownership. YUV conversion requires the source color-space convention.
 */
fun Image.toVideoFrame(
    rotationDegrees: Int = 0,
    mirror: Boolean = false,
    colorSpace: YuvColorSpace = YuvColorSpace.BT601_LIMITED,
): VideoFrame {
    val crop = cropRect
    return convertImage(
        format,
        width,
        height,
        PixelCrop(crop.left, crop.top, crop.right, crop.bottom),
        planes.map { PixelPlane(it.buffer.duplicate(), it.rowStride, it.pixelStride) },
        rotationDegrees,
        mirror,
        colorSpace,
    )
}

internal fun convertImage(
    format: Int,
    width: Int,
    height: Int,
    crop: PixelCrop,
    planes: List<PixelPlane>,
    rotation: Int,
    mirror: Boolean,
    colorSpace: YuvColorSpace,
): VideoFrame {
    require(rotation in setOf(0, 90, 180, 270)) { "Rotation must be 0, 90, 180 or 270 degrees clockwise" }
    require(crop.left >= 0 && crop.top >= 0 && crop.right <= width && crop.bottom <= height)
    val w = crop.right - crop.left
    val h = crop.bottom - crop.top
    require(w > 0 && h > 0 && w.toLong() * h <= Int.MAX_VALUE / 4) { "Image crop exceeds the BGRA buffer limit" }
    require(format == ImageFormat.YUV_420_888 || format == PixelFormat.RGBA_8888) { "Use YUV_420_888 or RGBA_8888 input" }
    require(planes.size == if (format == ImageFormat.YUV_420_888) 3 else 1) { "Unexpected image plane count" }
    if (format == PixelFormat.RGBA_8888) require(planes[0].pixelStride >= 4) { "RGBA pixel stride must contain all four components" }
    val outWidth = if (rotation == 90 || rotation == 270) h else w
    val outHeight = if (rotation == 90 || rotation == 270) w else h
    val coefficients =
        when (colorSpace) {
            YuvColorSpace.BT601_LIMITED -> intArrayOf(409, 100, 208, 516)
            YuvColorSpace.BT709_LIMITED -> intArrayOf(459, 55, 136, 541)
            YuvColorSpace.BT601_FULL -> intArrayOf(359, 88, 183, 454)
        }
    val pixels = ByteArray(w * h * 4)
    for (y in 0 until h) {
        for (x in 0 until w) {
            val sx = crop.left + x
            val sy = crop.top + y
            val red: Int
            val green: Int
            val blue: Int
            val alpha: Int
            if (format == PixelFormat.RGBA_8888) {
                red = planes[0].sample(sx, sy, 0)
                green = planes[0].sample(sx, sy, 1)
                blue = planes[0].sample(sx, sy, 2)
                alpha = planes[0].sample(sx, sy, 3)
            } else {
                val yy = planes[0].sample(sx, sy)
                val u = planes[1].sample(sx / 2, sy / 2) - 128
                val v = planes[2].sample(sx / 2, sy / 2) - 128
                val c = if (colorSpace == YuvColorSpace.BT601_FULL) yy * 256 else maxOf(0, yy - 16) * 298
                red = ((c + coefficients[0] * v + 128) shr 8).coerceIn(0, 255)
                green = ((c - coefficients[1] * u - coefficients[2] * v + 128) shr 8).coerceIn(0, 255)
                blue = ((c + coefficients[3] * u + 128) shr 8).coerceIn(0, 255)
                alpha = 255
            }
            var dx =
                when (rotation) {
                    90 -> h - 1 - y
                    180 -> w - 1 - x
                    270 -> y
                    else -> x
                }
            val dy =
                when (rotation) {
                    90 -> x
                    180 -> h - 1 - y
                    270 -> w - 1 - x
                    else -> y
                }
            if (mirror) dx = outWidth - 1 - dx
            val target = (dy * outWidth + dx) * 4
            pixels[target] = blue.toByte()
            pixels[target + 1] = green.toByte()
            pixels[target + 2] = red.toByte()
            pixels[target + 3] =
                alpha.toByte()
        }
    }
    return VideoFrame(pixels, outWidth, outHeight)
}

internal fun cameraTime(
    timestamp: Long,
    source: CameraTimestampSource,
    realtimeNanos: Long,
    engineMicros: Long,
): Long {
    require(engineMicros >= 0)
    if (source == CameraTimestampSource.UNKNOWN) return engineMicros
    require(timestamp >= 0 && realtimeNanos >= timestamp) {
        "Camera REALTIME timestamp is in the future or invalid; check the sensor timestamp source"
    }
    return (engineMicros - (realtimeNanos - timestamp) / 1000).coerceAtLeast(0)
}

/** Register on an ImageReader using a background Handler. Owns every acquired Image until finally-close.
 * Camera creation, CAMERA permission and capture-session shutdown remain explicit application work.
 */
class CameraImageSink(
    private val track: Track,
    private val rotationDegrees: Int = 0,
    private val mirror: Boolean = false,
    private val timestampSource: CameraTimestampSource = CameraTimestampSource.UNKNOWN,
    private val colorSpace: YuvColorSpace = YuvColorSpace.BT601_LIMITED,
    private val onFailure: (Throwable) -> Unit = { System.err.println("Reactor camera: $it") },
) : ImageReader.OnImageAvailableListener,
    AndroidMediaResource {
    private val lock = Any()
    private var closing = false
    private var inFlight = 0
    private val closed = CompletableDeferred<Unit>()

    init {
        require(track.kind == TrackKind.VIDEO && track.direction == TrackDirection.SENDONLY) { "Camera requires a sendonly video track" }
        require(rotationDegrees in setOf(0, 90, 180, 270))
    }

    override fun onImageAvailable(reader: ImageReader) {
        try {
            reader.acquireLatestImage()?.let(::consume)
        } catch (failure: Throwable) {
            val report = synchronized(lock) { !closing }
            close()
            if (report) runCatching { onFailure(failure) }
        }
    }

    fun consume(image: Image) {
        val accepted =
            synchronized(lock) {
                if (closing) {
                    false
                } else {
                    inFlight++
                    true
                }
            }
        try {
            if (!accepted) return
            check(Looper.myLooper() != Looper.getMainLooper()) { "Convert camera images on a background Handler, not the main thread" }
            val captureTime = cameraTime(image.timestamp, timestampSource, SystemClock.elapsedRealtimeNanos(), timeMicros())
            val frame = image.toVideoFrame(rotationDegrees, mirror, colorSpace)
            if (synchronized(lock) { !closing }) track.pushFrame(frame, captureTime)
        } finally {
            try {
                image.close()
            } finally {
                if (accepted) {
                    synchronized(lock) {
                        inFlight--
                        if (closing && inFlight == 0) closed.complete(Unit)
                    }
                }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            closing = true
            if (inFlight == 0) closed.complete(Unit)
        }
    }

    override suspend fun awaitClosed() {
        closed.await()
    }
}
