package inc.reactor.sdk.internal

import inc.reactor.sdk.AbortedError
import inc.reactor.sdk.BadRequestError
import inc.reactor.sdk.DecodeFailedError
import inc.reactor.sdk.Reactor
import inc.reactor.sdk.TokenProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RecordingTest {
    init {
        val library = System.getProperty("reactor.jni.test.library")
        if (library == null) System.loadLibrary("reactor_jni_test") else System.load(library)
    }

    external fun configure(mode: Int)

    external fun waitForDownload(): Boolean

    external fun progress()

    external fun finishDownloads()

    external fun timeout(): Double

    external fun token(): ByteArray

    @Test fun clipAndFullRecordingDecodeStrictly() =
        runBlocking<Unit> {
            configure(0)
            val client = Reactor("model", local = true)
            client.connect()
            try {
                val clip = client.requestClip(3.25)
                assertEquals(1.25, clip.startMarker, 0.0)
                assertEquals(123456.75, clip.predictedReadyAtMillis, 0.0)
                assertEquals("session", client.requestRecording().sessionId)
                for (duration in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0)) {
                    assertTrue(runCatching { client.requestClip(duration) }.exceptionOrNull() is IllegalArgumentException)
                }
                configure(2)
                assertTrue(runCatching { client.requestClip(1.0) }.exceptionOrNull() is BadRequestError)
                configure(3)
                assertTrue(runCatching { client.requestRecording() }.exceptionOrNull() is DecodeFailedError)
            } finally {
                client.close()
            }
        }

    @Test fun downloadCarriesCredentialsTimeoutAndReportsProgressDespiteThrowingHandler() =
        runBlocking<Unit> {
            configure(0)
            val failed = CompletableDeferred<Unit>()
            val client = Reactor("model", TokenProvider { "test-token" }, onHandlerFailure = { failed.complete(Unit) })
            val file = Files.createTempFile("reactor-download-", ".mp4").toFile()
            client.connect()
            try {
                val clip = client.requestRecording()
                val pending =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        client.download(clip, file, Double.POSITIVE_INFINITY) { throw IllegalStateException("handler bug") }
                    }
                assertTrue(waitForDownload())
                progress()
                withTimeout(5000) { failed.await() }
                assertEquals("test-token", token().decodeToString())
                assertEquals(Double.POSITIVE_INFINITY, timeout(), 0.0)
                finishDownloads()
                val result = withTimeout(5000) { pending.await() }
                assertEquals(file, result.file)
                assertEquals(12uL, result.bytes)
                assertEquals(3u, result.segments)
                assertEquals("init|one|two", file.readText())
                assertTrue(runCatching { client.download(clip, file, Double.NaN) }.exceptionOrNull() is IllegalArgumentException)
            } finally {
                client.close()
                finishDownloads()
                file.delete()
            }
        }

    @Test fun destroyZeroStillAllowsDetachedProgressAndCompletion() =
        runBlocking<Unit> {
            configure(0)
            val client = Reactor("model", local = true)
            val file = Files.createTempFile("reactor-late-", ".mp4").toFile()
            client.connect()
            try {
                val clip = client.requestRecording()
                val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { client.download(clip, file) } }
                assertTrue(waitForDownload())
                client.close()
                assertTrue(withTimeout(5000) { pending.await() }.exceptionOrNull() is AbortedError)
                assertTrue(file.exists())
                progress() // destroy returned 0; the native ticket must still be valid.
                finishDownloads()
                assertEquals("init|one|two", file.readText())
            } finally {
                client.close()
                finishDownloads()
                file.delete()
            }
        }

    @Test fun cancellationDoesNotRemoveOutputAndLateNativeErrorsSettleSafely() =
        runBlocking<Unit> {
            configure(0)
            val client = Reactor("model", local = true)
            val file = Files.createTempFile("reactor-cancel-", ".mp4").toFile()
            client.connect()
            try {
                val clip = client.requestRecording()
                val pending = async(start = CoroutineStart.UNDISPATCHED) { client.download(clip, file) }
                assertTrue(waitForDownload())
                pending.cancel()
                pending.join()
                progress()
                finishDownloads()
                assertEquals("init|one|two", file.readText())
                for (mode in listOf(2, 3)) {
                    configure(mode)
                    val next = async(start = CoroutineStart.UNDISPATCHED) { runCatching { client.download(clip, file) } }
                    assertTrue(waitForDownload())
                    finishDownloads()
                    val error = withTimeout(5000) { next.await() }.exceptionOrNull()
                    assertTrue(if (mode == 2) error is BadRequestError else error is DecodeFailedError)
                }
            } finally {
                client.close()
                finishDownloads()
                file.delete()
            }
        }
}
