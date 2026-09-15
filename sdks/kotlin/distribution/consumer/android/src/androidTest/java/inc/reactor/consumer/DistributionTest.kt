package inc.reactor.consumer

import android.content.Intent
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class DistributionTest {
    @Test
    fun minifiedApplicationLoadsBundledNatives() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent().setClassName(context.packageName, "inc.reactor.consumer.MainActivity")
        ActivityScenario.launch<android.app.Activity>(intent).use { scenario ->
            var text = ""
            repeat(100) {
                scenario.onActivity { activity ->
                    val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                    text = (content.getChildAt(0) as TextView).text.toString()
                }
                if (text.startsWith("REACTOR_") || text.startsWith("FAILED:")) return@repeat
                Thread.sleep(100)
            }
            assertEquals("REACTOR_DISTRIBUTION_OK", text)
        }
    }
}
