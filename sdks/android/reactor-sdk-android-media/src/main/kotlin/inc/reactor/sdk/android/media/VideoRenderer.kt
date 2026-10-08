package inc.reactor.sdk.android.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import inc.reactor.sdk.android.VideoFrame
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A view that draws the frames a track delivers.
 *
 * A `SurfaceView` rather than an `ImageView`, and the difference is the whole reason this exists.
 * Drawing a stream into an ImageView means mutating a Bitmap the UI thread may be reading, which
 * tears — the examples' own renderer says so and double-buffers to make it rare. A SurfaceView
 * has a surface the renderer locks, so the frame is either fully drawn or not drawn, with no
 * window in between.
 *
 * ### Threading
 *
 * [render] is meant to be called **from the FFI's delivery thread**, straight out of
 * `Track.onFrame`. It draws there: `lockCanvas` is designed to be called off the UI thread, and
 * posting to the main thread instead is what turns a bounded frame drop into unbounded latency.
 *
 * ### Scaling
 *
 * Letterboxed, preserving aspect ratio. A stream whose shape does not match the view is bordered
 * rather than stretched, because a stretched face is a bug report and a border is not.
 */
public class VideoRenderer
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : SurfaceView(context, attrs) {
        private val lock = ReentrantLock()
        private var bitmap: Bitmap? = null
        private var pixels = IntArray(0)
        private var frameWidth = 0
        private var frameHeight = 0

        @Volatile
        private var surfaceReady = false

        private val paint =
            Paint().apply {
                isFilterBitmap = true
                isAntiAlias = true
            }

        /**
         * How far to rotate frames before drawing, in degrees.
         *
         * Set it from [CameraCapture.rotation] when rendering a local preview. Zero for a remote
         * stream, which arrives already upright.
         */
        public var rotationDegrees: Int = 0

        /** Mirror horizontally. What a front-camera preview wants, and nothing else does. */
        public var mirror: Boolean = false

        init {
            holder.addCallback(
                object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        surfaceReady = true
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int,
                    ) = Unit

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        // Before the surface actually goes: a lockCanvas racing destruction throws
                        // from whichever thread happened to be mid-frame.
                        surfaceReady = false
                    }
                },
            )
        }

        /** Draw one frame. Safe to call before the surface exists — the frame is dropped. */
        public fun render(frame: VideoFrame) {
            render(frame.pixels, frame.width, frame.height)
        }

        /**
         * Draw raw BGRA. The buffer is read within the call and never retained.
         *
         * Public so a local camera preview can go straight from [CameraCapture] to the screen
         * without first becoming a [VideoFrame] it never was.
         */
        public fun render(
            bgra: ByteBuffer,
            width: Int,
            height: Int,
        ) {
            if (!surfaceReady || width <= 0 || height <= 0) return
            lock.withLock {
                // Keyed on both dimensions, not on their product: 640x480 and 480x640 have the
                // same area and a different shape, and reusing across that writes out of bounds.
                if (width != frameWidth || height != frameHeight) {
                    frameWidth = width
                    frameHeight = height
                    pixels = IntArray(width * height)
                    bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                }
                val target = bitmap ?: return
                toArgb(bgra, pixels)
                target.setPixels(pixels, 0, width, 0, 0, width, height)

                val canvas = runCatching { holder.lockCanvas() }.getOrNull() ?: return
                try {
                    draw(canvas, target)
                } finally {
                    runCatching { holder.unlockCanvasAndPost(canvas) }
                }
            }
        }

        private fun draw(
            canvas: Canvas,
            frame: Bitmap,
        ) {
            canvas.drawColor(android.graphics.Color.BLACK)

            val rotated = CameraSelection.swapsDimensions(rotationDegrees)
            val sourceWidth = if (rotated) frame.height else frame.width
            val sourceHeight = if (rotated) frame.width else frame.height

            // Letterbox: the smaller scale, so the whole frame fits and the remainder is border.
            val scale = minOf(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)

            val matrix =
                Matrix().apply {
                    postTranslate(-frame.width / 2f, -frame.height / 2f)
                    if (mirror) postScale(-1f, 1f)
                    postRotate(rotationDegrees.toFloat())
                    postScale(scale, scale)
                    postTranslate(width / 2f, height / 2f)
                }
            canvas.drawBitmap(frame, matrix, paint)
        }

        private fun toArgb(
            bgra: ByteBuffer,
            out: IntArray,
        ) {
            val base = bgra.position()
            for (i in out.indices) {
                val at = base + i * 4
                val b = bgra.get(at).toInt() and 0xFF
                val g = bgra.get(at + 1).toInt() and 0xFF
                val r = bgra.get(at + 2).toInt() and 0xFF
                out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        /** Release the backing bitmap. The view is unusable afterwards without a new frame. */
        public fun release() {
            lock.withLock {
                bitmap = null
                pixels = IntArray(0)
                frameWidth = 0
                frameHeight = 0
            }
        }
    }
