package inc.reactor.sdk.android.media

import inc.reactor.sdk.AudioFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AndroidAudioTest {
    private val format = AndroidAudioFormat()

    @Test fun constructionAndDeniedPermissionOpenNoHardware() =
        runBlocking<Unit> {
            var attempts = 0
            val backend =
                object : AndroidAudioBackend {
                    override fun capture(format: AndroidAudioFormat): AndroidCapture {
                        attempts++
                        throw SecurityException("RECORD_AUDIO denied")
                    }

                    override fun playback(format: AndroidAudioFormat): AndroidPlayback = error("unused")
                }
            val mic = AndroidMicrophone(format, format, backend) {}
            assertEquals(0, attempts)
            assertFalse(mic.running)
            assertTrue(runCatching { mic.startCapture {} }.exceptionOrNull() is SecurityException)
            withTimeout(2000) { mic.awaitClosed() }
            assertEquals(1, attempts)
        }

    @Test fun closeReturnsWithoutWaitingForReadAndDropsTheReleasedCapture() =
        runBlocking<Unit> {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val closed = AtomicInteger()
            val delivered = AtomicInteger()
            val backend =
                object : AndroidAudioBackend {
                    override fun playback(format: AndroidAudioFormat): AndroidPlayback = error("unused")

                    override fun capture(format: AndroidAudioFormat): AndroidCapture =
                        object : AndroidCapture {
                            override fun read(
                                samples: ShortArray,
                                offset: Int,
                                count: Int,
                            ): Int {
                                entered.countDown()
                                check(release.await(3, TimeUnit.SECONDS))
                                return count
                            }

                            override fun close() {
                                closed.incrementAndGet()
                            }
                        }
                }
            val mic = AndroidMicrophone(format, format, backend) {}
            mic.startCapture { delivered.incrementAndGet() }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val before = System.nanoTime()
            mic.close()
            assertTrue(System.nanoTime() - before < 500_000_000)
            release.countDown()
            withTimeout(2000) { mic.awaitClosed() }
            assertEquals(0, delivered.get())
            assertEquals(1, closed.get())
        }

    @Test fun permissionRevocationAndThrowingReporterReleaseCapture() =
        runBlocking<Unit> {
            val failed = CompletableDeferred<Unit>()
            val closed = AtomicInteger()
            val backend =
                object : AndroidAudioBackend {
                    override fun playback(format: AndroidAudioFormat): AndroidPlayback = error("unused")

                    override fun capture(format: AndroidAudioFormat): AndroidCapture =
                        object : AndroidCapture {
                            override fun read(
                                samples: ShortArray,
                                offset: Int,
                                count: Int,
                            ): Int = throw SecurityException("revoked")

                            override fun close() {
                                closed.incrementAndGet()
                            }
                        }
                }
            val mic =
                AndroidMicrophone(format, format, backend) {
                    failed.complete(Unit)
                    error("reporter")
                }
            mic.startCapture {}
            withTimeout(2000) {
                failed.await()
                mic.awaitClosed()
            }
            assertEquals(1, closed.get())
            assertFalse(mic.running)
        }

    @Test fun playbackHandlesZeroPartialAndDeadObjectWithoutBlockingSubmit() =
        runBlocking<Unit> {
            val writes = AtomicInteger()
            val failed = CompletableDeferred<Unit>()
            val closed = AtomicInteger()
            val backend =
                object : AndroidAudioBackend {
                    override fun capture(format: AndroidAudioFormat): AndroidCapture = error("unused")

                    override fun playback(format: AndroidAudioFormat): AndroidPlayback =
                        object : AndroidPlayback {
                            override fun write(
                                samples: ShortArray,
                                offset: Int,
                                count: Int,
                            ): Int =
                                when (writes.incrementAndGet()) {
                                    1 -> 0
                                    2 -> 1
                                    else -> -6
                                }

                            override fun close() {
                                closed.incrementAndGet()
                            }
                        }
                }
            val speaker = AndroidSpeaker(format, backend) { failed.complete(Unit) }
            speaker.startPlayback()
            speaker.submit(AudioFrame(ShortArray(480), 48000, 1))
            withTimeout(2000) {
                failed.await()
                speaker.awaitClosed()
            }
            assertEquals(3, writes.get())
            assertEquals(1, closed.get())
        }

    @Test fun playbackQueueDropsOldBlocksAndOwnsInput() =
        runBlocking<Unit> {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val first = CompletableDeferred<Short>()
            val writes = AtomicInteger()
            val backend =
                object : AndroidAudioBackend {
                    override fun capture(format: AndroidAudioFormat): AndroidCapture = error("unused")

                    override fun playback(format: AndroidAudioFormat): AndroidPlayback =
                        object : AndroidPlayback {
                            override fun write(
                                samples: ShortArray,
                                offset: Int,
                                count: Int,
                            ): Int {
                                if (writes.incrementAndGet() ==
                                    1
                                ) {
                                    entered.countDown()
                                    check(release.await(3, TimeUnit.SECONDS))
                                    first.complete(samples[0])
                                }
                                return count
                            }

                            override fun close() = Unit
                        }
                }
            val speaker = AndroidSpeaker(format, backend) {}
            speaker.startPlayback()
            val input = ShortArray(480) { 7 }
            speaker.submit(AudioFrame(input, 48000, 1))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            input.fill(9)
            repeat(50) { speaker.submit(AudioFrame(ShortArray(480), 48000, 1)) }
            assertTrue(runCatching { speaker.submit(AudioFrame(ShortArray(48000), 48000, 1)) }.isFailure)
            release.countDown()
            assertEquals(7.toShort(), withTimeout(2000) { first.await() })
            speaker.close()
            withTimeout(2000) { speaker.awaitClosed() }
            assertTrue(writes.get() <= 5)
        }
}
