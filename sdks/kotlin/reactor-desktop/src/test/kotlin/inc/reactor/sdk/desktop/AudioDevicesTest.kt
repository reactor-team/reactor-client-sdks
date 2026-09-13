package inc.reactor.sdk.desktop

import inc.reactor.sdk.AudioFrame
import inc.reactor.sdk.Reactor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.AudioFormat

class AudioDevicesTest {
    private val format = DesktopAudioFormat()

    @Test fun constructionOpensNoHardwareAndClosedHelpersCannotStart() {
        val backend = FakeBackend()
        Reactor("model", local = true)
        val mic = DesktopMicrophone(format, backend) {}
        val speaker = DesktopSpeaker(format, backend) {}
        assertEquals(0, backend.opens.get())
        mic.close()
        speaker.close()
        assertTrue(runCatching { mic.startCapture {} }.isFailure)
        assertTrue(runCatching { speaker.startPlayback() }.isFailure)
        assertEquals(0, backend.opens.get())
    }

    @Test fun signedPcmRoundTripsAndDeviceFormatMismatchIsExplicit() {
        val samples = shortArrayOf(Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE)
        assertArrayEquals(byteArrayOf(0, -128, -1, -1, 0, 0, 1, 0, -1, 127), encodePcm(samples))
        assertArrayEquals(samples, decodePcm(encodePcm(samples)))
        assertTrue(runCatching { format.requireMatch(AudioFormat(48000f, 16, 1, true, true)) }.isFailure)
        assertTrue(runCatching { format.requireMatch(AudioFormat(44100f, 16, 1, true, false)) }.isFailure)
        assertTrue(runCatching { DesktopAudioFormat(12345, 1) }.isFailure)
    }

    @Test fun stopUnblocksCaptureAndDiscardsTheBlockReadWhileStopping() {
        val backend = FakeBackend()
        val delivered = AtomicInteger()
        val mic = DesktopMicrophone(format, backend) {}
        mic.startCapture { delivered.incrementAndGet() }
        assertTrue(backend.entered.await(2, TimeUnit.SECONDS))
        mic.close()
        assertEquals(0, delivered.get())
        assertEquals(1, backend.closes.get())
        assertFalse(mic.running)
        mic.close()
        assertEquals(1, backend.closes.get())
    }

    @Test fun captureCanCloseItselfWithoutJoiningItsOwnThread() {
        val backend = FakeBackend(blockCapture = false)
        val done = CountDownLatch(1)
        val mic = DesktopMicrophone(format, backend) {}
        mic.startCapture { frame ->
            assertEquals(480, frame.samplesPerChannel)
            assertEquals(Short.MIN_VALUE, frame.samples[0])
            mic.close()
            done.countDown()
        }
        assertTrue(done.await(2, TimeUnit.SECONDS))
        mic.close()
        assertEquals(1, backend.closes.get())
    }

    @Test fun throwingCaptureHandlerReportsOnceAndStops() {
        val backend = FakeBackend(blockCapture = false)
        val failures = AtomicInteger()
        val done = CountDownLatch(1)
        val mic =
            DesktopMicrophone(format, backend) {
                failures.incrementAndGet()
                done.countDown()
                error("reporter also failed")
            }
        mic.startCapture { error("consumer bug") }
        assertTrue(done.await(2, TimeUnit.SECONDS))
        mic.close()
        assertFalse(mic.running)
        assertEquals(1, failures.get())
        assertEquals(1, backend.closes.get())
    }

    @Test fun playbackOwnsItsPcmAndBoundsQueueWhileDeviceIsBlocked() {
        val backend = FakeBackend()
        val speaker = DesktopSpeaker(format, backend) {}
        speaker.startPlayback()
        val samples = ShortArray(480) { Short.MIN_VALUE }
        speaker.submit(AudioFrame(samples, 48000, 1))
        assertTrue(backend.entered.await(2, TimeUnit.SECONDS))
        samples.fill(1)
        repeat(50) { speaker.submit(AudioFrame(ShortArray(480), 48000, 1)) }
        assertTrue(speaker.queuedBlocks <= 4)
        assertEquals(Short.MIN_VALUE, decodePcm(requireNotNull(backend.written.get()))[0])
        assertTrue(runCatching { speaker.submit(AudioFrame(ShortArray(480), 44100, 1)) }.isFailure)
        assertTrue(runCatching { speaker.submit(AudioFrame(ShortArray(48000), 48000, 1)) }.isFailure)
        speaker.close()
        assertEquals(0, speaker.queuedBlocks)
        assertEquals(1, backend.closes.get())
        assertFalse(speaker.running)
    }

