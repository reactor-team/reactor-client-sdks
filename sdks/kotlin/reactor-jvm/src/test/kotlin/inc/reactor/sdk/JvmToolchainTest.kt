package inc.reactor.sdk

import org.junit.Assert.assertTrue
import org.junit.Test

class JvmToolchainTest {
    @Test
    fun testRunnerUsesSupportedJava() {
        assertTrue(System.getProperty("java.specification.version").toInt() >= 17)
    }
}
