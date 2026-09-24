package inc.reactor.sdk.android.endurance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What a reader gets, days later, from a Job Summary.
 *
 * The report is the whole output of this suite — a run nobody can reproduce on demand, read by
 * someone who was not watching it. These check the things that make it readable standalone, and
 * the one behaviour that matters more than any of them: that it is written even when the run
 * failed.
 */
class ReportTest {
    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private fun samples(count: Int) =
        (1..count).map {
            Sample(
                cycle = it,
                elapsedSeconds = it * 2.0,
                rssBytes = 100L * 1_048_576,
                cpuNanos = it * 1_000_000L,
                threads = 12,
                openFds = 30,
                liveClients = 0,
                orphanedGlobals = 0,
                nativeHeapBytes = 40L * 1_048_576,
            )
        }

    private fun metric(passed: Boolean) =
        MetricResult("rss", " MB", 100.0, 101.0, 102.0, 0.01, passed, if (passed) "flat" else "still climbing")

    @Test
    fun `all three renderings are written on a pass`() {
        Report.finishAndCheck(
            folder.root,
            "publish-churn",
            "publish then unpublish",
            "1.0.0",
            "abc123",
            300.0,
            samples(30),
            listOf(metric(true)),
        )
        for (extension in listOf("md", "json", "txt")) {
            assertTrue("$extension missing", File(folder.root, "publish-churn-report.$extension").isFile)
        }
    }

    /**
     * The one that matters. A report that only appears on success is useless for the run you
     * actually need to debug.
     */
    @Test
    fun `all three renderings are written before a failing metric raises`() {
        assertThrows(AssertionError::class.java) {
            Report.finishAndCheck(
                folder.root,
                "publish-churn",
                "publish then unpublish",
                "1.0.0",
                "abc123",
                300.0,
                samples(30),
                listOf(metric(false)),
            )
        }
        for (extension in listOf("md", "json", "txt")) {
            assertTrue("$extension missing", File(folder.root, "publish-churn-report.$extension").isFile)
        }
        assertTrue(File(folder.root, "publish-churn-report.md").readText().contains("**FAIL**"))
    }

    @Test
    fun `the failure names every metric that failed, not just the first`() {
        val raised =
            assertThrows(AssertionError::class.java) {
                Report.finishAndCheck(
                    folder.root,
                    "session-churn",
                    "everything at once",
                    "1.0.0",
                    "abc123",
                    300.0,
                    samples(30),
                    listOf(metric(false), MetricResult("threads", "", 1.0, 2.0, 9.0, 3.5, false, "climbing")),
                )
            }
        assertTrue(raised.message!!.contains("rss"))
        assertTrue(raised.message!!.contains("threads"))
    }

    /** The things a reader needs when they have only this file and no memory of the run. */
    @Test
    fun `the markdown carries the description, the version above the commit, and a timeline`() {
        val markdown =
            Report.markdown(
                "pause-resume-churn",
                "pause then resume a recvonly track, nothing else in the loop.",
                "1.2.3",
                "deadbeef",
                300.0,
                samples(40),
                listOf(metric(true)),
            )
        assertTrue(markdown.contains("pause then resume a recvonly track"))
        assertTrue(markdown.contains("`1.2.3`"))
        assertTrue(markdown.contains("`deadbeef`"))
        assertTrue(markdown.indexOf("1.2.3") < markdown.indexOf("deadbeef"))
        assertTrue(markdown.contains("### Timeline"))
        // The column the verdict is actually computed from.
        assertTrue(markdown.contains("mid → end"))
    }

    /** Evenly spaced, and always including the last reading — the end is the point. */
    @Test
    fun `the timeline is a handful of checkpoints spanning the whole run`() {
        val markdown =
            Report.markdown("x", "d", "1.0.0", "sha", 300.0, samples(100), listOf(metric(true)))
        val timeline = markdown.substringAfter("### Timeline")
        val rows = timeline.lines().count { it.startsWith("| ") && !it.startsWith("| at") && !it.startsWith("| --") }
        assertEquals(8, rows)
        assertTrue("the last cycle must be in the timeline", timeline.contains("| 100 |"))
    }

    @Test
    fun `a short run shows every reading rather than padding`() {
        val markdown = Report.markdown("x", "d", "1.0.0", "sha", 10.0, samples(3), listOf(metric(true)))
        val timeline = markdown.substringAfter("### Timeline")
        val rows = timeline.lines().count { it.startsWith("| ") && !it.startsWith("| at") && !it.startsWith("| --") }
        assertEquals(3, rows)
    }

    @Test
    fun `the json escapes a description that would otherwise break it`() {
        val json =
            Report.json(
                "x",
                """a "quoted" line
                and a newline""",
                "1.0.0",
                "sha",
                1.0,
                samples(1),
                listOf(metric(true)),
            )
        assertTrue(json.contains("""\"quoted\""""))
        // One line per field: a raw newline inside a JSON string is a parse error.
        assertTrue(json.lines().none { it.trim().startsWith("and a newline") })
    }

    @Test
    fun `the text rendering marks the failures`() {
        val text = Report.text("x", listOf(metric(true), metric(false)))
        assertTrue(text.contains("  ok   rss"))
        assertTrue(text.contains("  FAIL rss"))
    }

    /**
     * Numbers are formatted without a locale.
     *
     * A comma decimal separator is correct in a French locale, wrong in a table a reader is
     * comparing numbers in, and a parse error in the JSON beside it.
     */
    @Test
    fun `numbers use a dot wherever the machine is`() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.FRANCE)
            val markdown = Report.markdown("x", "d", "1.0.0", "sha", 300.0, samples(10), listOf(metric(true)))
            assertTrue(markdown.contains("100.0"))
            assertTrue("a comma separator would have got in here", !markdown.contains("100,0"))
        } finally {
            java.util.Locale.setDefault(original)
        }
    }
}
