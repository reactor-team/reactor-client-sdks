package inc.reactor.sdk.internal

import inc.reactor.sdk.AbortedError
import inc.reactor.sdk.BadRequestError
import inc.reactor.sdk.DecodeFailedError
import inc.reactor.sdk.MessageTooLargeError
import inc.reactor.sdk.NotFoundError
import inc.reactor.sdk.Reactor
import inc.reactor.sdk.UnauthorizedError
import inc.reactor.sdk.uploadBytes
import inc.reactor.sdk.uploadFile
import inc.reactor.sdk.uploadStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

class UploadTest {
    init {
        val library = System.getProperty("reactor.jni.test.library")
        if (library == null) System.loadLibrary("reactor_jni_test") else System.load(library)
    }

    external fun configureUpload(mode: Int)

    external fun finishUploads()

    external fun lastPath(): ByteArray

    external fun sentBytes(): ByteArray

    external fun commandUploads(): ByteArray

    external fun orphanNextDestroy()

    external fun waitForUpload(): Boolean

    suspend fun client(): Reactor {
        configureUpload(0)
        return Reactor("model", local = true).also { it.connect() }
    }

    @Test fun byteUploadCopiesBorrowedDataAndFileRefsReachCommands() =
        runBlocking<Unit> {
            val client = client()
            try {
                configureUpload(1)
                val bytes = byteArrayOf(0, -128, -1, 2)
                val pending = async(start = CoroutineStart.UNDISPATCHED) { client.uploadBytes(bytes, "image 🌍.png", "image/png") }
                assertTrue(waitForUpload())
                bytes.fill(7)
                assertArrayEquals(byteArrayOf(0, -128, -1, 2), sentBytes())
                finishUploads()
                val ref = withTimeout(5000) { pending.await() }
                assertEquals("image 🌍.png", ref.name)
                assertEquals("image/png", ref.mimeType)
                assertEquals(4uL, ref.size)
                val args = JsonObject(mapOf("nested" to JsonArray(listOf(ref.toJson()))))
                val reply = client.sendCommand("use_file", args, mapOf("image" to ref))
                assertEquals(args, reply?.data)
                assertEquals(JsonObject(mapOf("image" to ref.toJson())), Json.parseToJsonElement(commandUploads().decodeToString()))
                assertTrue(
                    runCatching {
                        client.sendCommand("ambiguous", JsonObject(mapOf("image" to JsonPrimitive(1))), mapOf("image" to ref))
                    }.exceptionOrNull() is IllegalArgumentException,
                )
            } finally {
                client.close()
            }
        }

    @Test fun callerOwnedFileIsPreservedAndEmptyBytesAreSupported() =
        runBlocking<Unit> {
            val client = client()
            val file = Files.createTempFile("reactor-owned-", ".png").toFile()
            try {
                file.writeBytes(byteArrayOf(1, 2, 3))
                val ref = client.uploadFile(file)
                assertEquals(file.name, ref.name)
                assertEquals("image/png", ref.mimeType)
                assertEquals(3uL, ref.size)
                assertTrue(file.isFile)
                assertEquals(0uL, client.uploadBytes(byteArrayOf(), "empty.bin", "application/octet-stream").size)
            } finally {
                client.close()
                file.delete()
            }
        }

