package inc.reactor.sdk.android.endurance

import android.os.Bundle
import android.os.Debug
import androidx.test.platform.app.InstrumentationRegistry
import inc.reactor.sdk.android.ConnectionStatus
import inc.reactor.sdk.android.Reactor
import inc.reactor.sdk.android.ReactorOptions
import inc.reactor.sdk.android.internal.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Assume
import java.io.File

/**
 * The run: how long it goes on for, what is measured, and how often.
 *
 * Unit tests and the live suite are both short-lived. Neither can see a handle, a JNI global or a
 * descriptor that leaks a little on every cycle — this runs for minutes to hours instead and
 * watches whether the numbers keep climbing.
 *
 * Every reading comes from `/proc/self`, which is the kernel's own account of this process. The
 * managed-runtime equivalents do not answer the question: `Runtime.totalMemory()` describes ART's
 * heap and says nothing about what the native side allocated, and `Thread.activeCount()` counts
 * only the current group's Java threads — not the ones libwebrtc and the FFI start, which are most
 * of them and exactly the ones that might leak.
 */
internal class Endurance private constructor(
    private val scenario: String,
    private val description: String,
) {
    private val samples = mutableListOf<Sample>()
    private val startedAt = System.nanoTime()
    private val deadlineNanos = startedAt + durationSeconds() * 1_000_000_000L
    private var lastPrintedAt = 0L
    private var cycle = 0

    companion object {
        private val args: Bundle get() = InstrumentationRegistry.getArguments()

        private fun arg(name: String): String? = args.getString(name)?.takeIf { it.isNotBlank() }

        /** How long a run lasts, from `enduranceDurationMinutes`. */
        fun durationSeconds(): Long = ((arg("enduranceDurationMinutes")?.toDoubleOrNull() ?: 5.0) * 60).toLong()

        /** The key, or an aborted test — this suite cannot run without reaching the platform. */
        fun apiKey(): String =
            arg("reactorApiKey") ?: run {
                Assume.assumeTrue("no API key set — skipping the endurance suite", false)
                error("unreachable")
            }

        fun apiUrl(): String = arg("reactorApiUrl") ?: "https://api.reactor.inc"

        fun model(): String = arg("reactorModel") ?: "reactor/echo"

        private fun sdkVersion(): String = Reactor.SDK_VERSION

        private fun commit(): String = arg("commitSha") ?: "(local)"

        /**
         * Where reports land: app-private external storage, which the harness script pulls off the
         * device afterwards.
         *
         * Not the app's internal `filesDir`: `adb pull` cannot read it without root, and a report
         * that exists only inside a sandbox on an emulator that is about to be destroyed is a
         * report nobody reads.
         */
        fun resultsDirectory(): File {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            return File(context.getExternalFilesDir(null), "endurance-results").apply { mkdirs() }
        }

        /** How many times a connect is attempted before the run gives up on it. */
        private const val CONNECT_ATTEMPTS = 5
        private const val CONNECT_RETRY_DELAY_MS = 5_000L

        /**
         * Runs one scenario and writes its report, whichever way it ends.
         *
         * Every scenario goes through here rather than calling `finish` itself. A scenario that
         * finished with its own call put report generation on the success path: a run that threw on
         * cycle 4,000 — which is the run somebody actually needs to read — cleaned up, propagated,
         * and left nothing behind, while the workflow dutifully uploaded an empty directory. The
         * shape is the fix: a new scenario cannot forget what it never had to remember.
         *
         * The original failure wins. A metric that also fails is attached to it as suppressed
         * rather than replacing it, because "still climbing" is a worse answer than the exception
         * that stopped the run.
         */
        suspend fun run(
            scenario: String,
            description: String,
            liveClientsBaselineZero: Boolean,
            body: suspend (Endurance) -> Unit,
        ) {
            val run = Endurance(scenario, description)
            var failure: Throwable? = null
            try {
                body(run)
            } catch (thrown: Throwable) {
                failure = thrown
            }
            // Nothing to report from a run that never took a reading: a scenario aborted for want
            // of a key, or one that failed before its first cycle, would otherwise leave three
            // files saying nothing and bury the run that has something to say.
            if (run.samples.isNotEmpty()) {
                try {
                    run.finish(liveClientsBaselineZero)
                } catch (verdict: Throwable) {
                    if (failure == null) throw verdict
                    failure.addSuppressed(verdict)
                }
            }
            if (failure != null) throw failure
        }

        /**
         * A connected client, retrying a connect that failed for a reason the platform is entitled
         * to have.
         *
         * A run that is unattended for hours specifically so nobody has to watch it must not end on
         * a few seconds of someone else's contention — a create-session answered with a 500 while
         * seven scenarios create sessions at once has ended a run and taken 115 cycles of trend
         * with it, in another binding. Every suite has this for the same reason.
         *
         * A failed attempt is disconnected before it is abandoned, not merely closed: the connect
         * may have got as far as creating a session before it failed, and `close()` does not end
         * one server-side. Left alone that session runs until its own idle timeout, holding
         * capacity the next attempt wants.
         */
        suspend fun connected(apiKey: String): Reactor {
            var last: Throwable? = null
            repeat(CONNECT_ATTEMPTS) { attempt ->
                val reactor =
                    Reactor(
                        ReactorOptions(apiUrl = apiUrl(), apiKey = apiKey),
                        // Unconfined, not Main: the instrumentation thread has no looper, and a
                        // run that deadlocks on its own event delivery measures nothing.
                        dispatcher = Dispatchers.Unconfined,
                    )
                try {
                    withTimeout(90_000) {
                        reactor.connect(model())
                        reactor.status.first { it == ConnectionStatus.READY }
                    }
                    return reactor
                } catch (failed: Throwable) {
                    last = failed
                    runCatching { withTimeout(30_000) { reactor.disconnect() } }
                    reactor.close()
                    if (attempt < CONNECT_ATTEMPTS - 1) delay(CONNECT_RETRY_DELAY_MS)
                }
            }
            throw IllegalStateException("the platform refused $CONNECT_ATTEMPTS connects in a row", last)
        }
    }

    /** Whether the run still has time left. */
    fun keepGoing(): Boolean = System.nanoTime() < deadlineNanos

    /** Takes a reading, and reprints the status block if it is time. */
    fun endOfCycle() {
        cycle++
        val sample =
            Sample(
                cycle = cycle,
                elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0,
                rssBytes = residentBytes(),
                cpuNanos = processCpuNanos(),
                threads = threadCount(),
                openFds = openFileDescriptors(),
                liveClients = Diagnostics.liveClients,
                orphanedGlobals = Diagnostics.orphanedGlobals,
                nativeHeapBytes = Debug.getNativeHeapAllocatedSize(),
            )
        samples += sample
        report(sample)
    }

    /** Every reading so far, for a scenario's own invariant. */
    fun samples(): List<Sample> = samples.toList()

    private fun finish(liveClientsBaselineZero: Boolean) {
        Report.finishAndCheck(
            directory = resultsDirectory(),
            scenario = scenario,
            description = description,
            sdkVersion = sdkVersion(),
            commit = commit(),
            durationSeconds = durationSeconds().toDouble(),
            samples = samples,
            metrics = Trends.standardResourceMetrics(samples, liveClientsBaselineZero),
        )
    }

    private fun report(sample: Sample) {
        val verbose = arg("enduranceVerbose") == "1"
        val now = System.nanoTime()
        if (!verbose && now - lastPrintedAt < 30_000_000_000L) return
        lastPrintedAt = now
        // One block reprinted on an interval, rather than a row per cycle: a run of several hours
        // is otherwise a log nobody reads. enduranceVerbose=1 brings the per-cycle detail back.
        println(
            "[$scenario] ${sample.elapsedSeconds.toLong()}s  cycle ${sample.cycle}  " +
                "rss ${sample.rssBytes / 1_048_576} MB  native ${sample.nativeHeapBytes / 1_048_576} MB  " +
                "threads ${sample.threads}  fds ${sample.openFds}  " +
                "live ${sample.liveClients}  orphaned ${sample.orphanedGlobals}",
        )
    }

    /** Resident set size from `/proc/self/status`, in bytes, or -1 if it cannot be read. */
    private fun residentBytes(): Long =
        runCatching {
            File("/proc/self/status").useLines { lines ->
                lines
                    .firstOrNull { it.startsWith("VmRSS:") }
                    ?.filter { it.isDigit() }
                    ?.toLongOrNull()
                    ?.times(1024)
                    ?: -1L
            }
        }.getOrDefault(-1L)

    /**
     * Every thread in the process, not just the Java ones.
     *
     * `/proc/self/task` counts what the kernel scheduled, which includes the libwebrtc and FFI
     * threads. Those are most of them, and the ones a leak would show up in.
     */
    private fun threadCount(): Int = runCatching { File("/proc/self/task").list()?.size ?: -1 }.getOrDefault(-1)

    private fun openFileDescriptors(): Long = runCatching { (File("/proc/self/fd").list()?.size ?: -1).toLong() }.getOrDefault(-1L)

    /**
     * Process CPU time from `/proc/self/stat`: utime + stime, converted from clock ticks.
     *
     * Fields 14 and 15, counted from 1, after the comm field — which is parenthesised and may
     * itself contain spaces, so the split starts after the last `)` rather than at the first.
     */
    private fun processCpuNanos(): Long =
        runCatching {
            val stat = File("/proc/self/stat").readText()
            val fields = stat.substring(stat.lastIndexOf(')') + 2).split(' ')
            // utime and stime are fields 14 and 15 overall; the substring dropped the first three.
            val ticks = fields[11].toLong() + fields[12].toLong()
            // Android's clock tick is 100 Hz on every supported ABI. Reading it properly needs
            // sysconf(_SC_CLK_TCK) through JNI, which is a native call this suite would then have
            // to ship for the sake of a constant that has not changed.
            ticks * 10_000_000L
        }.getOrDefault(-1L)
}
