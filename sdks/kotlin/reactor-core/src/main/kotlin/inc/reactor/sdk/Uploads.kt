package inc.reactor.sdk

import inc.reactor.sdk.internal.NativeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** The caller owns this file; cancellation/close never deletes it. Keep it readable until completion. */
suspend fun Reactor.uploadFile(file: File): FileRef =
    withContext(Dispatchers.IO) {
        requireHandle()
        require(
            '\u0000' !in file.path && !file.path.startsWith("content:"),
        ) { "Upload a filesystem file; use uploadContent for Android URIs" }
        val path = file.toPath().toAbsolutePath()
        localUploadIo {
            if (!Files.isRegularFile(path)) throw FileNotFoundException("Upload source is not a regular file: $path")
            Files.newInputStream(path).use { } // Diagnose missing/read permission before entering native work.
        }
        awaitResult(::fileRef) { native, receiver -> NativeClient.uploadFile(native, path.toString().encodeToByteArray(), false, receiver) }
    }

/** JNI and the FFI copy these bytes during the call. Do not mutate them concurrently with the call. */
suspend fun Reactor.uploadBytes(
    bytes: ByteArray,
    name: String,
    mimeType: String,
): FileRef {
    requireHandle()
    require(name.isNotBlank() && '\u0000' !in name) { "Upload name must be nonempty and contain no NUL" }
    require(mimeType.isNotBlank() && '\u0000' !in mimeType) { "MIME type must be nonempty and contain no NUL" }
    return withContext(Dispatchers.IO) {
        awaitResult(
            ::fileRef,
        ) { native, receiver -> NativeClient.uploadBytes(native, bytes, name.encodeToByteArray(), mimeType.encodeToByteArray(), receiver) }
    }
}

/** Stream into a private cache file in 64 KiB blocks, then upload it. The opener runs on IO and its stream is always closed.
 * The limit bounds disk staging; the current FFI reads a file into memory before uploading it.
 * Name (including its extension) determines the native file upload name and inferred MIME type.
 */
suspend fun Reactor.uploadStream(
    name: String,
    cacheDirectory: File,
    maxBytes: Long = 64L * 1024 * 1024,
    open: () -> InputStream,
): FileRef =
    withContext(Dispatchers.IO) {
        requireHandle()
        require(maxBytes >= 0) { "Upload staging limit must be nonnegative" }
        require(
            name.isNotBlank() &&
                name !in setOf(".", "..") &&
                !name.endsWith('.') &&
                !name.endsWith(' ') &&
                name.none { it.code < 32 || it in "<>:\"/\\|?*" },
        ) { "Use a single portable filename with an extension, not a path" }
        var directory: Path? = null
        var staged: Path? = null
        var transferred = false
        try {
            val context = currentCoroutineContext()
            localUploadIo {
                directory = Files.createTempDirectory(cacheDirectory.toPath(), "reactor-upload-")
                staged = requireNotNull(directory).resolve(name)
                open().use { input ->
                    Files.newOutputStream(requireNotNull(staged)).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            context.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            if (count.toLong() >
                                maxBytes - total
                            ) {
                                throw MessageTooLargeError(
                                    ErrorDetails(
                                        "MESSAGE_TOO_LARGE",
                                        "Upload exceeds the staging limit of $maxBytes bytes",
                                        operation = "upload_file",
                                    ),
                                )
                            }
                            output.write(buffer, 0, count)
                            total += count
                        }
                    }
                }
            }
            context.ensureActive()
            awaitResult(::fileRef) { native, receiver ->
                NativeClient.uploadFile(native, requireNotNull(staged).toString().encodeToByteArray(), true, receiver)
                transferred = true // JNI now owns deletion, even if the awaiter is cancelled or closed.
            }
        } finally {
            if (!transferred) {
                staged?.let { Files.deleteIfExists(it) }
                directory?.let { Files.deleteIfExists(it) }
            }
        }
    }

private inline fun <T> localUploadIo(action: () -> T): T =
    try {
        action()
    } catch (failure: SecurityException) {
        throw UnauthorizedError(
            ErrorDetails(
                "UNAUTHORIZED",
                "Read permission denied; grant access to the upload source: ${failure.message}",
                operation = "upload_file",
            ),
        )
    } catch (failure: AccessDeniedException) {
        throw UnauthorizedError(
            ErrorDetails("UNAUTHORIZED", "Read permission denied for upload: ${failure.message}", operation = "upload_file"),
        )
    } catch (failure: NoSuchFileException) {
        throw NotFoundError(
            ErrorDetails("NOT_FOUND", "Upload source or cache directory is missing: ${failure.message}", operation = "upload_file"),
        )
    } catch (failure: FileNotFoundException) {
        throw NotFoundError(ErrorDetails("NOT_FOUND", "Cannot open upload source: ${failure.message}", operation = "upload_file"))
    } catch (failure: IOException) {
        throw BadRequestError(ErrorDetails("BAD_REQUEST", "Cannot read or stage upload: ${failure.message}", operation = "upload_file"))
    }