    @Test fun stagedUploadSurvivesCancellationUntilNativeCompletion() =
        runBlocking<Unit> {
            val client = client()
            val cache = Files.createTempDirectory("reactor-stage-test-").toFile()
            try {
                configureUpload(1)
                val closed = AtomicBoolean(false)
                val pending =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        client.uploadStream("large.bin", cache, 8L * 1024 * 1024) { generated(8L * 1024 * 1024, closed) }
                    }
                assertTrue(waitForUpload())
                val staged = File(lastPath().decodeToString())
                assertEquals(8L * 1024 * 1024, staged.length())
                assertTrue(closed.get())
                pending.cancel()
                pending.join()
                assertTrue(staged.isFile)
                finishUploads() // Fake reads the entire staged file only now, then completes.
                assertFalse(staged.exists())
                assertFalse(requireNotNull(staged.parentFile).exists())
                assertEquals(0, cache.listFiles()!!.size)
            } finally {
                client.close()
                cache.deleteRecursively()
            }
        }

    @Test fun closeWaitsForNativeQuiescenceBeforeDeletingStagedInputs() =
        runBlocking<Unit> {
            for (orphaned in listOf(false, true)) {
                val client = client()
                val cache = Files.createTempDirectory("reactor-close-stage-").toFile()
                try {
                    configureUpload(1)
                    val pending =
                        async(
                            start = CoroutineStart.UNDISPATCHED,
                        ) { runCatching { client.uploadStream("source.bin", cache) { byteArrayOf(1, 2).inputStream() } } }
                    assertTrue(waitForUpload())
                    val staged = File(lastPath().decodeToString())
                    if (orphaned) orphanNextDestroy()
                    client.close()
                    assertTrue(withTimeout(5000) { pending.await() }.exceptionOrNull() is AbortedError)
                    assertEquals(orphaned, staged.exists())
                    if (orphaned) finishUploads()
                    assertFalse(staged.exists())
                    assertEquals(0, cache.listFiles()!!.size)
                } finally {
                    client.close()
                    cache.deleteRecursively()
                }
            }
        }

    @Test fun stagingLimitPermissionErrorsAndMissingFilesAreActionableAndCleaned() =
        runBlocking<Unit> {
            val client = client()
            val cache = Files.createTempDirectory("reactor-invalid-stage-").toFile()
            try {
                assertTrue(runCatching { client.uploadFile(File(cache, "missing.bin")) }.exceptionOrNull() is NotFoundError)
                assertTrue(
                    runCatching {
                        client.uploadStream("source.bin", cache) { throw SecurityException("URI permission expired") }
                    }.exceptionOrNull() is UnauthorizedError,
                )
                val closed = AtomicBoolean(false)
                assertTrue(
                    runCatching {
                        client.uploadStream("too-large.bin", cache, 100) { generated(101, closed) }
                    }.exceptionOrNull() is MessageTooLargeError,
                )
                assertTrue(closed.get())
                assertEquals(0, cache.listFiles()!!.size)
                assertTrue(
                    runCatching {
                        client.uploadStream("../escape", cache) { byteArrayOf().inputStream() }
                    }.exceptionOrNull() is IllegalArgumentException,
                )
                assertTrue(
                    runCatching {
                        client.uploadBytes(
                            byteArrayOf(),
                            "bad\u0000",
                            "image/png",
                        )
                    }.exceptionOrNull() is IllegalArgumentException,
                )
                assertTrue(runCatching { client.uploadBytes(byteArrayOf(), "ok", "") }.exceptionOrNull() is IllegalArgumentException)
            } finally {
                client.close()
                cache.deleteRecursively()
            }
        }

    @Test fun nativeErrorsAndMalformedReferencesCleanStagedFiles() =
        runBlocking<Unit> {
            val client = client()
            val cache = Files.createTempDirectory("reactor-error-stage-").toFile()
            try {
                for (mode in listOf(2, 3)) {
                    configureUpload(mode)
                    val failure =
                        runCatching {
                            client.uploadStream(
                                "source.bin",
                                cache,
                            ) { byteArrayOf(1).inputStream() }
                        }.exceptionOrNull()
                    assertTrue(if (mode == 2) failure is BadRequestError else failure is DecodeFailedError)
                    assertEquals(0, cache.listFiles()!!.size)
                }
            } finally {
                client.close()
                cache.deleteRecursively()
            }
        }

    @Test fun uploadsRejectMissingOrClosedHandlesBeforeOpeningSources() =
        runBlocking<Unit> {
            configureUpload(0)
            val client = Reactor("model", local = true)
            val cache = Files.createTempDirectory("reactor-no-handle-").toFile()
            try {
                val unopened = AtomicBoolean(true)

                suspend fun attempt() =
                    client.uploadStream("source.bin", cache) {
                        unopened.set(false)
                        byteArrayOf(1).inputStream()
                    }
                assertTrue(runCatching { attempt() }.exceptionOrNull() is inc.reactor.sdk.InvalidStateError)
                client.connect()
                client.close()
                assertTrue(runCatching { attempt() }.exceptionOrNull() is inc.reactor.sdk.InvalidStateError)
                assertTrue(unopened.get())
                assertEquals(0, cache.listFiles()!!.size)
            } finally {
                client.close()
                cache.deleteRecursively()
            }
        }

    private fun generated(
        size: Long,
        closed: AtomicBoolean,
    ): InputStream =
        object : InputStream() {
            private var remaining = size

            override fun read(): Int = error("Must use bounded bulk reads")

            override fun read(
                buffer: ByteArray,
                offset: Int,
                length: Int,
            ): Int {
                check(length <= 64 * 1024) { "Staging allocated an unbounded read buffer" }
                if (remaining == 0L) return -1
                val count = minOf(length.toLong(), remaining).toInt()
                buffer.fill(42, offset, offset + count)
                remaining -= count
                return count
            }

            override fun close() {
                closed.set(true)
            }
        }
}
