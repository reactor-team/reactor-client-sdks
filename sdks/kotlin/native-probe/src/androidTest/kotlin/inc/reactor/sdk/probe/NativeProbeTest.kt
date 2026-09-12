package inc.reactor.sdk.probe

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeProbeTest {
    private external fun lifecycle(): Int

    @Test
    fun syntheticClientLifecycle() {
        Class.forName("inc.reactor.org.webrtc.WebRtcClassLoader")
        Class.forName("inc.reactor.org.jni_zero.JniZero")
        System.loadLibrary("reactor_ffi")
        System.loadLibrary("reactor_probe")
        // Exercise repeated allocation and teardown, without credentials or a microphone.
        repeat(10) { assertEquals(0, lifecycle()) }
    }
}
