package inc.reactor.consumer

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import inc.reactor.sdk.Reactor
import inc.reactor.sdk.NativeRuntime
import inc.reactor.sdk.timeMicros
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class MainActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val result = TextView(this)
        result.text = "Testing bundled natives"
        setContentView(result)
        Thread {
            val message = runCatching {
                NativeRuntime.initialize()
                check(timeMicros() > 0)
                runBlocking {
                    repeat(3) {
                        val client = Reactor("probe", local = true, apiUrl = "http://127.0.0.1:1")
                        try {
                            check(runCatching { withTimeout(2000) { client.connect() } }.isFailure)
                        } finally {
                            client.close()
                        }
                    }
                }
                "REACTOR_DISTRIBUTION_OK"
            }.getOrElse { "FAILED: $it" }
            runOnUiThread { result.text = message }
        }.start()
    }
}
