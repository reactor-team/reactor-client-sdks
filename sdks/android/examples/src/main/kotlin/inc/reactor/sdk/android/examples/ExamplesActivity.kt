package inc.reactor.sdk.android.examples

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * A button per scenario, a log below it, and a frame view above.
 *
 * Deliberately not one Activity with the scenarios inlined: each is meant to be readable on its
 * own, and this file exists only to start one and show what it says.
 */
public class ExamplesActivity : Activity() {
    private lateinit var log: TextView
    private lateinit var frames: ImageView
    private var running: Job? = null

    /**
     * Scoped by hand rather than through `lifecycleScope`, so the examples pull in no androidx at
     * all: what a reader copies out of here should be the SDK, not a dependency.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        log = TextView(this)
        frames =
            ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 480)
            }

        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
        root.addView(frames)
        for (scenario in Scenarios.all) {
            root.addView(
                Button(this).apply {
                    text = "${scenario.number} · ${scenario.title}  (${scenario.model})"
                    setOnClickListener { start(scenario) }
                },
            )
        }
        root.addView(log)
        setContentView(ScrollView(this).apply { addView(root) })

        if (BuildConfig.REACTOR_API_KEY.isEmpty()) {
            append(
                "No API key. Install with:\n" +
                    "  gradle --project-dir sdks/android :examples:installDebug -PreactorApiKey=rk_…\n\n" +
                    "A shipped app would mint a short-lived token on its own backend instead — " +
                    "a key in an APK is a key anyone can extract.",
            )
        }
    }

    private fun start(scenario: Scenarios.Scenario) {
        running?.cancel()
        log.text = ""
        append("── ${scenario.number} ${scenario.title} ──")
        running =
            scope.launch {
                try {
                    scenario.run(BuildConfig.REACTOR_API_KEY, ::append, FrameSink(frames))
                    append("done")
                } catch (c: CancellationException) {
                    // Rethrown, not reported: this one is the *previous* scenario being stood down
                    // because a button was pressed, and swallowing it would also keep the coroutine
                    // machinery from seeing its own cancellation.
                    throw c
                } catch (t: Throwable) {
                    // Reported rather than swallowed: when an example fails, *why* is the lesson.
                    append("failed: $t")
                }
            }
    }

    override fun onDestroy() {
        // Cancels whatever scenario is running, which lets withReactor's finally disconnect it.
        // A creator that never disconnects orphans the session for the rest of its lease.
        scope.cancel()
        super.onDestroy()
    }

    private fun append(line: String) {
        runOnUiThread { log.append("$line\n") }
    }
}
