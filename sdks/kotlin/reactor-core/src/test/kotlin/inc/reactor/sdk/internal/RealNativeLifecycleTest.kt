package inc.reactor.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Run separately from the fake JNI suite, in its own JVM or instrumentation process. */
class RealNativeLifecycleTest {
    class Receiver {
        @Suppress("UNUSED_PARAMETER")
        fun accept(
            kind: Int,
            text: ByteArray?,
            data: ByteArray?,
        ) = Unit
    }

    @Test
    fun realSyntheticLifecycle() {
        val android = System.getProperty("java.vm.name") == "Dalvik"
        val directory = System.getProperty("reactor.jni.real.directory")
        assumeTrue(android || directory != null)
        if (android) {
            Class.forName("inc.reactor.org.webrtc.WebRtcClassLoader")
            System.loadLibrary("reactor_ffi")
            System.loadLibrary("reactor_jni")
        } else {
            System.load("$directory/${System.mapLibraryName("reactor_ffi")}")
            System.load("$directory/${System.mapLibraryName("reactor_jni")}")
        }
        NativeAbi.checkAbi()
        val receiver = Receiver()
        repeat(10) {
            val handle =
                NativeClient.create(
                    "http://127.0.0.1:1".encodeToByteArray(),
                    "probe".encodeToByteArray(),
                    null,
                    true,
                    SDK_VERSION.encodeToByteArray(),
                    receiver,
                )
            try {
                assertEquals("disconnected", NativeClient.status(handle).decodeToString())
                assertNull(NativeClient.session(handle))
            } finally {
                assertEquals(0, NativeClient.destroy(handle))
            }
        }
    }
}
