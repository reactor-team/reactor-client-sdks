package inc.reactor.sdk.android.internal

/**
 * The `reactor_step_result_*` accessors, bound one to one in `step.cpp`.
 *
 * Only valid inside the step trampoline: `result` is the native `ReactorStepResult*`, which the
 * trampoline frees as soon as [Completions.settleStepFromNative] returns. Every accessor answers
 * null or 0 for an index out of range, as the ABI does.
 */
internal object NativeStepResults : StepResultSource {
    private external fun nativeStep(result: Long): Long

    private external fun nativeElapsed(result: Long): Double

    private external fun nativeError(result: Long): String?

    private external fun nativeFileCount(result: Long): Int

    private external fun nativeFileName(
        result: Long,
        file: Int,
    ): String?

    private external fun nativeFileContentType(
        result: Long,
        file: Int,
    ): String?

    private external fun nativeFileSize(
        result: Long,
        file: Int,
    ): Long

    private external fun nativeTrackCount(result: Long): Int

    private external fun nativeTrackName(
        result: Long,
        track: Int,
    ): String?

    private external fun nativeTrackKind(
        result: Long,
        track: Int,
    ): String?

    private external fun nativeTrackRate(
        result: Long,
        track: Int,
    ): Double

    private external fun nativeFrameCount(
        result: Long,
        track: Int,
    ): Int

    private external fun nativeFrameEncoding(
        result: Long,
        track: Int,
        frame: Int,
    ): String?

    private external fun nativeFrameDtype(
        result: Long,
        track: Int,
        frame: Int,
    ): String?

    private external fun nativeFrameShape(
        result: Long,
        track: Int,
        frame: Int,
    ): IntArray?

    private external fun nativeFrameData(
        result: Long,
        track: Int,
        frame: Int,
    ): ByteArray?

    private external fun nativeFrameMetadata(
        result: Long,
        track: Int,
        frame: Int,
    ): ByteArray?

    /** The capture time, or [Long.MIN_VALUE] when the frame carries none: a JNI long cannot be null. */
    private external fun nativeFrameCaptureTimeUs(
        result: Long,
        track: Int,
        frame: Int,
    ): Long

    override fun step(result: Long): Long = nativeStep(result)

    override fun elapsed(result: Long): Double = nativeElapsed(result)

    override fun error(result: Long): String? = nativeError(result)

    override fun fileCount(result: Long): Int = nativeFileCount(result)

    override fun fileName(
        result: Long,
        file: Int,
    ): String? = nativeFileName(result, file)

    override fun fileContentType(
        result: Long,
        file: Int,
    ): String? = nativeFileContentType(result, file)

    override fun fileSize(
        result: Long,
        file: Int,
    ): Long = nativeFileSize(result, file)

    override fun trackCount(result: Long): Int = nativeTrackCount(result)

    override fun trackName(
        result: Long,
        track: Int,
    ): String? = nativeTrackName(result, track)

    override fun trackKind(
        result: Long,
        track: Int,
    ): String? = nativeTrackKind(result, track)

    override fun trackRate(
        result: Long,
        track: Int,
    ): Double = nativeTrackRate(result, track)

    override fun frameCount(
        result: Long,
        track: Int,
    ): Int = nativeFrameCount(result, track)

    override fun frameEncoding(
        result: Long,
        track: Int,
        frame: Int,
    ): String? = nativeFrameEncoding(result, track, frame)

    override fun frameDtype(
        result: Long,
        track: Int,
        frame: Int,
    ): String? = nativeFrameDtype(result, track, frame)

    override fun frameShape(
        result: Long,
        track: Int,
        frame: Int,
    ): IntArray? = nativeFrameShape(result, track, frame)

    override fun frameData(
        result: Long,
        track: Int,
        frame: Int,
    ): ByteArray? = nativeFrameData(result, track, frame)

    override fun frameMetadata(
        result: Long,
        track: Int,
        frame: Int,
    ): ByteArray? = nativeFrameMetadata(result, track, frame)

    override fun frameCaptureTimeUs(
        result: Long,
        track: Int,
        frame: Int,
    ): Long? = nativeFrameCaptureTimeUs(result, track, frame).takeIf { it != Long.MIN_VALUE }
}
