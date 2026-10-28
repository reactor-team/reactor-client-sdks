package inc.reactor.sdk.android.internal

import inc.reactor.sdk.android.ErrorCode
import inc.reactor.sdk.android.StepFile
import inc.reactor.sdk.android.StepFrame
import inc.reactor.sdk.android.StepResult
import inc.reactor.sdk.android.StepTrackOutput
import inc.reactor.sdk.android.TrackKind

/**
 * The `reactor_step_result_*` accessors, over one result the native side still owns.
 *
 * An interface so [StepResults.read] can be tested on the JVM: the real implementation is
 * [NativeStepResults], which only exists where the native library loads.
 */
internal interface StepResultSource {
    fun step(result: Long): Long

    fun elapsed(result: Long): Double

    fun error(result: Long): String?

    fun fileCount(result: Long): Int

    fun fileName(
        result: Long,
        file: Int,
    ): String?

    fun fileContentType(
        result: Long,
        file: Int,
    ): String?

    fun fileSize(
        result: Long,
        file: Int,
    ): Long

    fun trackCount(result: Long): Int

    fun trackName(
        result: Long,
        track: Int,
    ): String?

    fun trackKind(
        result: Long,
        track: Int,
    ): String?

    fun trackRate(
        result: Long,
        track: Int,
    ): Double

    fun frameCount(
        result: Long,
        track: Int,
    ): Int

    fun frameEncoding(
        result: Long,
        track: Int,
        frame: Int,
    ): String?

    fun frameDtype(
        result: Long,
        track: Int,
        frame: Int,
    ): String?

    fun frameShape(
        result: Long,
        track: Int,
        frame: Int,
    ): IntArray?

    /** The frame's bytes, copied onto the heap. */
    fun frameData(
        result: Long,
        track: Int,
        frame: Int,
    ): ByteArray?

    /** The sender's tag, copied, or null when it attached none. */
    fun frameMetadata(
        result: Long,
        track: Int,
        frame: Int,
    ): ByteArray?

    /** The capture time in microseconds, or null when the frame carries none. */
    fun frameCaptureTimeUs(
        result: Long,
        track: Int,
        frame: Int,
    ): Long?
}

/**
 * Copies a step's native result into a [StepResult].
 *
 * Runs on the native completion thread, inside the step trampoline, which frees the native result
 * as soon as this returns — so everything is copied here and nothing kept points into it. A frame
 * whose bytes do not add up to its shape is a decode failure rather than a frame a caller would
 * read past the end of.
 */
internal object StepResults {
    fun read(
        source: StepResultSource,
        result: Long,
    ): StepResult {
        val tracks =
            (0 until source.trackCount(result)).map { track ->
                val name = source.trackName(result, track) ?: malformed("track $track has no name")
                val kind =
                    TrackKind.of(source.trackKind(result, track))
                        ?: malformed("track \"$name\" has kind ${source.trackKind(result, track)}")
                StepTrackOutput(
                    name = name,
                    kind = kind,
                    rate = source.trackRate(result, track),
                    frames = (0 until source.frameCount(result, track)).map { frame -> frame(source, result, name, track, frame) },
                )
            }
        val files =
            (0 until source.fileCount(result)).map { file ->
                StepFile(
                    name = source.fileName(result, file) ?: malformed("file $file has no name"),
                    contentType = source.fileContentType(result, file) ?: "application/octet-stream",
                    size = source.fileSize(result, file),
                )
            }
        return StepResult(
            step = source.step(result),
            tracks = tracks,
            elapsed = source.elapsed(result),
            files = files,
            error = source.error(result),
        )
    }

    private fun frame(
        source: StepResultSource,
        result: Long,
        trackName: String,
        track: Int,
        frame: Int,
    ): StepFrame {
        val dtype = source.frameDtype(result, track, frame) ?: malformed("frame $frame of \"$trackName\" has no dtype")
        val shape = source.frameShape(result, track, frame) ?: malformed("frame $frame of \"$trackName\" has no shape")
        val data = source.frameData(result, track, frame) ?: malformed("frame $frame of \"$trackName\" has no data")
        // Checked only for a dtype whose size is known, as the core does: an unknown one passes
        // through as raw bytes rather than failing a step that succeeded.
        elementSize(dtype)?.let { size ->
            val expected =
                if (shape.any { it < 0 }) {
                    null
                } else {
                    runCatching {
                        shape.fold(size) { total, dimension -> Math.multiplyExact(total, dimension.toLong()) }
                    }.getOrNull()
                }
            if (expected != data.size.toLong()) {
                malformed(
                    "frame $frame of \"$trackName\" holds ${data.size} bytes, but its shape " +
                        "${shape.contentToString()} of $dtype needs ${expected ?: "an overflowing size"}",
                )
            }
        }
        return StepFrame(
            encoding = source.frameEncoding(result, track, frame) ?: "raw",
            dtype = dtype,
            shape = shape,
            bytes = data,
            tag = source.frameMetadata(result, track, frame),
            captureTimeUs = source.frameCaptureTimeUs(result, track, frame),
        )
    }

    /** The core's table (`step/decode.rs`): the dtypes it size-checks, by bytes per element. */
    private fun elementSize(dtype: String): Long? =
        when (dtype) {
            "uint8", "int8", "bool" -> 1L
            "uint16", "int16", "float16" -> 2L
            "uint32", "int32", "float32" -> 4L
            "uint64", "int64", "float64" -> 8L
            else -> null
        }

    private fun malformed(what: String): Nothing =
        throw ErrorCode.toException(
            wire = "DECODE_FAILED",
            message = "The step result could not be read: $what",
            operation = "step",
        )
}
