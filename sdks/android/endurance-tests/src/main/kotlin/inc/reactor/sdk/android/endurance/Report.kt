package inc.reactor.sdk.android.endurance

import java.io.File

/**
 * One result, three renderings, written whichever way the run ends.
 *
 * A report that only appears on success is useless for the run you actually need to debug. All
 * three files are written first, and only then does a failing metric raise.
 *
 * Deliberately imports nothing from the SDK. Its own tests need no live service, no device and no
 * native library, which is what keeps them able to check the formatting of a report about a suite
 * they never run — and on Android that matters more than elsewhere, because everything in
 * `androidTest` needs hardware to execute at all.
 */
public object Report {
    /**
     * Writes the three files and fails the run if any metric did.
     *
     * @param directory where to write. On a device this is app-private storage the harness then
     *   has pulled off; in a unit test it is a temporary directory.
     * @param scenario the slug, e.g. `publish-churn`
     * @param description one hand-written line saying what this scenario's loop actually does
     */
    public fun finishAndCheck(
        directory: File,
        scenario: String,
        description: String,
        sdkVersion: String,
        commit: String,
        durationSeconds: Double,
        samples: List<Sample>,
        metrics: List<MetricResult>,
    ) {
        val markdown = markdown(scenario, description, sdkVersion, commit, durationSeconds, samples, metrics)
        directory.mkdirs()
        File(directory, "$scenario-report.md").writeText(markdown)
        File(directory, "$scenario-report.json")
            .writeText(json(scenario, description, sdkVersion, commit, durationSeconds, samples, metrics))
        File(directory, "$scenario-report.txt").writeText(text(scenario, metrics))

        println(markdown)

        val failed = metrics.filterNot { it.passed }
        if (failed.isNotEmpty()) {
            throw AssertionError(
                failed.joinToString(
                    separator = "\n  ",
                    prefix = "$scenario failed:\n  ",
                ) { "${it.name}: ${it.detail}" },
            )
        }
    }

    internal fun markdown(
        scenario: String,
        description: String,
        sdkVersion: String,
        commit: String,
        durationSeconds: Double,
        samples: List<Sample>,
        metrics: List<MetricResult>,
    ): String {
        val passed = metrics.all { it.passed }
        val out = StringBuilder()
        out
            .append("## Endurance · ")
            .append(scenario)
            .append(if (passed) " · PASS" else " · FAIL")
            .append("\n\n")
        // The slug alone does not say "no frames, no commands", and that distinction is the whole
        // point once there is more than one scenario.
        out.append(description).append("\n\n")
        out.append("| | |\n| --- | --- |\n")
        out.append("| SDK version | `").append(sdkVersion).append("` |\n")
        out.append("| Commit | `").append(commit).append("` |\n")
        out.append("| Duration | ").append(Trends.fmt(durationSeconds)).append(" s |\n")
        out.append("| Cycles | ").append(samples.lastOrNull()?.cycle ?: 0).append(" |\n\n")

        out.append("### Results\n\n")
        out.append("| Metric | start | mid | end | mid → end | Verdict |\n")
        out.append("| --- | ---: | ---: | ---: | ---: | --- |\n")
        for (metric in metrics) {
            out.append("| ").append(metric.name)
            out.append(" | ").append(Trends.fmt(metric.start))
            out.append(" | ").append(Trends.fmt(metric.mid))
            out.append(" | ").append(Trends.fmt(metric.end))
            // The delta the verdict is actually based on, in its own column: start -> end reads as
            // growth on a healthy run, because a one-time step early on moves end relative to
            // start without ever being a leak.
            out.append(" | ").append(Trends.fmt(metric.midToEnd, signed = true))
            out.append(" | ").append(if (metric.passed) "pass" else "**FAIL**")
            out.append(" — ").append(metric.detail).append(" |\n")
        }

        out.append("\n### Timeline\n\n")
        out.append("| at | cycle | RSS MB | native MB | threads | fds | live | orphaned |\n")
        out.append("| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n")
        for (sample in checkpoints(samples)) {
            out.append("| ").append(Trends.fmt(sample.elapsedSeconds)).append("s")
            out.append(" | ").append(sample.cycle)
            out.append(" | ").append(Trends.fmt(sample.rssBytes / 1_048_576.0))
            out.append(" | ").append(Trends.fmt(sample.nativeHeapBytes / 1_048_576.0))
            out.append(" | ").append(sample.threads)
            out.append(" | ").append(sample.openFds)
            out.append(" | ").append(sample.liveClients)
            out.append(" | ").append(sample.orphanedGlobals).append(" |\n")
        }
        out
            .append("\nFlat, a one-time ramp, or still climbing — the shape is what a reader needs,")
            .append(" and two numbers cannot show it.\n")
        return out.toString()
    }

    /** A handful of evenly spaced readings, which is what shows the shape. */
    private fun checkpoints(samples: List<Sample>): List<Sample> {
        if (samples.size <= 8) return samples
        return (0 until 8).map { samples[it * (samples.size - 1) / 7] }
    }

    internal fun json(
        scenario: String,
        description: String,
        sdkVersion: String,
        commit: String,
        durationSeconds: Double,
        samples: List<Sample>,
        metrics: List<MetricResult>,
    ): String {
        val out = StringBuilder("{\n")
        out.append("  \"sdk\": \"android\",\n")
        out.append("  \"test_name\": \"").append(scenario).append("\",\n")
        out.append("  \"description\": \"").append(escape(description)).append("\",\n")
        out.append("  \"sdk_version\": \"").append(sdkVersion).append("\",\n")
        out.append("  \"commit\": \"").append(commit).append("\",\n")
        out.append("  \"duration_seconds\": ").append(durationSeconds).append(",\n")
        out.append("  \"passed\": ").append(metrics.all { it.passed }).append(",\n")
        out.append("  \"metrics\": [\n")
        metrics.forEachIndexed { index, metric ->
            out.append("    {\"name\": \"").append(metric.name).append("\"")
            out.append(", \"start\": ").append(metric.start)
            out.append(", \"mid\": ").append(metric.mid)
            out.append(", \"end\": ").append(metric.end)
            out.append(", \"mid_to_end\": ").append(metric.midToEnd)
            out.append(", \"passed\": ").append(metric.passed)
            out.append(", \"detail\": \"").append(escape(metric.detail)).append("\"}")
            out.append(if (index == metrics.size - 1) "\n" else ",\n")
        }
        out.append("  ],\n  \"samples\": ").append(samples.size).append("\n}\n")
        return out.toString()
    }

    internal fun text(
        scenario: String,
        metrics: List<MetricResult>,
    ): String {
        val out = StringBuilder("endurance $scenario\n")
        for (metric in metrics) {
            out
                .append(if (metric.passed) "  ok   " else "  FAIL ")
                .append(metric.name)
                .append(": ")
                .append(metric.detail)
                .append('\n')
        }
        return out.toString()
    }

    private fun escape(text: String): String = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
}
