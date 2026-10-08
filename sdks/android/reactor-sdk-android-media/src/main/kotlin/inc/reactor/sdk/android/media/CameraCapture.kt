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
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The camera, for real: Camera2 to BGRA frames ready for `Track.pushFrame`.
 *
 * Camera2 rather than CameraX, and that is a deliberate cost. CameraX is far less code — but it
 * needs a `LifecycleOwner` and pulls four androidx artifacts onto every consumer of this module.
 * This module exists so that importing the SDK opens no hardware and imposes no dependency, and
 * taking androidx.camera to save two hundred lines here would undo that for everyone who only
 * wanted the microphone.
 *
 * Frames arrive as YUV_420_888 — three planes with their own row strides, and a chroma *pixel*
 * stride that is 1 on some devices and 2 on others — and go through [YuvToBgra], which is where
 * that arithmetic is tested without a device.
 *
 * ### Permission
 *
 * `CAMERA` is the app's to request. [start] checks for it, so a missing grant is a message rather
 * than a `SecurityException` thrown from inside the platform.
 *
 * ### Threading
 *
 * Everything runs on this class's own background thread, and [onFrame] is called there. Push
 * straight into a track from it: that is the inline-callback bargain the SDK makes everywhere
 * else, and queueing instead trades a bounded drop for unbounded latency.
 */
public class CameraCapture
    @JvmOverloads
    constructor(
        private val context: Context,
        public val facing: CameraSelection.Facing = CameraSelection.Facing.FRONT,
        public val targetWidth: Int = 640,
        public val targetHeight: Int = 480,
        /**
         * How far the device is turned from its natural orientation, in degrees.
         *
         * Taken as a value rather than read from a `Display`, because the caller is the one who
         * knows whether it is rendering into a rotating Activity or a fixed-orientation one — and
         * because reading it here would make [CameraSelection.rotationDegrees] untestable.
         */
        public val deviceRotation: Int = 0,
    ) {
        private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        private var thread: HandlerThread? = null
        private var handler: Handler? = null
        private var camera: CameraDevice? = null
        private var session: CameraCaptureSession? = null
        private var reader: ImageReader? = null
        private val capturing = AtomicBoolean(false)

        /** The size actually opened, which is the nearest the hardware offered. Null until started. */
        public var size: CameraSelection.Size? = null
            private set

        /** How far frames need rotating to be upright, from [CameraSelection.rotationDegrees]. */
        public var rotation: Int = 0
            private set

        /**
         * Open the camera and deliver BGRA frames to [onFrame].
         *
         * The buffer handed to [onFrame] is reused between frames: copy it if you keep it. That is
         * the same contract the SDK's own `VideoFrame.pixels` has, and for the same reason — a
         * fresh allocation per frame at 30fps is megabytes a second of garbage.
         */
        @SuppressLint("MissingPermission") // Checked below, with a message worth reading.
        public fun start(onFrame: (ByteBuffer, Int, Int) -> Unit) {
            check(camera == null) { "This CameraCapture is already started" }
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
            rotation =
                CameraSelection.rotationDegrees(
                    sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                    deviceRotation = deviceRotation,
                    facing = facing,
                )

            val background = HandlerThread("reactor-camera").apply { start() }
            thread = background
            val post = Handler(background.looper)
            handler = post

            // maxImages = 2, deliberately. One is the frame being converted; the second lets the
            // hardware fill while that happens. More would buffer frames the consumer is already
            // too slow for, which is latency rather than throughput.
            val imageReader =
                ImageReader.newInstance(chosen.width, chosen.height, ImageFormat.YUV_420_888, 2)
            reader = imageReader
            capturing.set(true)

            val bgra = ByteBuffer.allocateDirect(chosen.width * chosen.height * 4)
            imageReader.setOnImageAvailableListener({ source ->
                val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    if (!capturing.get()) return@setOnImageAvailableListener
                    val planes = image.planes
                    bgra.clear()
                    YuvToBgra.convert(
                        y = planes[0].buffer,
                        u = planes[1].buffer,
                        v = planes[2].buffer,
                        width = image.width,
                        height = image.height,
                        yRowStride = planes[0].rowStride,
                        uvRowStride = planes[1].rowStride,
                        uvPixelStride = planes[1].pixelStride,
                        out = bgra,
                    )
                    bgra.rewind()
                    onFrame(bgra, image.width, image.height)
                } finally {
                    // Always, and before returning: an Image that is not closed is a buffer the
                    // hardware cannot refill, and two of them stall the stream permanently.
                    image.close()
                }
            }, post)

            manager.openCamera(cameraId, deviceCallback(imageReader, post), post)
        }

        private fun deviceCallback(
            imageReader: ImageReader,
            post: Handler,
        ) = object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camera = device
                val output = OutputConfiguration(imageReader.surface)
                val request =
                    device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(imageReader.surface)
                        // Continuous rather than per-frame: a video stream wants the autofocus and
                        // exposure that keep adjusting, not the one-shot kind a photo wants.
                        set(
                            CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                        )
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    }
                device.createCaptureSession(
                    SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        listOf(output),
                        { runnable -> post.post(runnable) },
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(configured: CameraCaptureSession) {
                                session = configured
                                if (!capturing.get()) return
                                configured.setRepeatingRequest(request.build(), null, post)
                            }

                            override fun onConfigureFailed(configured: CameraCaptureSession) {
                                // Nothing to throw to: this arrives on the camera's own thread,
                                // long after start() returned. Tearing down is the only useful
                                // response, and leaves isCapturing false for the caller to see.
                                capturing.set(false)
                                configured.close()
                            }
                        },
                    ),
                )
            }

            override fun onDisconnected(device: CameraDevice) {
                // The platform took the camera away — another app, or the screen locking.
                capturing.set(false)
                device.close()
                camera = null
            }

            override fun onError(
                device: CameraDevice,
                error: Int,
            ) {
                capturing.set(false)
                device.close()
                camera = null
            }
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
            get() = capturing.get()

        /** Stop and release everything, in the order the platform requires. Idempotent. */
        public fun stop() {
            capturing.set(false)
            // Session, then camera, then reader, then thread. Closing the reader first would pull
            // the surface out from under a session still repeating into it; closing the thread
            // first would leave callbacks with no looper to run on.
            runCatching { session?.close() }
            session = null
            runCatching { camera?.close() }
            camera = null
            runCatching { reader?.close() }
            reader = null
            thread?.quitSafely()
            thread = null
            handler = null
        }
    }
