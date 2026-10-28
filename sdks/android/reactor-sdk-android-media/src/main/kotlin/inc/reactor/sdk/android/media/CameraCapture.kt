package inc.reactor.sdk.android.media

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.OrientationEventListener
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The camera, for real: Camera2 to upright BGRA frames ready for `Track.pushFrame`.
 *
 * Camera2 rather than CameraX, and that is a deliberate cost. CameraX is far less code — but it
 * needs a `LifecycleOwner` and pulls four androidx artifacts onto every consumer of this module.
 * This module exists so that importing the SDK opens no hardware and imposes no dependency, and
 * taking androidx.camera to save two hundred lines here would undo that for everyone who only
 * wanted the microphone.
 *
 * Frames arrive as YUV_420_888 — three planes with their own row strides, and a chroma *pixel*
 * stride that is 1 on some devices and 2 on others — and go through [FrameConverter], which
 * converts and rotates them in one pass and is tested against [YuvToBgra] without a device.
 *
 * ### Orientation
 *
 * **Frames reach [start]'s callback already upright**, rotated as they are converted.
 * The sensor is mounted sideways on almost every handset, so a frame pushed as captured reaches
 * the model on its side whenever the phone is held in portrait — and the model cannot know to
 * turn it back. Rotating here means every app gets the right picture without writing the same
 * index arithmetic.
 *
 * **The rotation follows the device on its own**, as the Swift SDK's `Camera` does: an
 * `OrientationEventListener` tracks which way the phone is physically held while capture runs, so
 * frames stay upright through every turn — including landscape to the other landscape, which
 * changes no configuration and so never reaches `onConfigurationChanged`. It is the device's
 * orientation rather than the window's, which is what keeps a portrait-locked Activity's stream
 * upright when the phone is turned. Lying flat keeps the last orientation.
 *
 * Pass `followDeviceOrientation = false` to drive [deviceRotation] yourself instead.
 *
 * **Front-camera frames are not mirrored.** What is pushed is what the camera sees, which is what
 * the model should be given and what the Swift SDK's `Camera` pushes too. Mirroring is a preview
 * concern: draw the local preview through [VideoRenderer] with `mirror = true`.
 *
 * ### Permission
 *
 * `CAMERA` is the app's to request. [start] checks for it, so a missing grant is a message rather
 * than a `SecurityException` thrown from inside the platform.
 *
 * ### Threading
 *
 * Everything runs on this class's own background thread, and [onFrame][start] is called there.
 * Push straight into a track from it: that is the inline-callback bargain the SDK makes
 * everywhere else, and queueing instead trades a bounded drop for unbounded latency.
 *
 * [stop] tears down **on that same thread** and waits for it, so no frame is mid-conversion when
 * the `ImageReader` closes — closing a reader frees the native buffers of every image it handed
 * out — and no callback runs after [stop] returns.
 */
