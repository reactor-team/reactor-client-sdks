package inc.reactor.sdk.android.endurance

/**
 * One reading of the process, taken at the end of a cycle.
 *
 * @property cycle which cycle this was taken after
 * @property elapsedSeconds how long the run had been going
 * @property rssBytes resident set size — what the kernel says this process is actually using
 * @property cpuNanos cumulative process CPU time; a raw counter, only meaningful as a delta
 * @property threads live threads
 * @property openFds open file descriptors, or -1 where they cannot be counted
 * @property liveClients SDK clients created and not yet closed
 * @property orphanedGlobals JNI globals deliberately leaked because a callback was still running
 * @property nativeHeapBytes the native heap specifically, which on Android is the number that is
 *   not confounded by the managed heap moving underneath it. ART has no NMT; this is the
 *   equivalent question asked the way the platform can answer it.
 */
public data class Sample(
    val cycle: Int,
    val elapsedSeconds: Double,
    val rssBytes: Long,
    val cpuNanos: Long,
    val threads: Int,
    val openFds: Long,
    val liveClients: Int,
    val orphanedGlobals: Int,
    val nativeHeapBytes: Long,
)
