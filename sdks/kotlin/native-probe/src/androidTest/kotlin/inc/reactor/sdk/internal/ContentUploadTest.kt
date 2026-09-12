package inc.reactor.sdk.internal

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import inc.reactor.sdk.UnauthorizedError
import inc.reactor.sdk.android.uploadContent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ContentUploadTest {
    @Test fun contentUriIsReadThroughResolverAndStagedInAppCache() =
        runBlocking<Unit> {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val fixture = File(context.cacheDir, "content-source.bin")
            fixture.outputStream().use { output -> repeat(32) { output.write(ByteArray(65536) { 42 }) } }
            val fake = UploadTest()
            val client = fake.client()
            try {
                val ref = client.uploadContent(context, Uri.parse("content://inc.reactor.sdk.probe.test.upload/source"), "picture.png")
                assertEquals("picture.png", ref.name)
                assertEquals("image/png", ref.mimeType)
                assertEquals(2_097_152uL, ref.size)
                assertTrue(fixture.isFile)
                val staged = File(fake.lastPath().decodeToString())
                assertTrue(staged.canonicalPath.startsWith(context.cacheDir.canonicalPath + File.separator))
                assertFalse(staged.exists())
                assertFalse(requireNotNull(staged.parentFile).exists())
            } finally {
                client.close()
                fixture.delete()
            }
        }

    @Test fun revokedContentAccessIsTypedAndLeavesNoStagingFile() =
        runBlocking<Unit> {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val fake = UploadTest()
            val client = fake.client()
            try {
                val before =
                    context.cacheDir
                        .listFiles()!!
                        .filter { it.name.startsWith("reactor-upload-") }
                        .map { it.name }
                        .toSet()
                val result =
                    runCatching {
                        client.uploadContent(
                            context,
                            Uri.parse("content://inc.reactor.sdk.probe.test.upload/denied"),
                            "picture.png",
                        )
                    }
                assertTrue(result.exceptionOrNull() is UnauthorizedError)
                assertEquals(
                    before,
                    context.cacheDir
                        .listFiles()!!
                        .filter { it.name.startsWith("reactor-upload-") }
                        .map { it.name }
                        .toSet(),
                )
            } finally {
                client.close()
            }
        }
}

class TestUploadProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/png"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        if (uri.lastPathSegment == "denied") throw SecurityException("Read access revoked")
        if (uri.lastPathSegment == "destination") {
            return ParcelFileDescriptor.open(
                File(requireNotNull(context).cacheDir, "content-destination.mp4"),
                ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE,
            )
        }
        return ParcelFileDescriptor.open(File(requireNotNull(context).cacheDir, "content-source.bin"), ParcelFileDescriptor.MODE_READ_ONLY)
    }
}
