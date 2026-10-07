package inc.reactor.sdk.android.internal

import inc.reactor.sdk.android.ErrorCode
import inc.reactor.sdk.android.FileRef
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream

/** Decoding and staging for uploads. */
internal object Uploads {
    /**
     * `{ upload_id, name, mime_type, size }`.
     *
     * A missing field is a decode failure rather than a default: an upload with no id is not an
     * upload with an empty id, and handing back a FileRef that names nothing would fail later, in
     * a command, with nothing pointing back here.
     */
    fun decode(resultJson: String?): FileRef {
        if (resultJson.isNullOrBlank()) {
            throw JsonException("The upload completed but returned no file reference")
        }
        val fields = Json.parseObject(resultJson)
        val uploadId =
            fields["upload_id"] as? String
                ?: throw JsonException("Upload result carried no upload_id: $resultJson")
        return FileRef(
            uploadId = uploadId,
            name = fields["name"] as? String ?: "",
            mimeType = fields["mime_type"] as? String ?: "application/octet-stream",
            size = (fields["size"] as? Double)?.toLong() ?: 0L,
        )
    }

    /**
     * A MIME type for [name], from its extension, never null.
     *
     * The ABI will not take null: `reactor_upload_bytes` dereferences `mime_type` with
     * `CStr::from_ptr` unconditionally, and only `send_command`'s args and uploads are documented
     * nullable. So the public `mimeType = null` default has to become something here rather than
     * crossing the boundary.
     *
     * `android.webkit.MimeTypeMap` would know more types, but it is a framework class with no
     * value outside an instrumented test, and this binding's unit suite runs on the host JVM.
     * This covers what an upload to a model actually carries; anything else is the generic type,
     * which is the honest answer rather than a guess.
     */
    fun mimeTypeFor(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "heic" -> "image/heic"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "m4a" -> "audio/mp4"
            "flac" -> "audio/flac"
            "json" -> "application/json"
            "txt" -> "text/plain"
            "csv" -> "text/csv"
            else -> "application/octet-stream"
        }

    /**
     * Copy a stream into [directory], bounded by [maxBytes].
     *
     * Streamed in chunks rather than read into a ByteArray: the source is a file the *user*
     * chose, and an SDK that called readBytes() on it would decide the memory ceiling of an app
     * it knows nothing about.
     *
     * The bound is enforced while copying, not checked afterwards — by then the bytes are already
     * on disk, which is the thing being avoided. The partial file is removed on the way out.
     */
    suspend fun stage(
        name: String,
        directory: File,
        maxBytes: Long,
        open: () -> InputStream,
    ): File {
        require(maxBytes > 0) { "maxBytes must be positive, got $maxBytes" }
        val staged = File.createTempFile("reactor-upload-", "-$name", directory)
        var copied = 0L
        try {
            open().use { input ->
                staged.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        // Between chunks, because `read` is not interruptible. Without this the
                        // copy runs to completion after its caller has been cancelled and walked
                        // away, leaving a staged file nobody holds a reference to — the `catch`
                        // below never fires, so nothing deletes it.
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied += read
                        if (copied > maxBytes) {
                            throw ErrorCode.toException(
                                wire = "MESSAGE_TOO_LARGE",
                                message =
                                    "'$name' exceeds the $maxBytes byte limit for an " +
                                        "upload. Raise maxBytes if the model accepts more.",
                                operation = "upload",
                            )
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
        } catch (t: Throwable) {
            staged.delete()
            throw t
        }
        return staged
    }
}
