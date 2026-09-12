package inc.reactor.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** A recording window; readiness and HLS assembly are handled by the native downloader. */
data class Clip(
    val sessionId: String,
    val kind: String,
    val startMarker: Double,
    val endMarker: Double,
    val nowMarker: Double,
    val predictedReadyAtMillis: Double,
    val playlistUrl: String,
)

data class DownloadProgress(
    val done: UInt,
    val total: UInt,
)

data class DownloadResult(
    val file: File,
    val bytes: ULong,
    val segments: UInt,
)

internal fun clip(value: JsonElement?): Clip {
    val obj = value as? JsonObject ?: error("Expected a clip object")

    fun number(key: String): Double {
        val field = obj[key] as? JsonPrimitive ?: error("Missing $key")
        return requireNotNull(field.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }) { "Invalid $key" }
    }
    return Clip(
        obj.recordingString("session_id"),
        obj.recordingString("kind"),
        number("start_marker"),
        number("end_marker"),
        number("now_marker"),
        number("predicted_ready_at_ms"),
        obj.recordingString("playlist_url"),
    )
}

internal fun downloadResult(value: JsonElement?): DownloadResult {
    val obj = value as? JsonObject ?: error("Expected a download result")
    val segments = obj.recordingUnsigned("segments")
    require(segments <= UInt.MAX_VALUE.toULong()) { "Invalid segment count" }
    return DownloadResult(File(obj.recordingString("path")), obj.recordingUnsigned("bytes"), segments.toUInt())
}

internal fun downloadProgress(value: JsonElement): DownloadProgress {
    val obj = value as? JsonObject ?: error("Expected download progress")
    val done = obj.recordingUnsigned("done")
    val total = obj.recordingUnsigned("total")
    require(done <= total && total <= UInt.MAX_VALUE.toULong()) { "Invalid download progress" }
    return DownloadProgress(done.toUInt(), total.toUInt())
}

private fun JsonObject.recordingString(key: String): String =
    requireNotNull((get(key) as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content) { "Missing $key" }

private fun JsonObject.recordingUnsigned(key: String): ULong {
    val field = get(key) as? JsonPrimitive ?: error("Missing $key")
    require(!field.isString && field.content.all { it in '0'..'9' }) { "Invalid $key" }
    return requireNotNull(field.content.toULongOrNull()) { "Invalid $key" }
}

/** Copy completed output with bounded memory. Opens/closes the destination on IO, preserving the source.
 * Cancellation/failure can leave a partial destination. The caller owns both files.
 */
suspend fun DownloadResult.copyTo(open: () -> OutputStream): Unit =
    withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        try {
            file.inputStream().use { input ->
                open().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutine.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
        } catch (failure: SecurityException) {
            throw UnauthorizedError(
                ErrorDetails(
                    "UNAUTHORIZED",
                    "Grant access to the recording source/destination: ${failure.message}",
                    operation = "copy_recording",
                ),
            )
        } catch (failure: IOException) {
            throw BadRequestError(
                ErrorDetails("BAD_REQUEST", "Cannot copy completed recording: ${failure.message}", operation = "copy_recording"),
            )
        }
    }
