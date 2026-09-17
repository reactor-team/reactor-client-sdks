package inc.reactor.sdk.endurance;

/**
 * One reading of the process, taken at the end of a cycle.
 *
 * @param cycle which cycle this was taken after
 * @param elapsedSeconds how long the run had been going
 * @param rssBytes resident set size — what the OS says this process is actually using
 * @param cpuNanos cumulative process CPU time; a raw counter, only meaningful as a delta
 * @param threads live threads
 * @param openFds open file descriptors, or -1 where the platform does not report them
 * @param liveClients SDK clients created and not yet closed
 * @param orphanedArenas arenas deliberately leaked because a callback was still running
 * @param nativeCommittedBytes committed native memory from NMT, or -1 when it is not enabled
 */
record Sample(
        int cycle,
        double elapsedSeconds,
        long rssBytes,
        long cpuNanos,
        int threads,
        long openFds,
        int liveClients,
        int orphanedArenas,
        long nativeCommittedBytes) {}
