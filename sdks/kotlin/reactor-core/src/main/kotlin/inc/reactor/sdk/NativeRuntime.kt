package inc.reactor.sdk

import inc.reactor.sdk.internal.NativeAbi
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/** Explicit process initialization; no network access or source builds occur here. */
object NativeRuntime {
    private var initialized = false
    private var failure: Throwable? = null

    /**
     * Android loads the AAR's libraries. Desktop extracts the selected native artifact.
     * REACTOR_NATIVE_DIR overrides the desktop bundle with a directory containing both libraries.
     * Call once before using any client or media clock, preferably off the Android main thread.
     */
    @Synchronized
    fun initialize() {
        if (initialized) return
        failure?.let { throw UnsatisfiedLinkError("Reactor native initialization previously failed; restart the process").initCause(it) }
        try {
            if (System.getProperty("java.vm.name") == "Dalvik") {
                Class.forName("inc.reactor.org.webrtc.WebRtcClassLoader")
                System.loadLibrary("reactor_ffi")
                System.loadLibrary("reactor_jni")
            } else {
                val platform = nativePlatform(System.getProperty("os.name"), System.getProperty("os.arch"))
                val override = System.getenv("REACTOR_NATIVE_DIR")
                val directory = if (override != null) Path.of(override).toAbsolutePath() else extract(platform)
                for (name in nativeLibraries(platform)) {
                    val library = directory.resolve(name)
                    check(Files.isRegularFile(library)) { "Missing native library: $library" }
                    System.load(library.toString())
                }
            }
            NativeAbi.checkAbi()
            initialized = true
        } catch (error: Throwable) {
            failure = error
            throw UnsatisfiedLinkError("Cannot initialize Reactor natives: ${error.message}").initCause(error)
        }
    }

    private fun extract(platform: String): Path {
        // Private, unique directory avoids trusting files in a shared/predictable extraction cache.
        val directory = Files.createTempDirectory("reactor-native-").toAbsolutePath()
        directory.toFile().deleteOnExit()
        for (name in nativeLibraries(platform)) {
            val resource = "/inc/reactor/natives/$platform/$name"
            val input =
                NativeRuntime::class.java.getResourceAsStream(resource)
                    ?: error("Missing $resource; add inc.reactor:reactor-native-$platform at the SDK version")
            val file = directory.resolve(name)
            input.use { Files.copy(it, file) }
            // Libraries remain available for the entire process. Windows may retain mapped DLLs.
            file.toFile().deleteOnExit()
        }
        return directory
    }
}

internal fun nativePlatform(
    os: String,
    architecture: String,
): String {
    val arch =
        when (architecture.lowercase(Locale.ROOT)) {
            "aarch64", "arm64" -> "arm64"
            "amd64", "x86_64" -> "x64"
            else -> error("Unsupported Reactor architecture: $architecture")
        }
    return when {
        os.startsWith("Mac", ignoreCase = true) -> "macos-$arch"
        os.startsWith("Linux", ignoreCase = true) -> "linux-$arch"
        os.startsWith("Windows", ignoreCase = true) && arch == "x64" -> "windows-x64"
        else -> error("Unsupported Reactor platform: $os/$architecture")
    }
}

internal fun nativeLibraries(platform: String): List<String> =
    when {
        platform.startsWith("windows") -> listOf("reactor_ffi.dll", "reactor_jni.dll")
        platform.startsWith("macos") -> listOf("libreactor_ffi.dylib", "libreactor_jni.dylib")
        else -> listOf("libreactor_ffi.so", "libreactor_jni.so")
    }
