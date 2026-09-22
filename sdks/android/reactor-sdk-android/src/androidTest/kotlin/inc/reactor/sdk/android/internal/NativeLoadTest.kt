package inc.reactor.sdk.android.internal

import androidx.test.ext.junit.runners.AndroidJUnit4
import inc.reactor.sdk.android.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The native stack loads on a device, and the library agrees with the header.
 *
 * This is the one thing in A02 that no unit test can reach. Everything the build checks — ELF
 * alignment, the WebRtcClassLoader name, the packaged AAR's contents — is static; whether the two
 * libraries actually dlopen in the right order on Android, with WebRTC's JNI_OnLoad handing over
 * the JavaVM exactly once, is only answerable by running it.
 *
 * Run against a **16 KB page size** system image (see sdk-packages-emulator.txt). On an ordinary
 * 4 KB image a misaligned library loads fine and this test passes without proving anything; on
 * that one it fails the way an Android 15 device would.
 */
@RunWith(AndroidJUnit4::class)
class NativeLoadTest {
    @Test
    fun loadsAndReportsTheHeadersAbiVersion() {
        // Throws if either library is missing, if the relocated WebRTC classes are absent, or if
        // the ABI does not match — all of which are UnsatisfiedLinkError with a message naming
        // what to do about it.
        NativeLibrary.ensureLoaded()

        assertEquals(
            "The packaged libreactor_ffi.so reports a different ABI than the header this AAR " +
                "was built against — the staged library is stale. Run: mise run build:android:native",
            BuildConfig.REACTOR_ABI_VERSION,
            NativeLibrary.abiVersion(),
        )
    }

    @Test
    fun loadingTwiceIsIdempotent() {
        NativeLibrary.ensureLoaded()
        NativeLibrary.ensureLoaded()
        assertEquals(BuildConfig.REACTOR_ABI_VERSION, NativeLibrary.abiVersion())
    }
}
