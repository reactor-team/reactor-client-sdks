package inc.reactor.sdk.android.internal

import inc.reactor.sdk.android.ErrorCode
import inc.reactor.sdk.android.FileRef
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
     * Copy a stream into [directory], bounded by [maxBytes].
     *
     * Streamed in chunks rather than read into a ByteArray: the source is a file the *user*
     * chose, and an SDK that called readBytes() on it would decide the memory ceiling of an app
     * it knows nothing about.
     *
     * The bound is enforced while copying, not checked afterwards — by then the bytes are already
     * on disk, which is the thing being avoided. The partial file is removed on the way out.
     */
    fun stage(
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