    @Test fun deviceLossAndPartialWritesDoNotSpinOrLeak() {
        val closed = CountDownLatch(1)
        val writes = AtomicInteger()
        val failed = CountDownLatch(1)
        val backend =
            object : AudioBackend {
                override fun capture(format: DesktopAudioFormat): CaptureDevice = error("unused")

                override fun playback(format: DesktopAudioFormat): PlaybackDevice =
                    object : PlaybackDevice {
                        override fun write(
                            bytes: ByteArray,
                            offset: Int,
                            size: Int,
                        ): Int = if (writes.incrementAndGet() == 1) 2 else 0

                        override fun close() {
                            closed.countDown()
                        }
                    }
            }
        val speaker = DesktopSpeaker(format, backend) { failed.countDown() }
        speaker.startPlayback()
        speaker.submit(AudioFrame(ShortArray(480), 48000, 1))
        assertTrue(failed.await(2, TimeUnit.SECONDS))
        assertTrue(closed.await(2, TimeUnit.SECONDS))
        speaker.close()
        assertEquals(2, writes.get())
        assertFalse(speaker.running)
    }

    @Test fun playbackCanStopDuringADeviceWrite() {
        val done = CountDownLatch(1)
        lateinit var speaker: DesktopSpeaker
        val backend =
            object : AudioBackend {
                override fun capture(format: DesktopAudioFormat): CaptureDevice = error("unused")

                override fun playback(format: DesktopAudioFormat): PlaybackDevice =
                    object : PlaybackDevice {
                        override fun write(
                            bytes: ByteArray,
                            offset: Int,
                            size: Int,
                        ): Int {
                            speaker.close()
                            done.countDown()
                            return size
                        }

                        override fun close() = Unit
                    }
            }
        speaker = DesktopSpeaker(format, backend) {}
        speaker.startPlayback()
        speaker.submit(AudioFrame(ShortArray(480), 48000, 1))
        assertTrue(done.await(2, TimeUnit.SECONDS))
        speaker.close()
        assertFalse(speaker.running)
    }

    @Test fun bgraPreservesChannelsAndAlpha() {
        assertArrayEquals(
            intArrayOf(0x04030201, 0xffabcdef.toInt()),
            bgraToArgb(byteArrayOf(1, 2, 3, 4, 0xef.toByte(), 0xcd.toByte(), 0xab.toByte(), -1)),
        )
    }
}

private class FakeBackend(
    private val blockCapture: Boolean = true,
) : AudioBackend {
    val opens = AtomicInteger()
    val closes = AtomicInteger()
    val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)
    val written = AtomicReference<ByteArray?>()

    override fun capture(format: DesktopAudioFormat): CaptureDevice {
        opens.incrementAndGet()
        return object : CaptureDevice {
            override fun read(
                bytes: ByteArray,
                offset: Int,
                size: Int,
            ): Int {
                entered.countDown()
                if (blockCapture) check(released.await(3, TimeUnit.SECONDS)) { "Capture was not unblocked by close" }
                for (i in offset until offset + size step 2) {
                    bytes[i] = 0
                    bytes[i + 1] = -128
                }
                return size
            }

            override fun close() {
                closes.incrementAndGet()
                released.countDown()
            }
        }
    }

    override fun playback(format: DesktopAudioFormat): PlaybackDevice {
        opens.incrementAndGet()
        return object : PlaybackDevice {
            override fun write(
                bytes: ByteArray,
                offset: Int,
                size: Int,
            ): Int {
                written.set(bytes.copyOfRange(offset, offset + size))
                entered.countDown()
                check(released.await(3, TimeUnit.SECONDS)) { "Playback was not unblocked by close" }
                return size
            }

            override fun close() {
                closes.incrementAndGet()
                released.countDown()
            }
        }
    }
}
