package inc.reactor.sdk.android.media

import android.graphics.drawable.BitmapDrawable
import android.widget.ImageView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import inc.reactor.sdk.AudioFrame
import inc.reactor.sdk.VideoFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AndroidMediaLifecycleTest {
    @Test fun activityStopKeepsMainResponsiveAndRestartWaitsForCleanup() =
        runBlocking<Unit> {
            val starts = AtomicInteger()
            val active = AtomicInteger()
            val peak = AtomicInteger()
            val cleaning = CountDownLatch(1)
            val release = CountDownLatch(1)
            lateinit var media: ForegroundMedia
            ActivityScenario.launch(MediaTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    media =
                        ForegroundMedia(activity.lifecycle) {
                            assertFalse(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
                            val number = starts.incrementAndGet()
                            peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                            own(
                                AutoCloseable {
                                    if (number == 1) {
                                        cleaning.countDown()
                                        check(release.await(5, TimeUnit.SECONDS))
                                    }
                                    active.decrementAndGet()
                                },
                            )
                        }
                }
                assertEquals(0, starts.get())
                media.start()
                eventually { starts.get() == 1 }
                scenario.moveToState(Lifecycle.State.CREATED)
                assertTrue(cleaning.await(2, TimeUnit.SECONDS))
                // This runs on main while IO cleanup is deliberately held by the fake resource.
                withTimeout(2000) { withContext(Dispatchers.Main) { assertEquals(1, active.get()) } }
                scenario.moveToState(Lifecycle.State.STARTED)
                assertEquals(1, starts.get())
                release.countDown()
                eventually { starts.get() == 2 }
                assertEquals(1, peak.get())
                scenario.recreate()
                withTimeout(5000) { media.awaitClosed() }
                assertEquals(0, active.get())
                lateinit var next: ForegroundMedia
                scenario.onActivity { activity ->
                    next =
                        ForegroundMedia(activity.lifecycle) {
                            active.incrementAndGet()
                            own(AutoCloseable { active.decrementAndGet() })
                        }
                    next.start()
                }
                eventually { active.get() == 1 }
                next.close()
                withTimeout(2000) { next.awaitClosed() }
                assertEquals(0, active.get())
            }
        }

    @Test fun factoryFailureCleansPartiallyCreatedResourcesAndCanRetry() =
        runBlocking<Unit> {
            val attempts = AtomicInteger()
            val cleaned = AtomicInteger()
            val failure = CompletableDeferred<Unit>()
            lateinit var media: ForegroundMedia
            ActivityScenario.launch(MediaTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    media =
                        ForegroundMedia(activity.lifecycle, { failure.complete(Unit) }) {
                            own(AutoCloseable { cleaned.incrementAndGet() })
                            if (attempts.incrementAndGet() == 1) throw SecurityException("Permission denied")
                        }
                    media.start()
                }
                withTimeout(2000) { failure.await() }
                eventually { cleaned.get() == 1 }
                media.start()
                eventually { attempts.get() == 2 }
                media.close()
                withTimeout(2000) { media.awaitClosed() }
                assertEquals(2, cleaned.get())
            }
        }

    @Test fun frameworkPermissionDenialIsExplicitAndSpeakerReleases() =
        runBlocking<Unit> {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val microphone = AndroidMicrophone(context)
            assertTrue(runCatching { microphone.startCapture {} }.exceptionOrNull() is SecurityException)
            microphone.awaitClosed()
            val speaker = AndroidSpeaker(context)
            speaker.startPlayback()
            speaker.submit(AudioFrame(ShortArray(480), 48000, 1))
            delay(50)
            speaker.close()
            withTimeout(2000) { speaker.awaitClosed() }
            assertFalse(speaker.running)
        }

    @Test fun rendererShowsLatestBgraAndClosesWhileFramesArrive() =
        runBlocking<Unit> {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val view = withContext(Dispatchers.Main) { ImageView(context) }
            val renderer = AndroidVideoRenderer(view)
            repeat(50) { renderer.show(VideoFrame(byteArrayOf(0, 0, -1, -1), 1, 1)) }
            renderer.show(VideoFrame(byteArrayOf(-1, 0, 0, -1), 1, 1))
            eventually {
                withContext(
                    Dispatchers.Main,
                ) { (view.drawable as? BitmapDrawable)?.bitmap?.getPixel(0, 0) == 0xff0000ff.toInt() }
            }
            withContext(Dispatchers.Main) { renderer.close() }
            repeat(10) { renderer.show(VideoFrame(byteArrayOf(0, -1, 0, -1), 1, 1)) }
            withTimeout(2000) { renderer.awaitClosed() }
            withContext(Dispatchers.Main) { assertEquals(null, view.drawable) }
        }
}

private suspend fun eventually(test: suspend () -> Boolean) = withTimeout(5000) { while (!test()) delay(10) }
