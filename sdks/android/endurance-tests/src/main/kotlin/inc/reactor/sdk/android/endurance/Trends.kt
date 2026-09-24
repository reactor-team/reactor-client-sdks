package inc.reactor.sdk.android.endurance

/**
 * Reading a run's shape, and deciding whether it is a leak.
 *
 * Ported from the Python suite rather than redesigned, including the reasoning that is not
 * obvious from the code.
 */
public object Trends {
    /** How much of a run to drop before measuring, so one-time costs settle. */
    private const val WARMUP_FRACTION = 0.2

    /**
     * Fails when a value is **still climbing in the back half of the run**.
     *
     * Last third against the **middle** third, not against the first. A real CI run showed RSS
     * flat, then a one-time ramp to a new plateau roughly in the middle of the window, then flat
     * again — a native buffer growing once to its steady size, not an unbounded leak. Comparing
     * against the first third makes *where that ramp happened to land* the thing that decides pass
     * or fail, which is timing rather than signal: the same total jump read as a comfortable pass
     * in one run and a razor-thin fail in another. Last-versus-middle asks the direct question —
     * did it keep growing after that — so a step that has already plateaued by the back third
     * reads as flat.
     *
     * The accepted cost: a real leak still in its early, slowly accelerating phase near the end of
     * a short run can read as flat. A longer run is the answer to that, not a different
     * comparison.
     *
     * @param useMedian take medians rather than means, for a metric where one sample can be an
     *   outlier — thread and descriptor counts step by whole units
     */
    public fun assertNoSustainedGrowth(
        samples: List<Sample>,
        name: String,
        unit: String,
        maxGrowthRatio: Double,
        minAbsoluteDelta: Double,
        useMedian: Boolean,
        value: (Sample) -> Double,
    ): MetricResult {
        val values = afterWarmup(samples, value)
        if (values.size < 6) {
            return MetricResult(
                name,
                unit,
                0.0,
                0.0,
                0.0,
                0.0,
                true,
                "too few samples to read a trend (${values.size})",
            )
        }
        val third = values.size / 3
        val start = middle(values.subList(0, third), useMedian)
        val mid = middle(values.subList(third, third * 2), useMedian)
        val end = middle(values.subList(third * 2, values.size), useMedian)

        val delta = end - mid
        val ratio =
            when {
                mid != 0.0 -> delta / mid
                delta > 0 -> Double.POSITIVE_INFINITY
                else -> 0.0
            }
        val grew = delta > minAbsoluteDelta && ratio > maxGrowthRatio

        val detail =
            if (grew) {
                "still climbing: ${fmt(delta, signed = true)}$unit from the middle third " +
                    "(${fmt(ratio * 100, signed = true)}%), over the ${fmt(maxGrowthRatio * 100)}% allowed"
            } else {
                "flat from the midpoint on: ${fmt(delta, signed = true)}$unit " +
                    "(${fmt(ratio * 100, signed = true)}%)"
            }
        return MetricResult(name, unit, start, mid, end, ratio, !grew, detail)
    }

    /**
     * Fails when a value is ever anything but zero.
     *
     * For anything that should start at zero and stay there. A trend check compares against the
     * baseline it first sees, so a value already leaking on cycle 0 becomes the accepted normal and
     * the check passes forever — which is exactly what orphaned JNI globals would do.
     */
    public fun assertAlwaysZero(
        samples: List<Sample>,
        name: String,
        value: (Sample) -> Double,
    ): MetricResult {
        var worst = 0.0
        var firstAt = -1
        for (sample in samples) {
            val reading = value(sample)
            if (reading != 0.0 && firstAt < 0) firstAt = sample.cycle
            worst = maxOf(worst, reading)
        }
        val passed = worst == 0.0
        return MetricResult(
            name,
            "count",
            0.0,
            0.0,
            worst,
            0.0,
            passed,
            if (passed) {
                "zero throughout, as it must be"
            } else {
                "reached ${worst.toLong()}, first at cycle $firstAt — this must never leave zero"
            },
        )
    }

