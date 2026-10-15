package inc.reactor.sdk.android.media

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The backend a [Microphone] or [Speaker] drives.
 *
 * An interface so the lifecycle above it is testable without a device: the races that matter here
 * are between `stop()` and a callback already in flight, and those are reproducible with a fake
 * and not with real hardware.
 */
public interface CaptureDevice {
    /** Begin delivering buffers to [onData]. */
    public fun start(onData: (ShortArray) -> Unit)

    /**
     * Stop, **waiting for any in-flight callback to return**.
     *
     * This is the property that decides how [Microphone] guards its state — see the note there.
     * A real `AudioRecord.stop()` behaves this way.
     */
    public fun stop()
}

/** The backend a [Speaker] drives. Its callback model is the opposite of a capture device's. */
public interface RenderDevice {
    public fun start()

    /** Hand over samples to play. Never called from the device's own thread. */
    public fun write(samples: ShortArray)

    /** Stop. Does **not** wait for a caller-supplied callback, because there is not one. */
    public fun stop()
}

/**
 * Microphone capture, off by default and started only when asked.
 *
 * **Guarded by an atomic, not a mutex, and the asymmetry with [Speaker] is the point.**
 *
 * Closing a capture device *waits for its capture callback to return*. If that callback took the
 * same mutex `stop()` holds, the callback would be waiting for the thread that is waiting for the
 * callback, and the process would deadlock. So the running flag is atomic and the callback takes
 * no lock at all — it reads the flag and drops the buffer if capture is stopping.
 *
 * [Speaker] does the opposite with a mutex, and that is correct *there* because its render path
 * never runs under the lock. Copying either fix to the other side is a deadlock or a race; this
 * is the same trade-off the C++ SDK's speaker and microphone had to make differently.
 */
public class Microphone(
    private val device: CaptureDevice,
    private val targetSampleRate: Int,
    private val deviceSampleRate: Int,
    private val channels: Int,
) {
    private val running = AtomicBoolean(false)

    /**
     * Serialises the two lifecycle transitions, and **only** those.
     *
     * The atomic alone is not enough, and the gap is not theoretical: `running` used to be
     * committed before `device.start()` returned, so a `stop()` landing in that window cleared the
     * flag and stopped a backend that had not started — and then the pending `start` brought it
     * up. Every later `stop()` returned early on the flag, leaving the microphone live with
     * `isCapturing` reading false. Nothing could turn it off again.
     *
     * Holding this across `device.stop()` is safe precisely because the capture callback takes no
     * lock: it reads the atomic and returns. That is the whole reason the flag stays an atomic
     * rather than becoming a second mutex — see the class note above.
     */
    private val lifecycle = ReentrantLock()

    /**
     * Start capturing, handing mono samples at [targetSampleRate] to [onSamples].
     *
     * [onSamples] runs on the device's capture thread. Push straight into a track from it —
     * queueing instead is what turns a bounded drop into unbounded latency.
     */
    public fun start(onSamples: (ShortArray) -> Unit) {
        lifecycle.withLock {
            check(!running.get()) { "This Microphone is already capturing" }
            running.set(true)
            // One resampler for the whole capture, not one per buffer. A stateless resample
            // restarts its phase at every buffer boundary, which at 44.1k to 48k drops or repeats
            // a sample every few milliseconds — inaudible per buffer and a drifting, artefacted
            // stream over a long capture.
            val resampler = PcmConversion.Resampler(deviceSampleRate, targetSampleRate)
            try {
                device.start { raw ->
                    // Checked inside the callback, without a lock. A buffer captured while
                    // stopping is dropped rather than delivered — which is the difference between
                    // a clean stop and a push into a track the caller has already torn down.
                    if (!running.get()) return@start
                    onSamples(resampler.resample(PcmConversion.toMono(raw, channels)))
                }
            } catch (failedToStart: Throwable) {
                // Rolled back, or a device that refused to open leaves this Microphone claiming to
                // capture forever: start() would refuse as "already capturing" and stop() would
                // call stop() on a backend that never started.
                running.set(false)
                throw failedToStart
            }
        }
    }

    /** Stop capturing. Idempotent. */
    public fun stop() {
        lifecycle.withLock {
            if (!running.compareAndSet(true, false)) return
            // Safe to call under the lock even though a callback may be running: that callback
            // takes no lock, so waiting for it here cannot deadlock.
            device.stop()
        }
    }

    public val isCapturing: Boolean
        get() = running.get()
}

/**
 * Speaker playout.
 *
 * **Guarded by a mutex, unlike [Microphone].** Its render path does not run under this lock — the
 * device pulls from its own buffer — so holding it across `stop()` is safe, and a mutex gives the
 * stronger guarantee that no `write` can interleave with a teardown.
 */
public class Speaker(
    private val device: RenderDevice,
) {
    private val lock = ReentrantLock()
    private var running = false

    public fun start() {
        lock.withLock {
            check(!running) { "This Speaker is already playing" }
            running = true
            device.start()
        }
    }

    /** Play [samples]. Silently ignored once stopped, rather than throwing into an audio loop. */
    public fun write(samples: ShortArray) {
        lock.withLock {
            if (!running) return
            device.write(samples)
        }
    }

    /** Stop. Idempotent. */
    public fun stop() {
        lock.withLock {
            if (!running) return
            running = false
            device.stop()
        }
    }

    public val isPlaying: Boolean
        get() = lock.withLock { running }
}
