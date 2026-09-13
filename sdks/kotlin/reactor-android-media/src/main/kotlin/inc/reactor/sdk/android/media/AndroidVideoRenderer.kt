package inc.reactor.sdk.android.media

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import inc.reactor.sdk.VideoFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Optional ImageView renderer: one pending input and bitmap, with conversion off the UI thread. */
class AndroidVideoRenderer(
    view: ImageView,
    private val onFailure: (Throwable) -> Unit = { System.err.println("Reactor render: $it") },
) : AndroidMediaResource {
    private val target = WeakReference(view)
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val frames = Channel<VideoFrame>(Channel.CONFLATED)
    private val bitmap = AtomicReference<Bitmap?>()
    private val scheduled = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private val closed = CompletableDeferred<Unit>()
    private var displayed: Bitmap? = null // Main-thread only.
    private val worker =
        scope.launch(start = CoroutineStart.LAZY) {
            try {
                for (frame in frames) {
                    val colors =
                        IntArray(frame.width * frame.height) { index ->
                            val p = index * 4
                            ((frame.pixels[p + 3].toInt() and 255) shl 24) or ((frame.pixels[p + 2].toInt() and 255) shl 16) or
                                ((frame.pixels[p + 1].toInt() and 255) shl 8) or (frame.pixels[p].toInt() and 255)
                        }
                    val converted = Bitmap.createBitmap(colors, frame.width, frame.height, Bitmap.Config.ARGB_8888)
                    ensureActive()
                    bitmap.set(converted)
                    schedule()
                }
            } catch (failure: Throwable) {
                if (!closing.get()) runCatching { onFailure(failure) }
                close()
            } finally {
                bitmap.set(null)
            }
        }

    fun show(frame: VideoFrame) {
        if (closing.get()) return
        frames.trySend(frame.copy(pixels = frame.pixels.copyOf(), userData = null))
        worker.start()
    }

    private fun schedule() {
        if (!scheduled.compareAndSet(false, true)) return
        handler.post {
            try {
                val next = bitmap.getAndSet(null)
                if (!closing.get() && next != null) {
                    target.get()?.setImageBitmap(next)
                    displayed = next
                }
            } catch (failure: Throwable) {
                runCatching { onFailure(failure) }
                close()
            } finally {
                scheduled.set(false)
                if (!closing.get() && bitmap.get() != null) schedule()
            }
        }
    }

    override fun close() {
        if (!closing.compareAndSet(false, true)) return
        frames.cancel()
        worker.cancel()
        worker.invokeOnCompletion {
            handler.post {
                val view = target.get()
                runCatching {
                    if (displayed != null && (view?.drawable as? BitmapDrawable)?.bitmap === displayed) view?.setImageDrawable(null)
                }.onFailure { runCatching { onFailure(it) } }
                displayed = null
                bitmap.set(null)
                target.clear()
                closed.complete(Unit)
                scope.cancel()
            }
        }
    }

    override suspend fun awaitClosed() {
        closed.await()
    }
}
