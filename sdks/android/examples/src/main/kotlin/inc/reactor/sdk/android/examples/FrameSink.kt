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
    private var bitmap: Bitmap? = null
    private var pixels: IntArray = IntArray(0)

    /**
     * Draw one frame.
     *
     * Called **on the FFI's delivery thread**, so the conversion happens there and only the
     * `setImageBitmap` is posted to the UI thread. Blocking here slows the stream and nothing
     * else, which is the bargain the inline callback makes.
     */
    public fun render(frame: VideoFrame) {
        val width = frame.width
        val height = frame.height
        if (pixels.size != width * height) {
            pixels = IntArray(width * height)
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        val target = bitmap ?: return
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
