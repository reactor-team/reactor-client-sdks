package inc.reactor.sdk.internal

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import inc.reactor.sdk.DownloadResult
import inc.reactor.sdk.UnauthorizedError
import inc.reactor.sdk.android.copyToContent
import inc.reactor.sdk.android.createRecordingOutput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ContentRecordingTest {
    @Test fun completedPrivateOutputCopiesThroughContentResolver() =
        runBlocking<Unit> {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val source = createRecordingOutput(context)
            val destination = File(context.cacheDir, "content-destination.mp4")
            try {
                assertTrue(source.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
                source.writeText("completed recording")
                val result = DownloadResult(source, source.length().toULong(), 1u)
                result.copyToContent(context, Uri.parse("content://inc.reactor.sdk.probe.test.upload/destination"))
                assertEquals(source.readText(), destination.readText())
                assertTrue(source.exists())
                assertTrue(
                    runCatching {
                        result.copyToContent(context, Uri.parse("content://inc.reactor.sdk.probe.test.upload/denied"))
                    }.exceptionOrNull() is UnauthorizedError,
                )
            } finally {
                source.delete()
                destination.delete()
            }
        }
}
