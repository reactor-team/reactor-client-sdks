package inc.reactor.sdk

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Production gate. The workflow supplies credentials; local runs must opt in explicitly. */
class LiveIntegrationTest {
    @Test
    fun connectCommandAndReceiveStatus() = runBlocking {
        val model = requireNotNull(System.getenv("REACTOR_MODEL")) { "REACTOR_MODEL is required" }
        val token = requireNotNull(System.getenv("REACTOR_TOKEN")) { "REACTOR_TOKEN is required" }
        ReactorNative.initialize()
        val client = Reactor(model, TokenProvider { token })
        try {
            client.connect()
            assertEquals(ReactorStatus.READY, client.status)
            assertNotNull(client.sendCommand("inspect", buildJsonObject {}))
        } finally {
            client.close()
        }
    }
}
