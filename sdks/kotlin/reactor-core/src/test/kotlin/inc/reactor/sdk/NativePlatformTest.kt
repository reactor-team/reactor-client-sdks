package inc.reactor.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NativePlatformTest {
    @Test
    fun platformAliasesAndLibraryOrder() {
        assertEquals("macos-arm64", nativePlatform("Mac OS X", "aarch64"))
        assertEquals("linux-x64", nativePlatform("Linux", "amd64"))
        assertEquals("windows-x64", nativePlatform("Windows 11", "x86_64"))
        assertEquals(listOf("reactor_ffi.dll", "reactor_jni.dll"), nativeLibraries("windows-x64"))
    }

    @Test
    fun unsupportedPlatformsRefuseBeforeLoading() {
        assertThrows(IllegalStateException::class.java) { nativePlatform("Windows 11", "arm64") }
        assertThrows(IllegalStateException::class.java) { nativePlatform("Linux", "riscv64") }
        assertThrows(IllegalStateException::class.java) { nativePlatform("FreeBSD", "amd64") }
    }
}
