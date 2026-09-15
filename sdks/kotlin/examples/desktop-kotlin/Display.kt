package inc.reactor.examples.desktop

import inc.reactor.sdk.VideoFrame
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.image.BufferedImage
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import java.util.concurrent.atomic.AtomicReference

/** Optional desktop preview. Enable with REACTOR_SHOW=1; newest frame wins. */
class Display(title: String) {
    private data class Pending(val frame: VideoFrame)
    private val pending = AtomicReference<Pending?>()
    private val enabled = System.getenv("REACTOR_SHOW")?.let { it.isNotBlank() && it != "0" } == true
    private var panel: Preview? = null

    fun show(frame: VideoFrame) { if (enabled) pending.set(Pending(frame)) }

    fun pump() {
        if (!enabled) return
        val next = pending.getAndSet(null) ?: return
        SwingUtilities.invokeLater {
            val view = panel ?: Preview(title).also { panel = it }
            view.update(next.frame)
        }
    }

    private class Preview(title: String) : JPanel() {
        private var image: BufferedImage? = null
        init {
            background = Color.BLACK
            val window = JFrame(title)
            window.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
            window.contentPane = this
            preferredSize = Dimension(640, 480)
            window.pack()
            window.setLocationByPlatform(true)
            window.isVisible = true
        }
        fun update(frame: VideoFrame) {
            val out = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
            var i = 0
            for (y in 0 until frame.height) for (x in 0 until frame.width) {
                val b = frame.pixels[i++].toInt() and 255
                val g = frame.pixels[i++].toInt() and 255
                val r = frame.pixels[i++].toInt() and 255
                val a = frame.pixels[i++].toInt() and 255
                out.setRGB(x, y, (a shl 24) or (r shl 16) or (g shl 8) or b)
            }
            image = out
            repaint()
        }
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            image?.let { g.drawImage(it, 0, 0, width, height, null) }
        }
    }
}
