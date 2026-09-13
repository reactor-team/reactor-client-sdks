package inc.reactor.sdk.internal

import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.ImageWriter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import inc.reactor.sdk.InvalidStateError
import inc.reactor.sdk.Reactor
import inc.reactor.sdk.android.media.CameraImageSink
import inc.reactor.sdk.android.media.ForegroundMedia
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidCameraTest {
    @Test fun destroyedForegroundScopeDisconnectsAndClosesItsNativeClient() =
        runBlocking<Unit> {
            val fake = MediaSendTest()
            fake.resetSend()
            val owner =
                object : LifecycleOwner {
                    val registry = LifecycleRegistry(this)
                    override val lifecycle: Lifecycle get() = registry
                }
            val ready = CompletableDeferred<Reactor>()
            val media =
                ForegroundMedia(owner.lifecycle, { ready.completeExceptionally(it) }) {
                    val client = ownClient(Reactor("model", local = true))
                    client.connect()
                    ready.complete(client)
                }
            try {
                withContext(Dispatchers.Main) {
                    owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                    media.start()
                }
                val client = withTimeout(5000) { ready.await() }
                withContext(Dispatchers.Main) { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY) }
                withTimeout(5000) { media.awaitClosed() }
                assertTrue(runCatching { client.status }.exceptionOrNull() is InvalidStateError)
            } finally {
                media.close()
                withTimeout(5000) { media.awaitClosed() }
            }
        }

    @Test fun realImageConvertsAndClosesAcrossNativeSendAndShutdown() =
        runBlocking<Unit> {
            val fake = MediaSendTest()
            fake.resetSend()
            val client = Reactor("model", local = true)
            client.connect()
            val track = client.track("input").publish()
            val sink = CameraImageSink(track)
            val reader = ImageReader.newInstance(2, 2, PixelFormat.RGBA_8888, 2)
            val writer = ImageWriter.newInstance(reader.surface, 2)
            try {
                val image = writer.dequeueInputImage()
                val plane = image.planes[0]
                for (y in 0..1) {
                    for (x in 0..1) {
                        val offset = plane.buffer.position() + y * plane.rowStride + x * plane.pixelStride
                        plane.buffer.put(offset, 1)
                        plane.buffer.put(offset + 1, 2)
                        plane.buffer.put(offset + 2, 3)
                        plane.buffer.put(offset + 3, -1)
                    }
                }
                withContext(Dispatchers.IO) { sink.consume(image) }
                assertEquals(listOf<Byte>(3, 2, 1, -1), fake.videoBytes().toList().take(4))
                assertTrue(runCatching { image.planes }.isFailure)
                sink.close()
                withTimeout(2000) { sink.awaitClosed() }
                val late = writer.dequeueInputImage()
                withContext(Dispatchers.IO) { sink.consume(late) }
                assertTrue(runCatching { late.planes }.isFailure)
                assertEquals(1L, fake.values()[0])
            } finally {
                sink.close()
                sink.awaitClosed()
                writer.close()
                reader.close()
                client.close()
            }
        }

    @Test fun mainThreadRefusalStillClosesAcquiredImage() =
        runBlocking<Unit> {
            val fake = MediaSendTest()
            fake.resetSend()
            val client = Reactor("model", local = true)
            client.connect()
            val sink = CameraImageSink(client.track("input"))
            val reader = ImageReader.newInstance(2, 2, PixelFormat.RGBA_8888, 2)
            val writer = ImageWriter.newInstance(reader.surface, 2)
            try {
                val image = writer.dequeueInputImage()
                val error = withContext(Dispatchers.Main) { runCatching { sink.consume(image) }.exceptionOrNull() }
                assertTrue(error is IllegalStateException)
                assertTrue(runCatching { image.planes }.isFailure)
            } finally {
                sink.close()
                sink.awaitClosed()
                writer.close()
                reader.close()
                client.close()
            }
        }
}
