package inc.reactor.sdk.internal

import inc.reactor.sdk.AudioFrame
import inc.reactor.sdk.Reactor
import inc.reactor.sdk.TokenProvider
import inc.reactor.sdk.TrackDirection
import inc.reactor.sdk.TrackKind
import inc.reactor.sdk.VideoFrame
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MediaReceiveTest {
    init {
        val library = System.getProperty("reactor.jni.test.library")
        if (library == null) System.loadLibrary("reactor_jni_test") else System.load(library)
    }

    private external fun orphanNextDestroy()

    private external fun emitOld()

    private external fun resetMedia()

    private external fun invalidateOnRead()

    private external fun emit(kind: Int)

    @Test
    fun callbacksFromOrphanedHandleCannotMutateReplacement() =
        runBlocking {
            resetMedia()
            var tokens = 0
            val client = Reactor("model", TokenProvider { "token-${++tokens}" })
            try {
                client.connect()
                var frames = 0
                client.track("z-video").onFrame { frames++ }
                orphanNextDestroy()
                client.connect("existing")
                emitOld()
                assertEquals(0, frames)
                assertEquals(listOf("z-video", "a-audio", "input"), client.tracks.map { it.name })
                emit(0)
                assertEquals(1, frames)
            } finally {
                client.close()
            }
        }

    @Test fun declarationOrderFiltersMetadataAndPause() =
        runBlocking {
            resetMedia()
            val client = Reactor("model", local = true)
            try {
                client.connect()
                assertEquals(listOf("z-video", "a-audio", "input"), client.tracks.map { it.name })
                assertEquals(
                    "z-video",
                    client.tracks
                        .withKind(TrackKind.VIDEO)
                        .withDirection(TrackDirection.RECVONLY)
                        .one()
                        .name,
                )
                assertEquals(
                    "a-audio",
                    client.tracks
                        .withDirection(TrackDirection.RECVONLY)
                        .withKind(TrackKind.AUDIO)
                        .one()
                        .name,
                )
                assertThrows(IllegalArgumentException::class.java) { client.tracks.one() }
                assertThrows(
                    IllegalArgumentException::class.java,
                ) {
                    client.tracks
                        .withKind(TrackKind.AUDIO)
                        .withDirection(TrackDirection.SENDONLY)
                        .one()
                }
                assertThrows(IllegalArgumentException::class.java) { client.track("missing") }
                assertThrows(IllegalArgumentException::class.java) { client.track("input").onFrame {} }
                val video = client.track("z-video")
                assertEquals("video-mid", video.mid)
                video.pause()
                assertTrue(video.paused)
                video.resume()
                assertFalse(video.paused)
            } finally {
                client.close()
            }
        }

    @Test fun nativeFramesPreserveOwnedDataAndAudioFormat() =
        runBlocking {
            resetMedia()
            val client = Reactor("model", local = true)
            try {
                client.connect()
                var video: VideoFrame? = null
                var audio: AudioFrame? = null
                client.track("z-video").onFrame { video = it as VideoFrame }
                client.track("a-audio").onFrame { audio = it as AudioFrame }
                emit(0)
                emit(1)
                assertArrayEquals(byteArrayOf(1, 2, 3, -1), video!!.pixels)
                assertArrayEquals(byteArrayOf(0, -128, -1), video!!.userData)
                assertEquals(ULong.MAX_VALUE, video!!.frameId)
                assertEquals(0x8000000000000000uL, video!!.timestampMicros)
                assertArrayEquals(shortArrayOf(-32768, 10, 300, 32767), audio!!.samples)
                assertEquals(44100, audio!!.sampleRate)
                assertEquals(2, audio!!.channels)
                assertEquals(2, audio!!.samplesPerChannel)
            } finally {
                client.close()
            }
        }

    @Test fun invalidUnknownFramesAndThrowingHandlerAreContained() =
        runBlocking {
            resetMedia()
            val diagnostics = AtomicInteger()
            val delivered = AtomicInteger()
            val client = Reactor("model", local = true, onHandlerFailure = { diagnostics.incrementAndGet() })
            try {
                client.connect()
                client.track("z-video").onFrame { error("bad video handler") }
                client.track("z-video").onFrame { delivered.incrementAndGet() }
                emit(2)
                emit(3)
                emit(4)
                assertEquals(0, delivered.get())
                assertEquals(3, diagnostics.get())
                emit(0)
                emit(0)
                assertEquals(2, delivered.get())
                assertEquals(4, diagnostics.get())
            } finally {
                client.close()
            }
        }

    @Test fun inlineBackpressureAndRemovalWaitForInFlightDelivery() =
        runBlocking {
            resetMedia()
            val client = Reactor("model", local = true)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val count = AtomicInteger()
            try {
                client.connect()
                val subscription =
                    client.track("z-video").onFrame {
                        count.incrementAndGet()
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                val delivery = async(Dispatchers.IO) { emit(0) }
                assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
                val removal = async(Dispatchers.IO, start = CoroutineStart.DEFAULT) { subscription.close() }
                try {
                    assertFalse(delivery.isCompleted)
                    assertFalse(removal.isCompleted)
                    assertEquals(
                        null,
                        withTimeoutOrNull(100) {
                            removal.await()
                            true
                        },
                    )
                } finally {
                    release.countDown()
                }
                withTimeout(5000) {
                    delivery.await()
                    removal.await()
                }
                emit(0)
                assertEquals(1, count.get())
            } finally {
                release.countDown()
                client.close()
            }
        }

    @Test fun selfRemovalStopsFutureDeliveries() =
        runBlocking {
            resetMedia()
            val client = Reactor("model", local = true)
            try {
                client.connect()
                var count = 0
                lateinit var subscription: AutoCloseable
                subscription =
                    client.track("z-video").onFrame {
                        count++
                        subscription.close()
                    }
                emit(0)
                emit(0)
                assertEquals(1, count)
            } finally {
                client.close()
            }
        }

    @Test fun eventDuringNativeSnapshotWinsOverStaleRead() =
        runBlocking {
            resetMedia()
            val client = Reactor("model", local = true)
            try {
                client.connect()
                assertEquals(3, client.tracks.size)
                invalidateOnRead()
                assertEquals(listOf("fresh"), client.tracks.map { it.name })
                assertEquals(listOf("fresh"), client.tracks.map { it.name })
            } finally {
                client.close()
            }
        }
}
