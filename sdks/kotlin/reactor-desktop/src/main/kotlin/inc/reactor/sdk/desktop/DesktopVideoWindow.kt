package inc.reactor.sdk.desktop

import inc.reactor.sdk.VideoFrame
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.ImageIcon
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.SwingUtilities

/** Optional visual check for examples. Retains only the latest frame; all Swing work runs on the EDT. */
class DesktopVideoWindow(
    title: String = "Reactor",
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val scheduled = AtomicBoolean(false)
    private val latest = AtomicReference<VideoFrame?>()
    private lateinit var window: JFrame
    private lateinit var label: JLabel

    init {
        check(!GraphicsEnvironment.isHeadless()) { "Desktop display requires a graphical session" }
        onEdt {
            label = JLabel()
            window =
                JFrame(title).apply {
                    defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                    contentPane.add(label)
                    addWindowListener(
                        object : java.awt.event.WindowAdapter() {
                            override fun windowClosed(event: java.awt.event.WindowEvent) {
                                close()
                            }
                        },
                    )
                }
        }
    }

    fun show(frame: VideoFrame) {
        if (closed.get()) return
        latest.set(frame.copy(pixels = frame.pixels.copyOf(), userData = null))
        schedule()
    }

    private fun schedule() {
        if (!scheduled.compareAndSet(false, true)) return
        SwingUtilities.invokeLater {
            try {
                val frame = latest.getAndSet(null)
                if (!closed.get() && frame != null) {
                    val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_ARGB)
                    image.setRGB(0, 0, frame.width, frame.height, bgraToArgb(frame.pixels), 0, frame.width)
                    label.icon = ImageIcon(image)
                    window.pack()
                    window.isVisible = true
                }
            } finally {
                scheduled.set(false)
                if (!closed.get() && latest.get() != null) schedule()
                if (closed.get()) latest.set(null)
            }
        }
    }

    override fun close() {
        if (closed.getAndSet(true)) return
        latest.set(null)
        SwingUtilities.invokeLater { window.dispose() }
    }
}

private fun onEdt(action: () -> Unit) {
    if (SwingUtilities.isEventDispatchThread()) action() else SwingUtilities.invokeAndWait(action)
}

internal fun bgraToArgb(bytes: ByteArray): IntArray {
    require(bytes.size % 4 == 0)
    return IntArray(bytes.size / 4) { i ->
        val p = i * 4
        ((bytes[p + 3].toInt() and 255) shl 24) or ((bytes[p + 2].toInt() and 255) shl 16) or
            ((bytes[p + 1].toInt() and 255) shl 8) or (bytes[p].toInt() and 255)
    }
}
