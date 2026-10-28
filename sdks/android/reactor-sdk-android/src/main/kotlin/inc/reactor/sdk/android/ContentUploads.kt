package inc.reactor.sdk.android

import android.content.Context
import android.net.Uri

/**
 * Upload a `content://` URI the user picked.
 *
 * A content URI is not a path. `ContentResolver` is the only thing that can open it, the grant
 * may be revoked at any moment, and the underlying bytes may not be a file at all — so the
 * stream is staged through the application's cache and uploaded from there, rather than handed
 * to the native layer as a filename it cannot resolve.
 *
 * @param name the filename to upload under. **Give it the right extension**: the platform infers
 *   the MIME type from it, and a content URI's own display name often has none.
 * @throws NotFoundException when the resolver cannot open the URI — usually a grant that expired
 *   or was revoked between the picker returning and this call.
 */
public suspend fun Reactor.uploadContent(
    context: Context,
    uri: Uri,
    name: String,
    maxBytes: Long = Reactor.DEFAULT_UPLOAD_LIMIT_BYTES,
): FileRef {
    require(uri.scheme == "content") {
        "Expected a content:// URI, got '$uri'. Use uploadFile() for a filesystem path."
    }
    return uploadStream(name, context.cacheDir, maxBytes) {
        openContent(uri.toString()) { context.contentResolver.openInputStream(uri) }
    }
}

/**
 * Open a content stream, or raise [NotFoundException].
 *
 * `openInputStream` reports a missing URI by throwing [java.io.FileNotFoundException], and only
 * occasionally by returning null, so both have to become the documented error. A revoked grant
 * surfaces as a [SecurityException] instead, which is not a [ReactorException] and would escape
 * a caller handling those, so it becomes the same error. The original exception is kept as the
 * cause.
 */
internal fun openContent(
    uri: String,
    open: () -> java.io.InputStream?,
): java.io.InputStream {
    val stream =
        try {
            open()
        } catch (e: java.io.FileNotFoundException) {
            throw contentNotFound(uri, e)
        } catch (e: SecurityException) {
            throw contentNotFound(uri, e)
        }
    return stream ?: throw contentNotFound(uri, null)
}

private fun contentNotFound(
    uri: String,
    cause: Throwable?,
) = ErrorCode.toException(
    wire = "NOT_FOUND",
    message = "ContentResolver could not open $uri — the grant may have been revoked",
    operation = "upload_file",
    cause = cause,
)
