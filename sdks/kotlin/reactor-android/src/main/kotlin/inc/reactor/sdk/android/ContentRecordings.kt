package inc.reactor.sdk.android

import android.content.Context
import android.net.Uri
import inc.reactor.sdk.DownloadResult
import inc.reactor.sdk.copyTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** A unique app-private output. The caller owns it, including any partial native download. */
suspend fun createRecordingOutput(context: Context): File =
    withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, "reactor-recordings")
        check(directory.mkdirs() || directory.isDirectory) { "Cannot create private recordings directory" }
        File.createTempFile("recording-", ".mp4", directory)
    }

/** Copy only a successfully completed download. Requires an existing write grant.
 * Neither the source nor a partially written destination is deleted on cancellation/failure.
 */
suspend fun DownloadResult.copyToContent(
    context: Context,
    destination: Uri,
): Unit =
    withContext(Dispatchers.IO) {
        require(destination.scheme == "content") { "Use a content URI with a write grant" }
        copyTo {
            context.contentResolver.openOutputStream(destination, "wt")
                ?: throw IOException("Content provider returned no output stream")
        }
    }
