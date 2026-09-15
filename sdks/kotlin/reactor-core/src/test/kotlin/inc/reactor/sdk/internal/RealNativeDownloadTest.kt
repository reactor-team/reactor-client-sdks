package inc.reactor.sdk.internal

import inc.reactor.sdk.Operations
import inc.reactor.sdk.downloadResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.ServerSocket
import java.nio.file.Files
import java.util.Collections
import kotlin.concurrent.thread

/** Real Rust HTTP downloader through JNI; run in the isolated testRealNative process. */
class RealNativeDownloadTest {
    @Test fun realDownloaderWritesInitFirstAndKeepsBearerSameOrigin() =
        runBlocking<Unit> {
            val directory = System.getProperty("reactor.jni.real.directory")
            assumeTrue(directory != null) // Local desktop transport check, separate from fake JNI instrumentation.
            System.load("$directory/${System.mapLibraryName("reactor_ffi")}")
            System.load("$directory/${System.mapLibraryName("reactor_jni")}")
            val requests = Collections.synchronizedList(mutableListOf<Pair<String, String>>())
            TestServer { path, headers ->
                requests.add("cross:$path" to headers)
                "two"
            }.use { cross ->
                TestServer { path, headers ->
                    requests.add(path to headers)
                    when (path) {
                        "/hls/clip.m3u8" ->
                            // Producer shape copied from crates/reactor-core/src/recording.rs FMP4_PLAYLIST
                            // (coordinator clips_handler.go/runtime recorder.py), with a presigned cross-origin fragment.
                            "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:4\n#EXT-X-PLAYLIST-TYPE:VOD\n" +
                                "#EXT-X-MAP:URI=\"/clips/chunks/sid/init.mp4\"\n#EXTINF:4.000,\nchunk_00000.m4s\n" +
                                "#EXTINF:4.000,\n${cross.url}/chunk_00001.m4s?signature=test\n#EXT-X-ENDLIST\n"
                        "/clips/chunks/sid/init.mp4" -> "init|"
                        "/hls/chunk_00000.m4s" -> "one|"
                        else -> error("Unexpected path $path")
                    }
                }.use { server ->
                    val output = Files.createTempFile("real-recording-", ".mp4").toFile()
                    val owner = Operations()
                    try {
                        val result =
                            withTimeout(10000) {
                                owner.registry.await(::downloadResult) { id ->
                                    NativeClient.download(
                                        0,
                                        "${server.url}/hls/clip.m3u8".encodeToByteArray(),
                                        "test-bearer".encodeToByteArray(),
                                        output.absolutePath.encodeToByteArray(),
                                        0.0,
                                        5.0,
                                        true,
                                        owner.receiver(id),
                                    )
                                }
                            }
                        assertEquals("init|one|two", output.readText())
                        assertEquals(12uL, result.bytes)
                        assertEquals(3u, result.segments)
                        assertEquals(4, requests.size)
                        assertEquals("/clips/chunks/sid/init.mp4", requests[1].first)
                        requests
                            .filter {
                                !it.first.startsWith(
                                    "cross:",
                                )
                            }.forEach { assertTrue(it.second.contains("authorization: bearer test-bearer")) }
                        assertFalse(requests.single { it.first.startsWith("cross:") }.second.contains("authorization:"))
                    } finally {
                        owner.close()
                        output.delete()
                    }
                }
            }
        }
}

private class TestServer(
    private val respond: (String, String) -> String,
) : AutoCloseable {
    private val server = ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
    val url = "http://127.0.0.1:${server.localPort}"
    private val worker =
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket =
                    try {
                        server.accept()
                    } catch (_: java.net.SocketException) {
                        break
                    }
                socket.use {
                    it.soTimeout = 10000
                    val reader = it.getInputStream().bufferedReader()
                    val path = reader.readLine().split(' ')[1]
                    val headers = StringBuilder()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        headers.append(line.lowercase()).append('\n')
                    }
                    val body = respond(path, headers.toString()).encodeToByteArray()
                    it.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".encodeToByteArray(),
                    )
                    it.getOutputStream().write(body)
                }
            }
        }

    override fun close() {
        server.close()
        worker.join(10000)
    }
}
