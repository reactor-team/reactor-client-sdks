package inc.reactor.examples.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import inc.reactor.sdk.VideoFrame
import java.util.concurrent.atomic.AtomicReference

/** Main-thread preview for the Android examples; newest frame wins. */
class FrameView(context: Context) : View(context) {
    private val pending = AtomicReference<Bitmap?>()
    fun show(frame: VideoFrame) {
        val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(frame.width * frame.height)
        var i = 0
        for (p in pixels.indices) {
            val b = frame.pixels[i++].toInt() and 255
            val g = frame.pixels[i++].toInt() and 255
            val r = frame.pixels[i++].toInt() and 255
            val a = frame.pixels[i++].toInt() and 255
            pixels[p] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        bitmap.setPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
        pending.getAndSet(bitmap)?.recycle()
        postInvalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        pending.get()?.let { canvas.drawBitmap(it, null, viewBounds, null) }
    }
    private val viewBounds get() = android.graphics.Rect(0, 0, width, height)
}
