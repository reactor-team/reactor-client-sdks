package inc.reactor.sdk.internal

import inc.reactor.sdk.AudioFrame
import inc.reactor.sdk.DecodeFailedError
import inc.reactor.sdk.InvalidStateError
import inc.reactor.sdk.PublicationState
import inc.reactor.sdk.Reactor
import inc.reactor.sdk.TokenProvider
import inc.reactor.sdk.VideoFrame
import inc.reactor.sdk.timeMicros
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSendTest {
    init {
        val library = System.getProperty("reactor.jni.test.library")
        if (library == null) System.loadLibrary("reactor_jni_test") else System.load(library)
    }

    private external fun resetSend()

    private external fun configure(
        mode: Int,
        unpublishFailure: Boolean,
    )

    private external fun finishPublish()

    private external fun cycleStatus()

    private external fun values(): LongArray

    private external fun videoBytes(): ByteArray

    private external fun audioSamples(): ShortArray

    private val video = VideoFrame(byteArrayOf(1, 2, 3, -1), 1, 1)

    @Test fun publishingBlocksPushUntilCompletionAndIsSharedByTrackReferences() =
        runBlocking<Unit> {
            resetSend()
            val client = Reactor("model", local = true)
            try {
                client.connect()
                val track = client.track("input")
                assertThrows(InvalidStateError::class.java) { track.pushFrame(video) }
                configure(1, false)
                val publish = async(start = CoroutineStart.UNDISPATCHED) { track.publish() }
                assertEquals(PublicationState.PUBLISHING, track.publicationState)
                assertThrows(InvalidStateError::class.java) { track.pushFrame(video) }
                assertThrows(InvalidStateError::class.java) { runBlocking { track.publish() } }
                assertThrows(InvalidStateError::class.java) { track.unpublish() }
                assertEquals(0L, values()[0])
                finishPublish()
                withTimeout(5000) { publish.await() }
                assertTrue(client.track("input").published)
                track.publish() // Already published: no second native operation.
                assertEquals(1L, values()[13])
                track.pushFrame(video)
                assertEquals(1L, values()[0])
            } finally {
                client.close()
            }
        }

    @Test fun staleCompletionCannotRestorePublicationAfterConnectionReset() =
        runBlocking<Unit> {
            resetSend()
            val client = Reactor("model", local = true)
            try {
                client.connect()
                val track = client.track("input")
                configure(1, false)
                val old = async(start = CoroutineStart.UNDISPATCHED) { runCatching { track.publish() } }
                cycleStatus()
                assertFalse(track.published)
                configure(0, false)
                track.publish()
                finishPublish()
                val result = withTimeout(5000) { old.await() }
                assertTrue(result.exceptionOrNull() is InvalidStateError)
                assertTrue(track.published) // Old completion cannot remove the NEW publication either.
                cycleStatus()
                assertFalse(track.published)
                assertThrows(InvalidStateError::class.java) { track.pushFrame(video) }
            } finally {
                client.close()
            }
        }

    @Test fun cancellationDoesNotPretendToCancelNativePublish() =
        runBlocking<Unit> {
            resetSend()
            val client = Reactor("model", local = true)
            try {
                client.connect()
                val track = client.track("input")
                configure(1, false)
                val publish = async(start = CoroutineStart.UNDISPATCHED) { track.publish() }
                publish.cancel()
                publish.join()
                assertEquals(PublicationState.PUBLISHING, track.publicationState)
                finishPublish()
                withTimeout(5000) { while (!track.published) yield() }
                track.unpublish()
                assertFalse(track.published)
            } finally {
                client.close()
            }
        }

    @Test fun failedUnpublishIsRetryableAndFailedPublishNeverEnablesPush() =
        runBlocking<Unit> {
            resetSend()
            val client = Reactor("model", local = true)
            try {
                client.connect()
                val track = client.track("input")
                configure(2, false)
                assertTrue(runCatching { track.publish() }.exceptionOrNull() is InvalidStateError)
                assertFalse(track.published)
                configure(3, false)
                assertTrue(runCatching { track.publish() }.exceptionOrNull() is DecodeFailedError)
                assertFalse(track.published)
                configure(0, true)
                track.publish()
                assertThrows(InvalidStateError::class.java) { track.unpublish() }
                assertTrue(track.published)
                configure(0, false)
                track.unpublish()
                assertFalse(track.published)
                track.unpublish()
                assertEquals(2L, values()[14])
            } finally {
                client.close()
            }
        }

    @Test fun nativeVideoReceivesExactPixelsMetadataAndEngineTimestamp() =
        runBlocking<Unit> {
            resetSend()
            val client = Reactor("model", local = true)
            try {
                client.connect()
                val track = client.track("input").publish()
                track.pushFrame(video)
                assertEquals(0L, values()[4])
                assertArrayEquals(video.pixels, videoBytes())
                val tagged = video.copy(userData = byteArrayOf(0, -128, -1))
                track.pushFrame(tagged)
                assertEquals(1L, values()[4])
                assertArrayEquals(byteArrayOf(1, 2, 3, -1, 0, -128, -1), videoBytes())
                val now = timeMicros()
                assertEquals(0x123456789abcdefL, now)
                track.pushFrame(tagged, now)
                assertEquals(now, values()[3])
                assertEquals(2L, values()[4])
                track.pushFrame(video, Long.MAX_VALUE)
                assertEquals(Long.MAX_VALUE, values()[3])
                track.pushFrame(video, 0)
                assertEquals(0L, values()[3])
            } finally {
                client.close()
            }
        }

    @Test fun pcmUsesSampleCountPerChannelAndEverySupportedFormat() =
        runBlocking<Unit> {
            resetSend()
            val client = Reactor("model", local = true)
            try {
                client.connect()
                val mic = client.track("mic").publish()
                val pcm = shortArrayOf(Short.MIN_VALUE, 10, 300, Short.MAX_VALUE)
                for (rate in listOf(8000, 16000, 24000, 32000, 44100, 48000)) {
                    for (channels in 1..2) {
                        mic.pushFrame(AudioFrame(pcm, rate, channels))
                        assertArrayEquals(pcm, audioSamples())
                        assertEquals((pcm.size / channels).toLong(), values()[5])
                        assertEquals(rate.toLong(), values()[6])
                        assertEquals(channels.toLong(), values()[7])
                    }
                }
            } finally {
                client.close()
            }
        }

    @Test fun invalidDirectionKindBufferFormatAndTimestampNeverReachPush() =
        runBlocking<Unit> {
            resetSend()
            val client = Reactor("model", local = true)
            try {
                client.connect()
                val input = client.track("input").publish()
                val mic = client.track("mic").publish()
                val receive = client.track("z-video")
                assertThrows(IllegalArgumentException::class.java) { runBlocking { receive.publish() } }
                assertThrows(IllegalArgumentException::class.java) { receive.unpublish() }
                assertThrows(IllegalArgumentException::class.java) { receive.pushFrame(video) }
                assertThrows(IllegalArgumentException::class.java) { mic.pushFrame(video) }
                assertThrows(IllegalArgumentException::class.java) { input.pushFrame(AudioFrame(shortArrayOf(1), 48000, 1)) }
                assertThrows(IllegalArgumentException::class.java) { input.pushFrame(video, -1) }
                assertThrows(IllegalArgumentException::class.java) { input.pushFrame(video, Long.MIN_VALUE) }
                assertThrows(IllegalArgumentException::class.java) { VideoFrame(byteArrayOf(1), 1, 1) }
                assertThrows(IllegalArgumentException::class.java) { VideoFrame(byteArrayOf(), Int.MAX_VALUE, Int.MAX_VALUE) }
                assertThrows(IllegalArgumentException::class.java) { VideoFrame(byteArrayOf(), 0, 1) }
                assertThrows(IllegalArgumentException::class.java) { AudioFrame(shortArrayOf(1), 48000, 2) }
                assertThrows(IllegalArgumentException::class.java) { AudioFrame(shortArrayOf(1), 48000, 0) }
                assertThrows(IllegalArgumentException::class.java) { mic.pushFrame(AudioFrame(shortArrayOf(1), 12345, 1)) }
                assertThrows(IllegalArgumentException::class.java) { mic.pushFrame(AudioFrame(shortArrayOf(1, 2, 3), 48000, 3)) }
                assertEquals(0L, values()[0])
                assertEquals(0L, values()[5])
            } finally {
                client.close()
            }
        }

    @Test fun bitrateBoundsApplyBeforeConnectAndRejectInvalidSentinels() =
        runBlocking<Unit> {
            resetSend()
            val client = Reactor("model", local = true)
            try {
                client.setBitrate(0, -1, Int.MAX_VALUE)
                assertEquals(0L, values()[8])
                client.connect()
                assertEquals(listOf(1L, 0L, -1L, Int.MAX_VALUE.toLong(), 0L), values().slice(8..12))
                client.track("input").setBitrate(0, 8_000_000)
                assertEquals(listOf(2L, 0L, -1L, 8_000_000L, 1L), values().slice(8..12))
                assertThrows(IllegalArgumentException::class.java) { runBlocking { client.setBitrate(-2) } }
                assertThrows(IllegalArgumentException::class.java) { runBlocking { client.setBitrate(startBps = -2) } }
                assertThrows(IllegalArgumentException::class.java) { runBlocking { client.setBitrate(maxBps = -2) } }
                assertThrows(IllegalArgumentException::class.java) { runBlocking { client.track("input").setBitrate(maxBps = -2) } }
                assertThrows(IllegalArgumentException::class.java) { runBlocking { client.track("z-video").setBitrate() } }
                assertEquals(2L, values()[8])
            } finally {
                client.close()
            }
        }

    @Test fun tokenChangeDropsPublicationAndReappliesConnectionBounds() =
        runBlocking<Unit> {
            resetSend()
            var tokens = 0
            val client = Reactor("model", TokenProvider { "token-${++tokens}" })
            try {
                client.setBitrate(maxBps = 9_000_000)
                client.connect()
                val track = client.track("input").publish()
                client.connect("session")
                assertFalse(track.published)
                assertEquals(2L, values()[8])
                assertEquals(9_000_000L, values()[11])
            } finally {
                client.close()
            }
        }

    @Test fun nativeBoundaryRejectsInvalidBuffersEvenWithoutObjectValidation() {
        resetSend()
        val receiver = RealNativeLifecycleTest.Receiver()
        val native =
            NativeClient.create(
                "http://localhost".encodeToByteArray(),
                "model".encodeToByteArray(),
                null,
                true,
                SDK_VERSION.encodeToByteArray(),
                receiver,
                null,
            )
        try {
            val name = "input".encodeToByteArray()
            assertThrows(IllegalStateException::class.java) {
                NativeClient.pushVideo(native, name, byteArrayOf(1), 1, 1, null, -1)
            }
            assertThrows(IllegalStateException::class.java) {
                NativeClient.pushVideo(native, name, byteArrayOf(1), Int.MAX_VALUE, Int.MAX_VALUE, null, -1)
            }
            assertThrows(IllegalStateException::class.java) {
                NativeClient.pushVideo(native, name, video.pixels, 1, 1, null, -2)
            }
            assertThrows(IllegalStateException::class.java) {
                NativeClient.pushAudio(native, name, shortArrayOf(1), 48000, 2)
            }
            assertThrows(IllegalStateException::class.java) {
                NativeClient.pushAudio(native, name, shortArrayOf(1), 12345, 1)
            }
            assertEquals(0L, values()[0])
            assertEquals(0L, values()[5])
        } finally {
            NativeClient.destroy(native)
        }
    }

    @Test fun closeSettlesPendingPublishAndInvalidatesTrack() =
        runBlocking<Unit> {
            resetSend()
            val client = Reactor("model", local = true)
            try {
                client.connect()
                val track = client.track("input")
                configure(1, false)
                val pending = async(start = CoroutineStart.UNDISPATCHED) { runCatching { track.publish() } }
                client.close()
                assertTrue(withTimeout(5000) { pending.await() }.exceptionOrNull() is inc.reactor.sdk.AbortedError)
                assertThrows(InvalidStateError::class.java) { track.pushFrame(video) }
            } finally {
                client.close()
            }
        }
}
