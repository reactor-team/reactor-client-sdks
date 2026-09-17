package inc.reactor.sdk.endurance;

/**
 * What one metric did over a run, and whether that is a problem.
 *
 * <p>Carries the midpoint as well as the ends. The start and end are third-means rather than single
 * samples, so {@code start -> end} routinely shows growth on a perfectly healthy run: a buffer or
 * pool reaching its steady size is a one-time step early on. The verdict is computed from
 * {@code mid -> end}, so showing that delta is what lets a reader conclude "flat from the midpoint
 * on, not a leak" from the table alone.
 *
 * @param name what was measured
 * @param unit what it is measured in
 * @param start mean over the first third, after warm-up
 * @param mid mean over the middle third
 * @param end mean over the last third
 * @param growthRatio how much it grew from mid to end, as a fraction
 * @param passed whether this metric is within its limit
 * @param detail one line explaining the verdict
 */
record MetricResult(
        String name,
        String unit,
        double start,
        double mid,
        double end,
        double growthRatio,
        boolean passed,
        String detail) {

    /** @return the change from the middle third to the last, which is what the verdict is based on */
    double midToEnd() {
        return end - mid;
    }
}
