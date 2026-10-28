package inc.reactor.sdk.android.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.nio.ByteBuffer

/**
 * Uploads and recordings — the two paths that leave the WebRTC connection entirely.
 *
 * Both talk to the coordinator over HTTP and, for a clip, to whichever host the segments were
 * presigned onto. That is the half a local runtime does not have, and the reason these cannot be
 * proven anywhere but production.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
internal class LiveTransferTest : LiveFixture() {
    private val scratch: File
        get() = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir

    @Test
    fun aFileUploadComesBackAsAReference() =
        runBlocking {
            val file = File.createTempFile("upload", ".bin", scratch)
            try {
                file.writeBytes(ByteArray(4096) { (it % 251).toByte() })
                val ref = reactor.uploadFile(file)
                assertTrue("an upload must carry an id", ref.uploadId.isNotBlank())
                assertEquals("the platform must record the size it received", 4096L, ref.size)
            } finally {
                file.delete()
            }
        }

    @Test
    fun aByteUploadComesBackAsAReference() =
        runBlocking {
            val bytes = ByteBuffer.allocateDirect(2048)
            repeat(2048) { bytes.put((it % 199).toByte()) }
            bytes.rewind()
            val ref = reactor.uploadBytes(bytes, name = "inline.bin", mimeType = "application/octet-stream")
            assertTrue("an upload must carry an id", ref.uploadId.isNotBlank())
            assertEquals(2048L, ref.size)
            assertEquals("inline.bin", ref.name)
        }

    /**
     * Request a clip and download it.
     *
     * The wait is bounded by the session being alive rather than by a number: readiness is in
     * *media* time, so a wall-clock guess is only correct at 1x, and once the session is gone a
     * 202 is a 202 forever.
     */
    @Test
    fun aClipIsRequestedAndDownloaded() =
        runBlocking {
            // Publish something first, so there is media to clip. An empty session produces a
            // playlist with nothing in it, which is a different — and much less useful — test.
            val outgoing = reactor.track(Live.VIDEO_IN)
            outgoing.publish()

            // Kept running until the download finishes, not stopped before requestClip.
            //
            // A snap clip includes the recording chunk that is still open when it is asked for,
            // and echo only advances media time when it is *receiving* frames. Stop pushing and
            // that chunk never reaches a boundary, so the clip never becomes ready and the
            // download waits as long as the session lives — which, now that readiness is bounded
            // by session lifetime rather than by a wall-clock guess, is a hang rather than a
            // failure. The Java recording test keeps a producer alive for the same reason.
            val producer =
                launch {
                    val pixels = ByteBuffer.allocateDirect(64 * 64 * 4)
                    while (isActive) {
                        pixels.rewind()
                        // The track may be unpublished under us during teardown; that is the
                        // producer's cue to stop, not a test failure.
                        if (runCatching { outgoing.pushFrame(pixels, 64, 64) }.isFailure) break
                        delay(33)
                    }
                }

            try {
                delay(3_000) // enough media to clip

                val clip = reactor.requestClip(durationSeconds = 2.0)
                assertTrue("a clip must name its playlist", clip.playlistUrl.isNotBlank())

                val out = File(scratch, "clip-${System.currentTimeMillis()}.mp4")
                try {
                    val segments = mutableListOf<Int>()
                    val downloaded =
                        reactor.downloadClip(clip, out) { progress -> segments += progress.done }
                    assertTrue("the clip must land on disk", out.exists())
                    assertTrue("a downloaded clip is not empty", downloaded.bytes > 0)
                    assertTrue("progress must be reported at least once", segments.isNotEmpty())
                } finally {
                    out.delete()
                }
            } finally {
                // Bounded: the producer must not outlive the test and push into the next one's
                // session. cancelAndJoin rather than cancel, so teardown is ordered.
                withTimeoutOrNull(5_000) { producer.cancelAndJoin() }
                runCatching { outgoing.unpublish() }
            }
        }
}
