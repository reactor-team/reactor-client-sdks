package inc.reactor.sdk.endurance;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Reading a run's shape, and deciding whether it is a leak.
 *
 * <p>Ported from the Python suite rather than redesigned, including the reasoning that is not
 * obvious from the code.
 */
final class Trends {

    /** How much of a run to drop before measuring, so one-time costs settle. */
    private static final double WARMUP_FRACTION = 0.2;

    private Trends() {}

    /**
     * Fails when a value is <b>still climbing in the back half of the run</b>.
     *
     * <p>Last third against the <b>middle</b> third, not against the first. A real CI run showed
     * RSS flat, then a one-time ramp to a new plateau roughly in the middle of the window, then
     * flat again — a native buffer growing once to its steady size, not an unbounded leak.
     * Comparing against the first third makes <i>where that ramp happened to land</i> the thing
     * that decides pass or fail, which is timing rather than signal: the same total jump read as a
     * comfortable pass in one run and a razor-thin fail in another. Last-versus-middle asks the
     * direct question — did it keep growing after that — so a step that has already plateaued by
     * the back third reads as flat.
     *
     * <p>The accepted cost: a real leak still in its early, slowly accelerating phase near the end
     * of a short run can read as flat. A longer run is the answer to that, not a different
     * comparison.
     *
     * @param samples the run
     * @param name what is being measured
     * @param unit what it is measured in
     * @param value reads the metric out of a sample
     * @param maxGrowthRatio how much growth from mid to end is tolerated
     * @param minAbsoluteDelta growth below this is noise whatever the ratio says
     * @param useMedian take medians rather than means, for a metric where one sample can be an
     *     outlier — thread and descriptor counts step by whole units
     * @return the verdict
     */
    static MetricResult assertNoSustainedGrowth(
            List<Sample> samples,
            String name,
            String unit,
            ToDoubleFunction<Sample> value,
            double maxGrowthRatio,
            double minAbsoluteDelta,
            boolean useMedian) {

        List<Double> values = afterWarmup(samples, value);
        if (values.size() < 6) {
            return new MetricResult(
                    name, unit, 0, 0, 0, 0, true, "too few samples to read a trend (" + values.size() + ")");
        }
        int third = values.size() / 3;
        double start = middle(values.subList(0, third), useMedian);
        double mid = middle(values.subList(third, third * 2), useMedian);
        double end = middle(values.subList(third * 2, values.size()), useMedian);

        double delta = end - mid;
        double ratio = mid == 0 ? (delta > 0 ? Double.POSITIVE_INFINITY : 0) : delta / mid;
        boolean grew = delta > minAbsoluteDelta && ratio > maxGrowthRatio;

        String detail = grew
                ? "still climbing: %+.1f%s from the middle third (%+.1f%%), over the %.1f%% allowed"
                        .formatted(delta, unit, ratio * 100, maxGrowthRatio * 100)
                : "flat from the midpoint on: %+.1f%s (%+.1f%%)".formatted(delta, unit, ratio * 100);
        return new MetricResult(name, unit, start, mid, end, ratio, !grew, detail);
    }

    /**
     * Fails when a value is ever anything but zero.
     *
     * <p>For anything that should start at zero and stay there. A trend check compares against the
     * baseline it first sees, so a value already leaking on cycle 0 becomes the accepted normal and
     * the check passes forever — which is exactly what orphaned callbacks would do.
     *
     * @param samples the run
     * @param name what is being measured
     * @param value reads the metric out of a sample
     * @return the verdict
     */
    static MetricResult assertAlwaysZero(List<Sample> samples, String name, ToDoubleFunction<Sample> value) {
        double worst = 0;
        int firstAt = -1;
        for (Sample sample : samples) {
            double reading = value.applyAsDouble(sample);
            if (reading != 0 && firstAt < 0) {
                firstAt = sample.cycle();
            }
            worst = Math.max(worst, reading);
        }
        boolean passed = worst == 0;
        return new MetricResult(
                name,
                "count",
                0,
                0,
                worst,
                0,
                passed,
                passed
                        ? "zero throughout, as it must be"
                        : "reached %.0f, first at cycle %d — this must never leave zero".formatted(worst, firstAt));
    }

    /**
     * The checks every scenario runs, so a new one is a loop body and two calls.
     *
     * <p>{@code openFds} gets the trend check with medians, never an exact never-grows. That used
     * to be a per-scenario opt-in on the theory that some teardown was provably synchronous with
     * its own cycle boundary — and two bindings independently caught a one-cycle step that settled
     * back down before the run ended, which is native socket teardown landing mid-sample rather
     * than a leak. One rule, no exceptions.
     *
     * @param samples the run
     * @param liveClientsBaselineZero whether this scenario ends every cycle with no clients alive
     * @return every metric's verdict
     */
    static List<MetricResult> standardResourceMetrics(List<Sample> samples, boolean liveClientsBaselineZero) {
        List<MetricResult> results = new ArrayList<>();
        results.add(assertNoSustainedGrowth(
                samples, "rss", " MB", sample -> sample.rssBytes() / 1_048_576.0, 0.10, 8, false));
        results.add(assertNoSustainedGrowth(samples, "threads", "", sample -> sample.threads(), 0.10, 2, true));
        results.add(assertNoSustainedGrowth(samples, "open fds", "", sample -> sample.openFds(), 0.10, 4, true));
        results.add(assertNoSustainedGrowth(samples, "cpu per cycle", " ms", Trends::cpuPerCycleMillis, 0.50, 5, true));
        // Never anything but zero: a callback the FFI could still be running is a permanent leak by
        // design, and one on the very first cycle would otherwise become the accepted baseline.
        results.add(assertAlwaysZero(samples, "orphaned arenas", sample -> sample.orphanedArenas()));
        if (liveClientsBaselineZero) {
            results.add(assertAlwaysZero(samples, "live clients", sample -> sample.liveClients()));
        }
        if (samples.stream().anyMatch(sample -> sample.nativeCommittedBytes() > 0)) {
            // Only when NMT is on. RSS alone is noisy on a JVM because the GC heap moves under it;
            // this is the number that says whether *native* memory is growing.
            results.add(assertNoSustainedGrowth(
                    samples,
                    "native committed",
                    " MB",
                    sample -> sample.nativeCommittedBytes() / 1_048_576.0,
                    0.10,
                    8,
                    false));
        }
        return results;
    }

    private static double cpuPerCycleMillis(Sample sample) {
        // A delta would need the previous sample; the scenario records cpuNanos cumulatively and
        // this divides by the cycle, which is the same question asked of one reading. A raw
        // cumulative counter grows by construction and proves nothing.
        return sample.cycle() == 0 ? 0 : sample.cpuNanos() / 1_000_000.0 / sample.cycle();
    }

    private static List<Double> afterWarmup(List<Sample> samples, ToDoubleFunction<Sample> value) {
        int skip = (int) (samples.size() * WARMUP_FRACTION);
        List<Double> values = new ArrayList<>();
        for (int index = skip; index < samples.size(); index++) {
            values.add(value.applyAsDouble(samples.get(index)));
        }
        return values;
    }

    private static double middle(List<Double> values, boolean useMedian) {
        if (values.isEmpty()) {
            return 0;
        }
        if (!useMedian) {
            return values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        return sorted.get(sorted.size() / 2);
    }
}
