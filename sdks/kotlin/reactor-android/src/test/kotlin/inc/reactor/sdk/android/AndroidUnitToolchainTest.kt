package inc.reactor.sdk.android

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidUnitToolchainTest {
    @Test
    fun kotlinTestsAreDiscoveredInAndroidModule() {
        assertEquals("reactor-android", listOf("reactor", "android").joinToString("-"))
    }
}