    /**
     * The checks every scenario runs, so a new one is a loop body and two calls.
     *
     * `openFds` gets the trend check with medians, never an exact never-grows. That used to be a
     * per-scenario opt-in on the theory that some teardown was provably synchronous with its own
     * cycle boundary — and two bindings independently caught a one-cycle step that settled back
     * down before the run ended, which is native socket teardown landing mid-sample rather than a
     * leak. One rule, no exceptions.
     *
     * @param liveClientsBaselineZero whether this scenario ends every cycle with no clients alive
     */
    public fun standardResourceMetrics(
        samples: List<Sample>,
        liveClientsBaselineZero: Boolean,
    ): List<MetricResult> {
        val results = mutableListOf<MetricResult>()
        results +=
            assertNoSustainedGrowth(samples, "rss", " MB", 0.10, 8.0, false) {
                it.rssBytes / 1_048_576.0
            }
        results +=
            assertNoSustainedGrowth(samples, "threads", "", 0.10, 2.0, true) {
                it.threads.toDouble()
            }
        results +=
            assertNoSustainedGrowth(samples, "open fds", "", 0.10, 4.0, true) {
                it.openFds.toDouble()
            }
        results += assertNoSustainedGrowth(samples, "cpu per cycle", " ms", 0.50, 5.0, true, ::cpuPerCycleMillis)
        // The native heap on its own. On Android, RSS moves with ART's managed heap underneath it,
        // so a native leak can hide inside ordinary GC behaviour; this is the number that does not
        // have that problem. ART has no NMT, and this is the equivalent question the platform can
        // actually answer.
        results +=
            assertNoSustainedGrowth(samples, "native heap", " MB", 0.10, 8.0, false) {
                it.nativeHeapBytes / 1_048_576.0
            }
        // Never anything but zero: a JNI global the FFI could still be reaching through is a
        // permanent leak by design, and one on the very first cycle would otherwise become the
        // accepted baseline.
        results += assertAlwaysZero(samples, "orphaned globals") { it.orphanedGlobals.toDouble() }
        if (liveClientsBaselineZero) {
            results += assertAlwaysZero(samples, "live clients") { it.liveClients.toDouble() }
        }
        return results
    }

    private fun cpuPerCycleMillis(sample: Sample): Double =
        // A delta would need the previous sample; the scenario records cpuNanos cumulatively and
        // this divides by the cycle, which is the same question asked of one reading. A raw
        // cumulative counter grows by construction and proves nothing.
        if (sample.cycle == 0) 0.0 else sample.cpuNanos / 1_000_000.0 / sample.cycle

    private fun afterWarmup(
        samples: List<Sample>,
        value: (Sample) -> Double,
    ): List<Double> = samples.drop((samples.size * WARMUP_FRACTION).toInt()).map(value)

    private fun middle(
        values: List<Double>,
        useMedian: Boolean,
    ): Double =
        when {
            values.isEmpty() -> 0.0
            !useMedian -> values.average()
            else -> values.sorted()[values.size / 2]
        }

    /**
     * One decimal place, without java.util.Formatter's locale.
     *
     * A comma decimal separator is correct in a French locale and wrong in a Markdown table a
     * reader is comparing numbers in — and wrong again in the JSON the same values are written to.
     */
    internal fun fmt(
        value: Double,
        signed: Boolean = false,
    ): String {
        if (value.isNaN()) return "NaN"
        if (value.isInfinite()) return if (value > 0) "∞" else "-∞"
        val rounded = Math.round(value * 10) / 10.0
        val sign = if (signed && rounded >= 0) "+" else ""
        val whole = kotlin.math.abs(rounded).toLong()
        val tenths = Math.round((kotlin.math.abs(rounded) - whole) * 10)
        val negative = if (rounded < 0) "-" else ""
        return "$sign$negative$whole.$tenths"
    }
}
