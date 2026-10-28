package inc.reactor.sdk.android.examples

import android.graphics.Bitmap
import android.widget.ImageView
import inc.reactor.sdk.android.VideoFrame
import java.nio.ByteBuffer

/**
 * Optional on-screen rendering, in the one file every example shares.
 *
 * Opt-in because a frame count is what the examples assert on and a window is what makes the
 * result trustworthy — but only the examples that benefit should pay for it, and a headless run
 * has to stay possible.
 *
 * BGRA to ARGB_8888 is a byte-order swap, done here rather than in the SDK: the SDK's job is to
 * hand over what the wire carries, and every renderer wants something slightly different.
 */
public class FrameSink(
    private val view: ImageView,
) {
    /**
     * Two bitmaps, written and displayed alternately.
     *
     * One would be wrong: `setPixels` runs on the FFI thread while the previous `post` may still
     * have that same Bitmap attached to the ImageView, so the UI can draw a half-converted frame.
     * With two, a tear needs the UI thread to be a whole frame behind rather than merely late.
     *
     * It is not a guarantee, and this is the honest limit of rendering a video stream into an
     * ImageView at all — a renderer that must never tear wants a SurfaceView and its own
     * synchronisation. This is an example teaching the SDK's frame callback, so it uses the
     * simplest widget that works and says where the line is.
     */
    private var buffers: Array<Bitmap>? = null
    private var next = 0
    private var pixels: IntArray = IntArray(0)
    private var width = 0
    private var height = 0

    /**
     * Draw one frame.
     *
     * Called **on the FFI's delivery thread**, so the conversion happens there and only the
     * `setImageBitmap` is posted to the UI thread. Blocking here slows the stream and nothing
     * else, which is the bargain the inline callback makes.
     */
    public fun render(frame: VideoFrame) {
        // Keyed on both dimensions, not on their product. 640x480 and 480x640 have the same area,
        // so an area key reuses a bitmap of the wrong shape and setPixels then writes past its
        // end — an IllegalStateException on a resolution change that happens to be a transpose.
        if (frame.width != width || frame.height != height) {
            width = frame.width
            height = frame.height
            pixels = IntArray(width * height)
            buffers =
                Array(2) { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
            next = 0
        }
        val target = buffers?.get(next) ?: return
        next = (next + 1) % 2

        toArgb(frame.pixels, pixels)
        target.setPixels(pixels, 0, width, 0, 0, width, height)
        view.post { view.setImageBitmap(target) }
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
}
