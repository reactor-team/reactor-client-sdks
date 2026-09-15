import inc.reactor.sdk.Reactor
import inc.reactor.sdk.ReactorNative
import inc.reactor.sdk.ReactorStatus
import inc.reactor.sdk.timeMicros
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

fun main(args: Array<String>) {
    if (args.isNotEmpty()) {
        val failure = runCatching { ReactorNative.initialize() }.exceptionOrNull()
        check(failure is UnsatisfiedLinkError && failure.message.orEmpty().contains(args[0])) { "Unexpected native error: $failure" }
        println("Expected native error verified")
        return
    }
    ReactorNative.initialize()
    ReactorNative.initialize()
    check(timeMicros() > 0)
    runBlocking {
        repeat(3) {
            val reactor = Reactor("probe", local = true, apiUrl = "http://127.0.0.1:1")
            try {
                check(reactor.status == ReactorStatus.DISCONNECTED)
                check(runCatching { withTimeout(2000) { reactor.connect() } }.isFailure)
            } finally {
                reactor.close()
            }
        }
    }
    // Resolve the optional desktop adapter through published transitive metadata too.
    Class.forName("inc.reactor.sdk.desktop.DesktopMicrophone")
    println("REACTOR_DISTRIBUTION_OK")
}
