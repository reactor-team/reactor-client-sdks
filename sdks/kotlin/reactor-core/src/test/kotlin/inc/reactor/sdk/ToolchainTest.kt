package inc.reactor.sdk

import org.junit.Assert.assertEquals
import org.junit.Test

/** Proves that Kotlin compilation and JUnit discovery run in the shared module. */
class ToolchainTest {
    @Test
    fun kotlinCollectionsRunOnJvm() {
        assertEquals(listOf("video", "audio"), linkedSetOf("video", "audio").toList())
    }
}
