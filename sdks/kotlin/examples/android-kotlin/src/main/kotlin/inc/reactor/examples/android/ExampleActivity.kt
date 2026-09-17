package inc.reactor.examples.android

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope

/** Minimal Activity host used by the emulator sample; credentials arrive via Intent extras. */
class ExampleActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val preview = FrameView(this)
        val status = TextView(this).apply { text = "Connecting…" }
        setContentView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(status); addView(preview) })
        val model = intent.getStringExtra("reactor.model") ?: error("Pass reactor.model")
        val token = intent.getStringExtra("reactor.token") ?: error("Pass reactor.token")
        val scenario = intent.getIntExtra("reactor.scenario", 1)
        Scenarios(this, lifecycleScope, preview).runScenario(scenario, model, token)
    }
}
