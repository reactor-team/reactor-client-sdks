package inc.reactor.sdk.android

import android.content.Context
import android.net.Uri
import inc.reactor.sdk.FileRef
import inc.reactor.sdk.Reactor
import inc.reactor.sdk.uploadStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/** Opens a granted content URI with ContentResolver and stages it in the application's cache.
 * Supply a filename with the correct extension: the FFI infers MIME type from it.
 */
suspend fun Reactor.uploadContent(
    context: Context,
    uri: Uri,
    name: String,
    maxBytes: Long = 64L * 1024 * 1024,
): FileRef {
    require(uri.scheme == "content") { "Expected content://; use uploadFile for filesystem paths" }
    return withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        uploadStream(name, context.cacheDir, maxBytes) {
            resolver.openInputStream(uri) ?: throw FileNotFoundException("ContentResolver returned no stream for $uri")
        }
    }
}
