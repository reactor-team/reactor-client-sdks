package inc.reactor.sdk.android.endurance

/**
 * What one metric did over a run, and whether that is a problem.
 *
 * Carries the midpoint as well as the ends. [start] and [end] are third-summaries rather than
 * single samples, so `start → end` routinely shows growth on a perfectly healthy run: a buffer or
 * pool reaching its steady size is a one-time step early on. The verdict is computed from
 * `mid → end`, so showing that delta is what lets a reader conclude "flat from the midpoint on,
 * not a leak" from the table alone.
 *
 * @property name what was measured
 * @property unit what it is measured in
 * @property start summary over the first third, after warm-up
 * @property mid summary over the middle third
 * @property end summary over the last third
 * @property growthRatio how much it grew from mid to end, as a fraction
 * @property passed whether this metric is within its limit
 * @property detail one line explaining the verdict
 */
public data class MetricResult(
    val name: String,
    val unit: String,
    val start: Double,
    val mid: Double,
    val end: Double,
    val growthRatio: Double,
    val passed: Boolean,
    val detail: String,
) {
    /** The change from the middle third to the last, which is what the verdict is based on. */
    public val midToEnd: Double get() = end - mid
}
