package inc.reactor.sdk.android

/**
 * A recording the platform is assembling.
 *
 * [predictedReadyAtMs] is the runtime's own guess in Unix milliseconds, and it is only right for
 * a model generating at real time: clip readiness is in **media** time, so a model running at a
 * tenth of real time reaches the same boundary ten times later. Treat it as a hint to start the
 * grace period from, never as a deadline.
 */
public data class Clip(
    public val playlistUrl: String,
    public val sessionId: String? = null,
    public val kind: String? = null,
    /** Unix milliseconds, or null when the runtime offered no prediction. */
    public val predictedReadyAtMs: Double? = null,
    /** Everything the platform sent, including fields this SDK does not name. */
    public val raw: Map<String, Any?> = emptyMap(),
)

/** A clip written to disk. */
public data class DownloadedClip(
    public val path: String,
    public val bytes: Long,
    public val segments: Long,
)

/** Segments written so far, out of how many the clip has. */
public data class ClipProgress(
    public val done: Int,
    public val total: Int,
) {
    /** 0.0 to 1.0, or null while the total is still unknown. */
    public val fraction: Double?
        get() = if (total > 0) done.toDouble() / total else null
}
