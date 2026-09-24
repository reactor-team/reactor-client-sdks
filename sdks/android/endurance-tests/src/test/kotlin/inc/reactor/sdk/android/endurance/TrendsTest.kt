package inc.reactor.sdk.android.endurance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The verdicts, against runs whose shape is known because this file made it.
 *
 * These exist because the thing they check cannot be checked any other way: a trend rule is only
 * wrong on a shape you did not happen to run, and the real suite takes minutes on hardware to
 * produce one shape. Here a leak, a plateau and a flat line are three lines of arithmetic each.
 */
class TrendsTest {
    private fun run(values: List<Double>): List<Sample> =
        values.mapIndexed { index, value ->
            Sample(
                cycle = index + 1,
                elapsedSeconds = index.toDouble(),
                rssBytes = (value * 1_048_576).toLong(),
                cpuNanos = 0,
                threads = 10,
                openFds = 20,
                liveClients = 0,
                orphanedGlobals = 0,
                nativeHeapBytes = 0,
            )
        }

    private fun rss(samples: List<Sample>) =
        Trends.assertNoSustainedGrowth(samples, "rss", " MB", 0.10, 8.0, false) {
            it.rssBytes / 1_048_576.0
        }

    @Test
    fun `a flat run passes`() {
        val result = rss(run(List(60) { 100.0 }))
        assertTrue(result.detail, result.passed)
        assertEquals(0.0, result.midToEnd, 0.001)
    }

    @Test
    fun `a steadily climbing run fails`() {
        val result = rss(run(List(60) { 100.0 + it * 5 }))
        assertFalse(result.detail, result.passed)
        assertTrue(result.detail.contains("still climbing"))
    }

    /**
     * The case the mid-third comparison exists for.
     *
     * A one-time ramp early on, then flat. Against the *first* third this reads as large growth
     * and fails; against the middle third it reads as what it is.
     */
    @Test
    fun `a one-time ramp that plateaus passes`() {
        val values = List(20) { 100.0 } + List(10) { 100.0 + it * 20.0 } + List(30) { 300.0 }
        val result = rss(run(values))
        assertTrue(result.detail, result.passed)
        assertTrue("the report must still show the step from start", result.end > result.start)
    }

    /** Growth under the absolute floor is noise, whatever the ratio says. */
    @Test
    fun `a tiny absolute rise passes even though the ratio is large`() {
        val values = List(30) { 1.0 } + List(30) { 4.0 }
        val result = rss(run(values))
        assertTrue(result.detail, result.passed)
    }

    @Test
    fun `too few samples is reported rather than guessed at`() {
        val result = rss(run(List(4) { 100.0 }))
        assertTrue(result.passed)
        assertTrue(result.detail, result.detail.contains("too few samples"))
    }

    // ── assertAlwaysZero ─────────────────────────────────────────────────────

    @Test
    fun `a value that is zero throughout passes`() {
        val result =
            Trends.assertAlwaysZero(run(List(30) { 1.0 }), "orphaned globals") {
                it.orphanedGlobals.toDouble()
            }
        assertTrue(result.passed)
    }

    /**
     * The reason this check exists rather than a trend check: a leak present from cycle 0 is
     * perfectly flat, and a trend check would accept it forever.
     */
    @Test
    fun `a value already nonzero on the first cycle fails, where a trend check would not`() {
        val samples = run(List(30) { 1.0 }).map { it.copy(orphanedGlobals = 3) }
        val zero = Trends.assertAlwaysZero(samples, "orphaned globals") { it.orphanedGlobals.toDouble() }
        assertFalse(zero.passed)
        assertTrue(zero.detail, zero.detail.contains("cycle 1"))

        val trend =
            Trends.assertNoSustainedGrowth(samples, "orphaned globals", "", 0.10, 0.0, true) {
                it.orphanedGlobals.toDouble()
            }
        assertTrue("a flat nonzero line is exactly what a trend check accepts", trend.passed)
    }

    @Test
    fun `a value that leaves zero midway names the cycle it happened on`() {
        val samples =
            run(List(30) { 1.0 }).mapIndexed { index, sample ->
                if (index >= 20) sample.copy(orphanedGlobals = 1) else sample
            }
        val result = Trends.assertAlwaysZero(samples, "orphaned globals") { it.orphanedGlobals.toDouble() }
        assertFalse(result.passed)
        assertTrue(result.detail, result.detail.contains("cycle 21"))
    }

    // ── the standard set ─────────────────────────────────────────────────────

    /**
     * fds get the trend check, never an exact never-grows.
     *
     * Two bindings independently caught a one-cycle step that settled back down before the run
     * ended — native socket teardown landing mid-sample, not a leak. A never-grows rule fails that
     * run; the median-backed trend does not.
     */
    @Test
    fun `a single fd spike that settles back does not fail the run`() {
        val samples =
            run(List(60) { 100.0 }).mapIndexed { index, sample ->
                sample.copy(openFds = if (index == 42) 60 else 20)
            }
        val fds = Trends.standardResourceMetrics(samples, false).first { it.name == "open fds" }
        assertTrue(fds.detail, fds.passed)
    }

    @Test
    fun `live clients is only checked when the scenario returns to zero`() {
        val samples = run(List(30) { 1.0 }).map { it.copy(liveClients = 1) }
        assertTrue(Trends.standardResourceMetrics(samples, false).none { it.name == "live clients" })
        val checked = Trends.standardResourceMetrics(samples, true).first { it.name == "live clients" }
        assertFalse("a churn scenario holding a client open is a failure", checked.passed)
    }
}
