package inc.reactor.sdk.android

import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The parts of [Reactor] that need no native library: the refusals, and the status mapping.
 *
 * Everything that touches the handle is exercised on a device — see `NativeBoundaryTest`.
 */
class ReactorTest {
    /**
     * Built on [Dispatchers.Unconfined] rather than the default.
     *
     * The default is `Dispatchers.Main.immediate`, which is the right answer on a device and does
     * not exist in a JVM unit test — constructing a Reactor here without saying otherwise throws
     * "Dispatchers.Main was accessed when the platform dispatcher was absent". That the
     * constructor takes a dispatcher at all is what makes this testable off-device, and this is
     * the parameter earning its place.
     */
    private fun newReactor() = Reactor(ReactorOptions(jwt = "t"), Dispatchers.Unconfined)

    @Test
    fun `a bare model name is refused, naming why`() {
        val reactor = newReactor()
        val error =
            assertThrows(IllegalArgumentException::class.java) {
                kotlinx.coroutines.runBlocking { reactor.connect("echo") }
            }
        val message = error.message!!
        // The refusal has to say what to do about it: "invalid model name" would send a reader
        // looking for a typo rather than for the owner prefix.
        assert(message.contains("owner/name")) { message }
        assert(message.contains("403")) { message }
    }

    @Test
    fun `an owner-qualified name is accepted as far as the native layer`() {
        // Not a connection test — there is no native library here. It asserts only that the name
        // check does not reject a correct name, which is the half that can be tested on the JVM.
        val reactor = newReactor()
        val error =
            runCatching {
                kotlinx.coroutines.runBlocking { reactor.connect("reactor/echo") }
            }.exceptionOrNull()
        assert(error !is IllegalArgumentException) {
            "an owner-qualified name must pass the name check, got: $error"
        }
    }

    @Test
    fun `reconnect before connect is an INVALID_STATE error, not a crash`() {
        val reactor = newReactor()
        val error =
            assertThrows(InvalidStateException::class.java) {
                kotlinx.coroutines.runBlocking { reactor.reconnect() }
            }
        assertEquals("INVALID_STATE", error.code)
        assertEquals("reconnect", error.operation)
    }

    @Test
    fun `closing twice is a no-op`() {
        val reactor = newReactor()
        reactor.close()
        reactor.close()
    }

    @Test
    fun `connecting after close is refused`() {
        val reactor = newReactor()
        reactor.close()
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { reactor.connect("reactor/echo") }
        }
    }

    @Test
    fun `status starts disconnected`() {
        newReactor().use {
            assertEquals(ConnectionStatus.DISCONNECTED, it.status.value)
        }
    }

    @Test
    fun `every wire status maps to its own state`() {
        assertEquals(ConnectionStatus.DISCONNECTED, ConnectionStatus.of("disconnected"))
        assertEquals(ConnectionStatus.CONNECTING, ConnectionStatus.of("connecting"))
        assertEquals(ConnectionStatus.WAITING, ConnectionStatus.of("waiting"))
        assertEquals(ConnectionStatus.READY, ConnectionStatus.of("ready"))
    }

    /**
     * A status this SDK does not know must read as unusable rather than throwing: it arrives on
     * an event thread, where there is nowhere to report a parse failure.
     */
    @Test
    fun `an unknown status is treated as disconnected`() {
        assertEquals(ConnectionStatus.DISCONNECTED, ConnectionStatus.of("something-new"))
        assertEquals(ConnectionStatus.DISCONNECTED, ConnectionStatus.of(null))
    }
}
