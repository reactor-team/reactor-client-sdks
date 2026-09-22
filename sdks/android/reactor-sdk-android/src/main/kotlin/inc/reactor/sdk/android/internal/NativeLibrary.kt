package inc.reactor.sdk.android.internal

/**
 * Loads the native stack, once, in the only order that works.
 *
 * ```
 * WebRtcClassLoader        the relocated WebRTC classes must resolve before native init looks
 *                          them up by name through JNI
 * libreactor_ffi.so        its JNI_OnLoad hands the JavaVM to reactor-webrtc
 * libreactor_jni.so        the bridge
 * NativeAbi.checkAbi()     refuse a library older than the header, naming both numbers
 * ```
 *
 * Serialized and idempotent, and a failure is **terminal for the process**: the first exception
 * is recorded and rethrown on every later call rather than retried. A half-initialised WebRTC —
 * class loader resolved, JavaVM not handed over — aborts the process the next time native code
 * touches it, and an abort is a worse diagnostic than the exception that caused it.
 */
internal object NativeLibrary {
    /**
     * The relocated WebRTC bootstrap class. Not `org.webrtc.*`: reactor-webrtc builds its Android
     * archive with `android_jni_package_prefix=inc.reactor`, so the name the native library looks
     * up is this one. scripts/check-android-native.py verifies the .so and the JAR agree on it
     * (REA-6249) before either reaches a device.
     */
    private const val WEBRTC_CLASS_LOADER = "inc.reactor.org.webrtc.WebRtcClassLoader"

    @Volatile
    private var failure: Throwable? = null

    @Volatile
    private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        failure?.let { throw it }
        if (loaded) return
        try {
            Class.forName(WEBRTC_CLASS_LOADER, false, NativeLibrary::class.java.classLoader)
            System.loadLibrary("reactor_ffi")
            System.loadLibrary("reactor_jni")
            NativeAbi.checkAbi()
            loaded = true
        } catch (t: ClassNotFoundException) {
            failure =
                UnsatisfiedLinkError(
                    "$WEBRTC_CLASS_LOADER is missing from the classpath. The AAR must carry the " +
                        "libwebrtc JAR whose classes were relocated to match libreactor_ffi.so — see " +
                        "scripts/build-android-ffi.sh.",
                ).initCause(t)
            throw failure!!
        } catch (t: Throwable) {
            failure = t
            throw t
        }
    }

    /** The ABI version of the loaded library, loading it first if necessary. */
    fun abiVersion(): Int {
        ensureLoaded()
        return NativeAbi.abiVersion()
    }
}
