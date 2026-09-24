package inc.reactor.sdk.android.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Starting and stopping the audio devices.
 *
 * The interesting cases are races between `stop()` and a callback already in flight, which a fake
 * backend reproduces on demand and real hardware does not.
 */
class DeviceLifecycleTest {
    /**
     * A capture device that behaves the way AudioRecord does on close: it announces that it is
     * stopping, then **waits for the capture callback to finish** before returning.
     *
     * That wait is the whole hazard. Whatever the binding does inside its callback has to be
     * something `stop()` is not also holding, or the two wait for each other for ever.
     */
    private class FakeCapture(
        /**
         * Whether `stop()` should block until a callback arrives and returns.
         *
         * Off by default, because a real device returns straight away when nothing is capturing —
         * the first version of this fake waited unconditionally and made every test in the class
         * take thirty seconds. Only the deadlock test wants the waiting behaviour, and only
         * because that wait is the hazard it exercises.
         */
        private val waitsForCallbackOnStop: Boolean = false,
    ) : CaptureDevice {
        @Volatile
        var onData: ((ShortArray) -> Unit)? = null

        @Volatile
        var stopped = false

        /** Counted once stop() has begun, so a test can deliver a buffer into that window. */
        val stopEntered = CountDownLatch(1)

        /** Counted when a callback delivered during teardown has returned. */
        val callbackFinished = CountDownLatch(1)

        override fun start(onData: (ShortArray) -> Unit) {
            this.onData = onData
        }

        override fun stop() {
            stopped = true
            stopEntered.countDown()
            if (!waitsForCallbackOnStop) return
            // Wait for the in-flight callback, as the real device does. Generously bounded only
            // so a genuine deadlock fails the suite rather than hanging it for ever — the test
            // asserts on how long this took, because a wait that eventually times out is still a
            // frozen app as far as a user is concerned.
            callbackFinished.await(30, TimeUnit.SECONDS)
        }

        fun deliver(samples: ShortArray) {
            onData?.invoke(samples)
        }

        /** Deliver a buffer and record that the callback returned. */
        fun deliverDuringTeardown(samples: ShortArray) {
            onData?.invoke(samples)
            callbackFinished.countDown()
        }
    }

    private class FakeRender : RenderDevice {
        val written = mutableListOf<ShortArray>()

        @Volatile var running = false

        override fun start() {
            running = true
        }

        override fun write(samples: ShortArray) {
            written += samples
        }

        override fun stop() {
            running = false
        }
    }

    // ── Microphone ───────────────────────────────────────────────────────────

    @Test
    fun `captured audio is mixed and resampled before it reaches the caller`() {
        val device = FakeCapture()
        val mic = Microphone(device, targetSampleRate = 24000, deviceSampleRate = 48000, channels = 2)
        val seen = mutableListOf<ShortArray>()
        mic.start { seen += it }

        // 4 stereo frames at 48k -> 4 mono samples -> 2 at 24k.
        device.deliver(shortArrayOf(100, 100, 200, 200, 300, 300, 400, 400))
        assertEquals(1, seen.size)
        assertEquals(2, seen[0].size)
        mic.stop()
    }

    /**
     * The race the atomic exists for: a buffer captured while stopping must be dropped, not
     * delivered into a track the caller has already torn down.
     */
    @Test
    fun `a buffer captured while stopping is dropped`() {
        val device = FakeCapture()
        val mic = Microphone(device, 48000, 48000, 1)
        var delivered = 0
        mic.start { delivered++ }

        device.deliver(shortArrayOf(1, 2, 3))
        assertEquals(1, delivered)

        mic.stop()
        device.deliver(shortArrayOf(4, 5, 6)) // arrives after stop
        assertEquals("a buffer after stop must not reach the caller", 1, delivered)
    }

    /**
     * The deadlock the atomic prevents, reproduced in the order it actually happens.
     *
     * `stop()` runs while a buffer is still arriving. The device's own `stop()` waits for that
     * callback to return; the callback, in a mutex-guarded binding, waits for the lock `stop()`
     * is holding. Neither can move.
     *
     * Verified in both directions: giving Microphone a ReentrantLock taken in both its callback
     * and its stop() makes this test hang and fail on its timeout. With the AtomicBoolean the
     * callback takes no lock, sees that capture is stopping, drops the buffer and returns.
     */
    @Test(timeout = 10_000)
    fun `stopping while a buffer is arriving does not deadlock`() {
        val device = FakeCapture(waitsForCallbackOnStop = true)
        val mic = Microphone(device, 48000, 48000, 1)
        var delivered = 0
        mic.start { delivered++ }

        // Delivers a buffer *after* teardown has begun — the window where a lock-holding stop()
        // and a lock-taking callback would meet.
        val capture =
            Thread {
                assertTrue(device.stopEntered.await(5, TimeUnit.SECONDS))
                device.deliverDuringTeardown(shortArrayOf(1, 2, 3))
            }
        capture.start()

        val startedAt = System.nanoTime()
        val stopper = Thread { mic.stop() }
        stopper.start()

        stopper.join(35_000)
        capture.join(35_000)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertFalse("stop() never returned — the callback and stop deadlocked", stopper.isAlive)
        // The timing assertion is the one that actually catches it. A mutex-guarded Microphone
        // blocks here until the device's own wait gives up, so `stop()` still *returns* — just
        // many seconds later, which is a frozen app rather than a hung test.
        assertTrue(
            "stop() took ${elapsedMs}ms — it waited on a callback that was waiting on it",
            elapsedMs < 2_000,
        )
        assertTrue(device.stopped)
        assertEquals("a buffer arriving during teardown must be dropped", 0, delivered)
    }

    @Test
    fun `starting twice is refused rather than silently reopening the device`() {
        val mic = Microphone(FakeCapture(), 48000, 48000, 1)
        mic.start { }
        assertThrows(IllegalStateException::class.java) { mic.start { } }
        mic.stop()
    }

    @Test
    fun `stopping twice is a no-op`() {
        val mic = Microphone(FakeCapture(), 48000, 48000, 1)
        mic.start { }
        mic.stop()
        mic.stop()
        assertFalse(mic.isCapturing)
    }

    // ── Speaker ──────────────────────────────────────────────────────────────

    @Test
    fun `a speaker plays what it is given`() {
        val device = FakeRender()
        val speaker = Speaker(device)
        speaker.start()
        speaker.write(shortArrayOf(1, 2, 3))
        assertEquals(1, device.written.size)
        speaker.stop()
        assertFalse(device.running)
    }

    /**
     * A write after stop is ignored rather than throwing: this is called from an audio loop, and
     * an exception there takes the loop with it for a condition that is entirely ordinary.
     */
    @Test
    fun `writing after stop is ignored rather than throwing`() {
        val device = FakeRender()
        val speaker = Speaker(device)
        speaker.start()
        speaker.stop()
        speaker.write(shortArrayOf(1, 2, 3))
        assertEquals(0, device.written.size)
    }

    @Test
    fun `starting a speaker twice is refused`() {
        val speaker = Speaker(FakeRender())
        speaker.start()
        assertThrows(IllegalStateException::class.java) { speaker.start() }
        speaker.stop()
    }

    @Test
    fun `stopping a speaker twice is a no-op`() {
        val speaker = Speaker(FakeRender())
        speaker.start()
        speaker.stop()
        speaker.stop()
        assertFalse(speaker.isPlaying)
    }
}
