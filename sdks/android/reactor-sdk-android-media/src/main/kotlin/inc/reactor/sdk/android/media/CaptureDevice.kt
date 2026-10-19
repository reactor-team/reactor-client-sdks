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
     * Begin delivering buffers to [onData], and report to [onFailure] if capture ends on its own.
     *
     * A device can stop delivering without anyone calling [stop] — the audio server restarting,
     * or another app taking the microphone — and without this the layer above keeps reporting
     * that it is capturing while nothing arrives. [onFailure] runs at most once, on the device's
     * own thread, after the last [onData]. The device still needs [stop] to release it.
     *
     * Defaults to the one-argument form, for a backend that cannot fail mid-capture.
     */
    public fun start(
        onData: (ShortArray) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        start(onData)
    }

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

    /**
     * Hand over samples to play. Never called from the device's own thread.
     *
     * Throws if the device can no longer play — the audio server restarting under it, say. A
     * write that fails quietly is a speaker that keeps reporting it is playing while every buffer
     * after it is lost; throwing is what lets [Speaker] stop and say so.
     */
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
    /** Whether buffers are being delivered. Read lock-free by the capture callback. */
    private val running = AtomicBoolean(false)

    /**
     * Whether the device has been started and not yet stopped. Guarded by [lifecycle].
     *
     * Distinct from [running], because a device that fails mid-capture clears [running] from its
     * own thread — so `isCapturing` stops lying — but still holds the hardware until `stop()`.
     * Releasing it from the failure callback instead would mean the device's `stop()` waiting for
     * the very thread that is calling it.
     */
    private var started = false

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
     * Start capturing, without being told if the device fails — [isCapturing] still goes false.
     *
     * An overload rather than a default argument: with a default, `start { samples -> }` would
     * bind its trailing lambda to the error handler instead of the samples.
     */
    public fun start(onSamples: (ShortArray) -> Unit) {
        start(onSamples) { }
    }

    /**
     * Start capturing, handing mono samples at [targetSampleRate] to [onSamples].
     *
     * [onSamples] runs on the device's capture thread. Push straight into a track from it —
     * queueing instead is what turns a bounded drop into unbounded latency.
     *
     * [onError] is told if capture ends without [stop] — the device failing under it. By then
     * [isCapturing] already reads false; [start] again (or [stop]) releases the dead device.
     */
    public fun start(
        onSamples: (ShortArray) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        lifecycle.withLock {
            check(!running.get()) { "This Microphone is already capturing" }
            // A device that failed under a previous start still holds the hardware: release it
            // before opening again, or the second open fails on a microphone this one owns.
            if (started) {
                started = false
                device.stop()
            }
            running.set(true)
            // One resampler for the whole capture, not one per buffer. A stateless resample
            // restarts its phase at every buffer boundary, which at 44.1k to 48k drops or repeats
            // a sample every few milliseconds — inaudible per buffer and a drifting, artefacted
            // stream over a long capture.
            val resampler = PcmConversion.Resampler(deviceSampleRate, targetSampleRate)
            try {
                device.start(
                    { raw ->
                        // Checked inside the callback, without a lock. A buffer captured while
                        // stopping is dropped rather than delivered — which is the difference
                        // between a clean stop and a push into a track already torn down.
                        if (running.get()) {
                            onSamples(resampler.resample(PcmConversion.toMono(raw, channels)))
                        }
                    },
                    { failure ->
                        // No lock here either, for the same reason as the data callback. Only a
                        // capture that was still meant to be running is reported: a failure that
                        // races stop() is the teardown, not news.
                        if (running.compareAndSet(true, false)) onError(failure)
                    },
                )
                started = true
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
            running.set(false)
            // On `started`, not on `running`: a device that already failed has cleared the flag
            // and still needs releasing.
            if (!started) return
            started = false
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
    private var onError: (Throwable) -> Unit = { }

    /** Start playing, without being told if the device fails — [isPlaying] still goes false. */
    public fun start() {
        start { }
    }

    /**
     * Start playing.
     *
     * [onError] is told if the device fails during a [write] — by then [isPlaying] already reads
     * false and the device is released; [start] again to reopen it. It runs on the thread that
     * called [write], after this Speaker's lock is released.
     */
    public fun start(onError: (Throwable) -> Unit) {
        lock.withLock {
            check(!running) { "This Speaker is already playing" }
            // Committed only once the device is up. Set first, a device that refused to open left
            // this Speaker "already playing" for good, and stop() then stopped a backend that
            // never started. The order is free here because the lock covers both.
            device.start()
            this.onError = onError
            running = true
        }
    }

    /**
     * Play [samples]. Silently ignored once stopped, rather than throwing into an audio loop.
     *
     * A device that fails here does not throw either, for the same reason: the Speaker stops,
     * releases the device and reports the failure to the `onError` given to [start].
     */
    public fun write(samples: ShortArray) {
        val (failure, report) =
            lock.withLock {
                if (!running) return
                try {
                    device.write(samples)
                    return
                } catch (failed: Exception) {
                    // Released here rather than left for stop(): unlike capture, nothing on the
                    // device's side is waiting on this thread, so stopping under the lock cannot
                    // deadlock — and a dead device left open would make the next start() fail.
                    running = false
                    runCatching { device.stop() }
                    // The handler is taken with the failure, under the lock: read after it, a
                    // start() racing in between would have replaced it, and this playback's
                    // failure would reach — and could tear down — the next one.
                    failed to onError
                }
            }
        // Outside the lock, so a handler that restarts or stops this Speaker does not do it from
        // inside a write.
        report(failure)
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
