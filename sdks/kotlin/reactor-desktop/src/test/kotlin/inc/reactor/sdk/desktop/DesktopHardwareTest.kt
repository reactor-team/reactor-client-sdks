package inc.reactor.sdk.desktop

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Explicit local check only: reads ten PCM blocks without retaining or transmitting audio. */
class DesktopHardwareTest {
    @Test(timeout = 15000)
    fun actualCaptureAndPlayback() {
        assumeTrue(System.getProperty("reactor.desktop.hardware") == "true")
        val format = DesktopAudioFormat()
        val captured = CountDownLatch(10)
        val failures = AtomicInteger()
        val mic =
            DesktopMicrophone(format) {
                System.err.println(it)
                failures.incrementAndGet()
            }
        val speaker =
            DesktopSpeaker(format) {
                System.err.println(it)
                failures.incrementAndGet()
            }
        try {
            speaker.startPlayback()
            mic.startCapture { captured.countDown() }
            // Low-amplitude 440 Hz tone for 100 ms; no microphone audio goes to the speaker or network.
            repeat(10) { block ->
                val samples =
                    ShortArray(480) { i -> (500 * kotlin.math.sin(2 * Math.PI * 440 * (block * 480 + i) / 48000)).toInt().toShort() }
                speaker.submit(inc.reactor.sdk.AudioFrame(samples, 48000, 1))
                Thread.sleep(10)
            }
            assertTrue("No microphone frames arrived", captured.await(5, TimeUnit.SECONDS))
            assertTrue("Device failure", failures.get() == 0)
        } finally {
            mic.close()
            speaker.close()
        }
    }
}
