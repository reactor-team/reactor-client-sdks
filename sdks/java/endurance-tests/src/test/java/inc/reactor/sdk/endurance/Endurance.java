package inc.reactor.sdk.endurance;

import com.sun.management.OperatingSystemMXBean;
import com.sun.management.UnixOperatingSystemMXBean;
import inc.reactor.sdk.ReactorOptions;
import inc.reactor.sdk.ReactorSdk;
import inc.reactor.sdk.internal.ClientPeer;
import inc.reactor.sdk.internal.OrphanedArenas;
import java.io.BufferedReader;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;

/**
 * The run: how long it goes on for, what is measured, and how often.
 *
 * <p>Unit tests and the live suite are both short-lived. Neither can see a handle, a callback or a
 * descriptor that leaks a little on every cycle — this runs for minutes to hours instead and
 * watches whether the numbers keep climbing.
 */
final class Endurance {

    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final OperatingSystemMXBean OS =
            (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    private final String scenario;
    private final String description;
    private final long deadlineNanos;
    private final List<Sample> samples = new ArrayList<>();
    private final long startedAt = System.nanoTime();
    private long lastPrintedAt;
    private int cycle;

    Endurance(String scenario, String description) {
        this.scenario = scenario;
        this.description = description;
        this.deadlineNanos = startedAt + durationSeconds() * 1_000_000_000L;
    }

    /** One scenario's loop, given the run it reports into. */
    @FunctionalInterface
    interface Scenario {
        void run(Endurance run) throws Exception;
    }

    /**
     * Runs one scenario and writes its report, whichever way it ends.
     *
     * <p>Every scenario goes through here rather than calling {@link #finish} itself. A scenario
     * that finished with its own call put report generation on the success path: a run that threw
     * on cycle 4,000 — which is the run somebody actually needs to read — cleaned up, propagated,
     * and left nothing behind, while the workflow dutifully uploaded an empty directory. The shape
     * is the fix: a new scenario cannot forget what it never had to remember.
     *
     * <p>The original failure wins. A metric that also fails is attached to it as suppressed rather
     * than replacing it, because "still climbing" is a worse answer than the exception that stopped
     * the run.
     *
     * @param scenario the slug
     * @param description one line saying what this loop does
     * @param liveClientsBaselineZero whether this scenario ends every cycle with no clients alive
     * @param body the loop
     * @throws Exception whatever the loop threw
     */
    static void run(String scenario, String description, boolean liveClientsBaselineZero, Scenario body)
            throws Exception {
        Endurance run = new Endurance(scenario, description);
        Throwable failure = null;
        try {
            body.run(run);
        } catch (Throwable thrown) {
            failure = thrown;
        }
        // Nothing to report from a run that never took a reading: a scenario aborted for want of a
        // key, or one that failed before its first cycle, would otherwise leave three files saying
        // nothing and bury the run that has something to say.
        if (!run.samples.isEmpty()) {
            try {
                run.finish(liveClientsBaselineZero);
            } catch (Throwable verdict) {
                if (failure == null) {
                    throw verdict;
                }
                failure.addSuppressed(verdict);
            }
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof Exception exception) {
            throw exception;
        }
    }

    /** @return how long a run lasts, from {@code ENDURANCE_DURATION_MINUTES} */
    static long durationSeconds() {
        String minutes = System.getenv("ENDURANCE_DURATION_MINUTES");
        return minutes == null || minutes.isBlank() ? 300 : (long) (Double.parseDouble(minutes) * 60);
    }

    /** @return the key, or an aborted test — this suite cannot run without reaching the platform */
    static String apiKey() {
        String key = System.getenv("ENDURANCE_TESTS_REACTOR_API_KEY");
        if (key == null || key.isBlank()) {
            key = System.getenv("INTEGRATION_TESTS_REACTOR_API_KEY");
        }
        if (key == null || key.isBlank()) {
            Assumptions.abort("no API key set — skipping the endurance suite");
        }
        return key;
    }

    static String apiUrl() {
        return System.getenv().getOrDefault("REACTOR_API_URL", "https://api.reactor.inc");
    }

    static String model() {
        return System.getenv().getOrDefault("ENDURANCE_TESTS_REACTOR_MODEL", "reactor/echo");
    }

    /**
     * Options for a client this suite creates.
     *
     * <p>The key rather than a token minted once for the whole run, which is what this used to
     * pass and what made lifecycle churn the only scenario that could not finish. A token carries a
     * session grant with a capacity the server sets, and a session spends its slot for the life of
     * the grant — disconnecting does not give it back. Every scenario but one holds a single
     * session for the whole run and never notices; the one that opens a client per cycle exhausted
     * the grant and was refused with "Session limit reached for this token". Handing the client the
     * key instead mints per connect, which is what the Python, C++ and Swift suites have always
     * done.
     *
     * @param apiKey the key to exchange
     * @return the options
     */
    static ReactorOptions options(String apiKey) {
        return ReactorOptions.builder(apiUrl(), model()).apiKey(apiKey).build();
    }

    /** @return whether the run still has time left */
    boolean keepGoing() {
        return System.nanoTime() < deadlineNanos;
    }

    /** Takes a reading, and reprints the status block if it is time. */
    void endOfCycle() {
        cycle++;
        Sample sample = new Sample(
                cycle,
                (System.nanoTime() - startedAt) / 1_000_000_000.0,
                residentBytes(),
                OS.getProcessCpuTime(),
                THREADS.getThreadCount(),
                openFileDescriptors(),
                ClientPeer.liveClients(),
                OrphanedArenas.count(),
                nativeCommittedBytes());
        samples.add(sample);
        report(sample);
    }

    /**
     * Writes the report and fails if anything is still climbing.
     *
     * @param liveClientsBaselineZero whether this scenario ends every cycle with no clients alive
     */
    void finish(boolean liveClientsBaselineZero) {
        Report.finishAndCheck(
                scenario,
                description,
                ReactorSdk.version(),
                System.getenv().getOrDefault("GITHUB_SHA", "(local)"),
                durationSeconds(),
                samples,
                Trends.standardResourceMetrics(samples, liveClientsBaselineZero));
    }

    /** @return every reading so far, for a scenario's own invariant */
    List<Sample> samples() {
        return List.copyOf(samples);
    }

    private void report(Sample sample) {
        boolean verbose = "1".equals(System.getenv("ENDURANCE_VERBOSE"));
        long now = System.nanoTime();
        if (!verbose && now - lastPrintedAt < TimeUnit.SECONDS.toNanos(30)) {
            return;
        }
        lastPrintedAt = now;
        // One block reprinted on an interval, rather than a row per cycle: a run of several hours
        // is otherwise a log nobody reads. ENDURANCE_VERBOSE=1 brings the per-cycle detail back.
        System.out.printf(
                "[%s] %5.0fs  cycle %-6d  rss %6.1f MB  threads %-4d fds %-5d live %-3d orphaned %d%n",
                scenario,
                sample.elapsedSeconds(),
                sample.cycle(),
                sample.rssBytes() / 1_048_576.0,
                sample.threads(),
                sample.openFds(),
                sample.liveClients(),
                sample.orphanedArenas());
    }

    private static long openFileDescriptors() {
        return OS instanceof UnixOperatingSystemMXBean unix ? unix.getOpenFileDescriptorCount() : -1;
    }

    private static long residentBytes() {
        // The JVM reports committed heap, not resident set — and RSS is the number that says what
        // the OS thinks this process is using, native allocations included.
        Path status = Path.of("/proc/self/status");
        if (Files.isReadable(status)) {
            try (BufferedReader reader = Files.newBufferedReader(status)) {
                return reader.lines()
                        .filter(line -> line.startsWith("VmRSS:"))
                        .mapToLong(line -> Long.parseLong(line.replaceAll("\\D+", "")) * 1024)
                        .findFirst()
                        .orElse(-1);
            } catch (IOException | RuntimeException unreadable) {
                return -1;
            }
        }
        return macOsResidentBytes();
    }

    private static long macOsResidentBytes() {
        try {
            Process ps = new ProcessBuilder(
                            "ps",
                            "-o",
                            "rss=",
                            "-p",
                            String.valueOf(ProcessHandle.current().pid()))
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = ps.inputReader()) {
                String line = reader.readLine();
                ps.waitFor(5, TimeUnit.SECONDS);
                return line == null ? -1 : Long.parseLong(line.trim()) * 1024;
            }
        } catch (IOException | InterruptedException | RuntimeException unavailable) {
            if (unavailable instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return -1;
        }
    }

    private static long nativeCommittedBytes() {
        // Needs -XX:NativeMemoryTracking=summary, which the test task passes. Absent otherwise, and
        // the trend check leaves the metric out rather than reporting zeros as a flat line.
        try {
            Process jcmd = new ProcessBuilder(
                            Path.of(System.getProperty("java.home"), "bin", "jcmd")
                                    .toString(),
                            String.valueOf(ProcessHandle.current().pid()),
                            "VM.native_memory",
                            "summary")
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = jcmd.inputReader()) {
                String committed = reader.lines()
                        .filter(line -> line.contains("Total:") && line.contains("committed="))
                        .findFirst()
                        .orElse(null);
                jcmd.waitFor(10, TimeUnit.SECONDS);
                if (committed == null) {
                    return -1;
                }
                String digits = committed.replaceAll(".*committed=(\\d+).*", "$1");
                return Long.parseLong(digits) * 1024;
            }
        } catch (IOException | InterruptedException | RuntimeException unavailable) {
            if (unavailable instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return -1;
        }
    }

    /** Sleeps, without a scenario having to handle the interrupt itself. */
    static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", interrupted);
        }
    }
}
