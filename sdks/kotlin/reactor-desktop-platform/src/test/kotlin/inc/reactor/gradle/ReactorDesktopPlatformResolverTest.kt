package inc.reactor.gradle

import org.junit.Assert.assertEquals
import org.junit.Test

class ReactorDesktopPlatformResolverTest {
    @Test
    fun detectsMacArm64() {
        assertEquals("macos-arm64", ReactorDesktopPlatformResolver.detect("Mac OS X", "aarch64"))
    }

    @Test
    fun detectsLinuxX64Aliases() {
        assertEquals("linux-x64", ReactorDesktopPlatformResolver.detect("Linux", "amd64"))
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsWindowsArm64() {
        ReactorDesktopPlatformResolver.detect("Windows 11", "aarch64")
    }
}