public class CameraCapture
    @JvmOverloads
    constructor(
        private val context: Context,
        public val facing: CameraSelection.Facing = CameraSelection.Facing.FRONT,
        public val targetWidth: Int = 640,
        public val targetHeight: Int = 480,
        deviceRotation: Int = 0,
        public val followDeviceOrientation: Boolean = true,
    ) {
        private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        /**
         * How far the device is turned from its natural orientation, in degrees: 0, 90, 180 or
         * 270 — `Display.getRotation()` times 90.
         *
         * Read per frame, so turning the phone mid-session turns the next frame rather than
         * leaving the stream sideways until a restart. With [followDeviceOrientation] (the
         * default) this class keeps it current while capturing, and a value written here lasts
         * only until the phone is next turned; without it, this is the app's to update.
         */
        @Volatile
        public var deviceRotation: Int = requireRotation(deviceRotation)
            set(value) {
                field = requireRotation(value)
            }

        /**
         * One start-to-stop, with everything it owns.
         *
         * Per start rather than fields on the class, because a stopped run can still be answered
         * after the next one has begun: `openCamera`'s reply for the old run arrives on the old
         * thread whenever the platform gets to it. With one shared `capturing` flag, that late
         * onOpened read the new run's `true` and adopted a device nobody would ever close.
         */
        private class Run(
            val thread: HandlerThread,
            val handler: Handler,
            val reader: ImageReader,
        ) {
            /** Cleared by stop() on any thread; read by every callback. */
            val live = AtomicBoolean(true)

            // The rest is touched only on [thread].
            var camera: CameraDevice? = null
            var session: CameraCaptureSession? = null

            /**
             * Whether `openCamera` has been asked and has not answered yet.
             *
             * The answer arrives on this run's looper. Quit that looper while it is pending and
             * onOpened is never delivered — so the device it would have handed over is never
             * closed, and stays held until the process dies. A stop in that window therefore
             * leaves the thread running and lets the answer itself close the device and quit.
             */
            var openPending = true

            /** Set by a teardown that found [openPending]; the open's answer finishes the quit. */
            var quitWhenOpenAnswers = false

            /**
             * Released once the device is closed and the thread told to quit — by [shutdown], or
             * by the open's late answer when shutdown had to defer to it. stop() waits on this
             * rather than on its own posted task, which finishes before a pending open does.
             */
            val finished = CountDownLatch(1)

            /** Tracks the device while this run lasts. Null without [followDeviceOrientation]. */
            var orientation: OrientationEventListener? = null
        }

        /** The run in progress, or null. Written only under [lifecycle]. */
        @Volatile private var run: Run? = null

        /**
         * Serialises [start] and [stop] — the whole of start's setup, not just its check.
         *
         * Without it a stop() landing between start's `run == null` check and the run being
         * published saw nothing to stop and returned, and the start then opened a live camera the
         * caller had already torn down; two concurrent starts could both pass the check and leak
         * a run. Never held across a wait on the camera thread, which takes no lock, so it cannot
         * deadlock against a frame.
         */
        private val lifecycle = Any()

        /** Read once in [start]; it is fixed in the hardware. */
        @Volatile private var sensorOrientation = 0

        /** The size actually opened, which is the nearest the hardware offered. Null until started. */
        public var size: CameraSelection.Size? = null
            private set

        /**
         * How far the most recent frame was rotated to be upright, from
         * [CameraSelection.rotationDegrees]. Informational: frames are already rotated by the
         * time they are delivered, so a preview of them wants zero rotation, not this.
         */
        @Volatile
        public var rotation: Int = 0
            private set

        /**
         * Open the camera and deliver upright BGRA frames to [onFrame].
         *
         * The buffer handed to [onFrame] is reused between frames: copy it if you keep it. That is
         * the same contract the SDK's own `VideoFrame.pixels` has, and for the same reason — a
         * fresh allocation per frame at 30fps is megabytes a second of garbage. The width and
         * height passed alongside are the *rotated* ones, so a portrait frame is taller than it
         * is wide.
         */
        public fun start(onFrame: (ByteBuffer, Int, Int) -> Unit) {
            synchronized(lifecycle) { startLocked(onFrame) }
        }

        @SuppressLint("MissingPermission") // Checked below, with a message worth reading.
        private fun startLocked(onFrame: (ByteBuffer, Int, Int) -> Unit) {
            // On the run, not on the device: the device is assigned asynchronously in onOpened,
            // so a second start() landing before that callback used to pass a `camera == null`
            // check and leak the first call's thread and reader.
            check(run == null) { "This CameraCapture is already started" }
            check(
                context.checkSelfPermission(Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED,
            ) {
                "The camera could not be opened because this app does not hold CAMERA. The SDK " +
                    "declares no permissions of its own — requesting it is the app's call, " +
                    "because only the app knows when to ask."
            }

            val cameraId = findCamera()
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val configs =
                characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?: error("Camera $cameraId reports no stream configuration map")
            val available =
                configs.getOutputSizes(ImageFormat.YUV_420_888).orEmpty().map {
                    CameraSelection.Size(it.width, it.height)
                }
            val chosen = CameraSelection.bestSize(available, targetWidth, targetHeight)
            size = chosen
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            rotation = currentRotation()

            val background = HandlerThread("reactor-camera").apply { start() }
            // maxImages = 2, deliberately. One is the frame being converted; the second lets the
            // hardware fill while that happens. More would buffer frames the consumer is already
            // too slow for, which is latency rather than throughput.
            val current =
                Run(
                    thread = background,
                    handler = Handler(background.looper),
                    reader = ImageReader.newInstance(chosen.width, chosen.height, ImageFormat.YUV_420_888, 2),
                )
            run = current
            if (followDeviceOrientation) current.orientation = followOrientation()

            // Allocated once per run rather than per frame, like the converter's own scratch.
            val upright = ByteBuffer.allocateDirect(chosen.width * chosen.height * 4)
            val converter = FrameConverter()
            current.reader.setOnImageAvailableListener({ source ->
                // Before acquiring: once stop() has begun, the reader is about to close, and a
                // frame that has not been taken yet is one there is no reason to touch.
                if (!current.live.get()) return@setOnImageAvailableListener
                val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val planes = image.planes
                    val degrees = currentRotation()
                    rotation = degrees
                    // Converted and rotated in one pass — see FrameConverter for why not two.
                    val turned =
                        converter.convert(
                            y = planes[0].buffer,
                            u = planes[1].buffer,
                            v = planes[2].buffer,
                            width = image.width,
                            height = image.height,
                            yRowStride = planes[0].rowStride,
                            uvRowStride = planes[1].rowStride,
                            uvPixelStride = planes[1].pixelStride,
                            degrees = degrees,
                            out = upright,
                        )
                    onFrame(upright, turned.width, turned.height)
                } catch (thrown: Throwable) {
                    // The caller's callback threw. Escaping, it kills this thread with the camera,
                    // session and reader still open and isCapturing still true — hardware held
                    // until the process dies. Contained instead: the run ends, and its release is
                    // posted behind this callback so the reader never closes under the open image.
                    Log.e(TAG, "The frame callback threw; capture has stopped", thrown)
                    if (current.live.compareAndSet(true, false)) current.handler.post { release(current) }
                } finally {
                    // Always, and before returning: an Image that is not closed is a buffer the
                    // hardware cannot refill, and two of them stall the stream permanently.
                    image.close()
                }
            }, current.handler)

            try {
                manager.openCamera(cameraId, deviceCallback(current), current.handler)
            } catch (failed: Throwable) {
                // Nothing was opened, so nothing will answer: undo what start() set up, or this
                // instance refuses every later start() as "already started" while holding a
                // thread and a reader nobody can release.
                current.live.set(false)
                current.openPending = false
                current.orientation?.disable()
                run = null
                current.handler.post { shutdown(current) }
                throw failed
            }
        }

        /**
         * Start tracking which way the device is held, seeded from the display.
         *
         * The listener only reports changes, so the starting value comes from the display's
         * rotation — the best answer available before the first sensor reading, and the right one
         * for the usual Activity that turns with the phone. Readings arrive on the main looper and
         * only write [deviceRotation], which every frame reads.
         */
        private fun followOrientation(): OrientationEventListener? {
            val display =
                (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                    ?.getDisplay(Display.DEFAULT_DISPLAY)
            if (display != null) deviceRotation = display.rotation * 90
            val listener =
                object : OrientationEventListener(context) {
                    override fun onOrientationChanged(orientation: Int) {
                        deviceRotation = CameraSelection.deviceRotationFor(orientation, deviceRotation)
                    }
                }
            // A device with no accelerometer keeps the display's rotation for the whole run.
            if (!listener.canDetectOrientation()) return null
            listener.enable()
            return listener
        }

        private fun currentRotation(): Int =
            CameraSelection.rotationDegrees(
                sensorOrientation = sensorOrientation,
                deviceRotation = deviceRotation,
                facing = facing,
            )

        private fun deviceCallback(current: Run) =
            object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    current.openPending = false
                    // A stop() that landed before the open completed has already released
                    // everything it could see — and this device was not yet among it. Closed
                    // here, or it stays held until the process dies and no other app can open
                    // the camera.
                    if (!current.live.get()) {
                        device.close()
                        finishPendingQuit(current)
                        return
                    }
                    current.camera = device
                    try {
                        configure(current, device)
                    } catch (failed: Exception) {
                        // createCaptureRequest and createCaptureSession throw on a device that
                        // disconnected or closed between being opened and being configured.
                        setupFailed(current, failed)
                    }
                }

                override fun onDisconnected(device: CameraDevice) {
                    // The platform took the camera away — another app, or the screen locking.
                    lost(current, device)
                }

                override fun onError(
                    device: CameraDevice,
                    error: Int,
                ) {
                    lost(current, device)
                }
            }

        /** Build the repeating request and the session for an opened [device]. Camera thread only. */
        private fun configure(
            current: Run,
            device: CameraDevice,
        ) {
            val surface = current.reader.surface
            val request =
                device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(surface)
                    // Continuous rather than per-frame: a video stream wants the autofocus
                    // and exposure that keep adjusting, not the one-shot kind a photo wants.
                    set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                    )
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                }
            val sessionCallback =
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        // Same window as onOpened, one step later: the device was
                        // already closed by the teardown, so this session is dead.
                        if (!current.live.get()) {
                            configured.close()
                            return
                        }
                        current.session = configured
                        try {
                            configured.setRepeatingRequest(request.build(), null, current.handler)
                        } catch (failed: Exception) {
                            // CameraAccessException when the device went away while the
                            // session was configuring, IllegalStateException when it closed.
                            setupFailed(current, failed)
                        }
                    }

                    override fun onConfigureFailed(configured: CameraCaptureSession) {
                        // Nothing to throw to: this arrives on the camera's own
                        // thread, long after start() returned. Releasing is the only
                        // useful response, and leaves isCapturing false for the
                        // caller to see; stop() still quits the thread.
                        current.live.set(false)
                        configured.close()
                        release(current)
                    }
                }
            createSession(device, surface, sessionCallback, current.handler)
        }

        /**
         * Configure a capture session onto [surface], on [handler]'s thread.
         *
         * Two paths because the module's floor is API 26 and `SessionConfiguration` arrived in
         * 28: calling it on Android 8.0 or 8.1 is a `NoSuchMethodError` the moment the camera
         * opens. The older overload does the same thing for a single plain surface, which is all
         * this class ever configures.
         */
        private fun createSession(
            device: CameraDevice,
            surface: Surface,
            callback: CameraCaptureSession.StateCallback,
            handler: Handler,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                device.createCaptureSession(
                    SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        listOf(OutputConfiguration(surface)),
                        { runnable -> handler.post(runnable) },
                        callback,
                    ),
                )
            } else {
                @Suppress("DEPRECATION")
                device.createCaptureSession(listOf(surface), callback, handler)
            }
        }

        /**
         * The asynchronous half of [start] failed: nothing to throw to on this thread, so the run
         * ends the way [lost] ends it — released, isCapturing false — and stop() still quits the
         * thread. Left to escape, the exception kills the camera thread with the device held and
         * isCapturing still true. Camera thread only.
         */
        private fun setupFailed(
            current: Run,
            failed: Exception,
        ) {
            Log.e(TAG, "The camera opened but could not be configured; capture has stopped", failed)
            current.live.set(false)
            release(current)
        }

        /** The device went away on its own. Released here; stop() still quits the thread. */
        private fun lost(
            current: Run,
            device: CameraDevice,
        ) {
            current.openPending = false
            current.live.set(false)
            device.close()
            release(current)
            finishPendingQuit(current)
        }

        private fun findCamera(): String {
            val wanted =
                if (facing == CameraSelection.Facing.FRONT) {
                    CameraCharacteristics.LENS_FACING_FRONT
                } else {
                    CameraCharacteristics.LENS_FACING_BACK
                }
            manager.cameraIdList.forEach { id ->
                if (manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == wanted) {
                    return id
                }
            }
            error(
                "This device has no ${facing.name.lowercase()} camera. Available ids: " +
                    manager.cameraIdList.joinToString(", ").ifEmpty { "none" },
            )
        }

        /** Whether frames are flowing. False after a disconnect or a configuration failure. */
        public val isCapturing: Boolean
            get() = run?.live?.get() == true

        /**
         * Stop and release everything, waiting until it is done. Idempotent.
         *
         * When this returns, the camera is closed and no frame callback is running or will run.
         * Safe to call from the frame callback itself, where it releases inline instead of
         * waiting on its own thread.
         */
        public fun stop() {
            val current =
                synchronized(lifecycle) {
                    val claimed = run ?: return
                    run = null
                    claimed.live.set(false)
                    claimed
                }
            // From the caller's thread, not the camera's: the listener belongs to the main
            // looper, and disable() is safe from anywhere.
            current.orientation?.disable()
            if (Looper.myLooper() == current.thread.looper) {
                shutdown(current)
                return
            }
            // Posted behind any frame already queued, and waited for: by the time the release
            // runs the listener has returned, so the reader closes with no image open — and a
            // frame dequeued after it sees `live` false and touches nothing.
            //
            // Waited on until the open has answered too, when one was pending: returning earlier
            // let an immediate restart race the old open for the same camera and fail in use.
            val posted = current.handler.post { shutdown(current) }
            if (posted && !current.finished.await(TEARDOWN_WAIT_MS, TimeUnit.MILLISECONDS)) {
                // A frame callback that will not return, or an open the platform never answers.
                // Closing the reader from here would free the buffers that callback is reading,
                // so nothing is closed from this thread: the release stays queued behind it.
                Log.w(
                    TAG,
                    "The camera did not finish closing within ${TEARDOWN_WAIT_MS}ms; its " +
                        "release is left queued on the camera thread.",
                )
            }
        }

        /** Release, then quit the thread — unless an open is still to answer. Camera thread only. */
        private fun shutdown(current: Run) {
            release(current)
            if (current.openPending) {
                current.quitWhenOpenAnswers = true
            } else {
                current.thread.quitSafely()
                current.finished.countDown()
            }
        }

        /** The other half of [shutdown]'s deferral, run by whichever answer the open gets. */
        private fun finishPendingQuit(current: Run) {
            if (!current.quitWhenOpenAnswers) return
            current.quitWhenOpenAnswers = false
            current.thread.quitSafely()
            current.finished.countDown()
        }

        /**
         * Session, then camera, then reader. Closing the reader first would pull the surface out
         * from under a session still repeating into it. Camera thread only, and idempotent.
         */
        private fun release(current: Run) {
            runCatching { current.session?.close() }
            current.session = null
            runCatching { current.camera?.close() }
            current.camera = null
            runCatching { current.reader.close() }
        }

        private companion object {
            const val TAG = "ReactorCamera"

            /**
             * How long [stop] waits for the camera thread. One frame's conversion is a few
             * milliseconds; this is bounded only so a callback that never returns cannot hold
             * the caller's teardown — usually the UI thread — open for ever.
             */
            const val TEARDOWN_WAIT_MS = 2_000L

            fun requireRotation(degrees: Int): Int {
                require(degrees % 90 == 0) { "deviceRotation must be a multiple of 90, got $degrees" }
                return ((degrees % 360) + 360) % 360
            }
        }
    }
