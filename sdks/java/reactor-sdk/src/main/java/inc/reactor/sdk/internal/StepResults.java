package inc.reactor.sdk.internal;

import inc.reactor.sdk.ErrorCode;
import inc.reactor.sdk.Media;
import inc.reactor.sdk.ReactorException;
import inc.reactor.sdk.StepFile;
import inc.reactor.sdk.StepFrame;
import inc.reactor.sdk.StepResult;
import inc.reactor.sdk.StepTrackOutput;
import inc.reactor.sdk.TrackKind;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Copies a native {@code ReactorStepResult} onto the heap.
 *
 * <p>Read once, in place, through the {@code reactor_step_result_*} accessors, and never freed here:
 * {@link Completions} releases the result as soon as this returns, whether it returned or threw.
 * Nothing this builds points into the result, which is what makes releasing it straight away safe.
 */
final class StepResults {

    /**
     * Element sizes of the dtypes a RAW frame can declare.
     *
     * <p>A frame whose byte count disagrees with its shape is refused when the result arrives,
     * rather than when somebody first indexes into it — by then the step has already been reported
     * as a success. A dtype not listed here passes through unchecked: the transport is not the
     * place to decide which element types a model may produce.
     */
    private static final Map<String, Integer> DTYPE_SIZES = Map.of(
            "uint8", 1,
            "int8", 1,
            "uint16", 2,
            "int16", 2,
            "uint32", 4,
            "int32", 4,
            "float16", 2,
            "float32", 4,
            "float64", 8);

    private StepResults() {}

    /**
     * @param ffi the bound ABI
     * @param result a live result, not yet freed
     * @return the copy
     * @throws ReactorException ({@code DECODE_FAILED}) when the result describes something
     *     inconsistent — a frame whose bytes do not match its shape, or a track of a kind this SDK
     *     does not know
     */
    static StepResult read(Ffi ffi, MemorySegment result) {
        try {
            return readOrThrow(ffi, result);
        } catch (ReactorException | IllegalStateException refused) {
            throw refused;
        } catch (Throwable t) {
            throw new IllegalStateException("the step result could not be read: " + t.getMessage(), t);
        }
    }

    private static StepResult readOrThrow(Ffi ffi, MemorySegment result) throws Throwable {
        long step = (long) ffi.handle(Ffi.Symbol.STEP_RESULT_STEP).invokeExact(result);
        int trackCount = (int) ffi.handle(Ffi.Symbol.STEP_RESULT_TRACK_COUNT).invokeExact(result);
        List<StepTrackOutput> tracks = new ArrayList<>(Math.max(trackCount, 0));
        for (int t = 0; t < trackCount; t++) {
            MemorySegment nameText = (MemorySegment)
                    ffi.handle(Ffi.Symbol.STEP_RESULT_TRACK_NAME).invokeExact(result, t);
            MemorySegment kindText = (MemorySegment)
                    ffi.handle(Ffi.Symbol.STEP_RESULT_TRACK_KIND).invokeExact(result, t);
            String name = orEmpty(NativeStrings.borrow(nameText));
            String kindName = orEmpty(NativeStrings.borrow(kindText));
            TrackKind kind = TrackKind.of(kindName)
                    .orElseThrow(() -> decodeFailed("output track \"" + name + "\" has a kind this SDK does not"
                            + " know: \"" + kindName + "\""));
            double rate = (double) ffi.handle(Ffi.Symbol.STEP_RESULT_TRACK_RATE).invokeExact(result, t);
            int frameCount =
                    (int) ffi.handle(Ffi.Symbol.STEP_RESULT_FRAME_COUNT).invokeExact(result, t);
            List<StepFrame> frames = new ArrayList<>(Math.max(frameCount, 0));
            for (int f = 0; f < frameCount; f++) {
                frames.add(readFrame(ffi, result, name, t, f));
            }
            tracks.add(new StepTrackOutput(name, kind, rate, frames));
        }
        double elapsed = (double) ffi.handle(Ffi.Symbol.STEP_RESULT_ELAPSED).invokeExact(result);
        int fileCount = (int) ffi.handle(Ffi.Symbol.STEP_RESULT_FILE_COUNT).invokeExact(result);
        List<StepFile> files = new ArrayList<>(Math.max(fileCount, 0));
        for (int f = 0; f < fileCount; f++) {
            MemorySegment nameText =
                    (MemorySegment) ffi.handle(Ffi.Symbol.STEP_RESULT_FILE_NAME).invokeExact(result, f);
            MemorySegment typeText = (MemorySegment)
                    ffi.handle(Ffi.Symbol.STEP_RESULT_FILE_CONTENT_TYPE).invokeExact(result, f);
            long size = (long) ffi.handle(Ffi.Symbol.STEP_RESULT_FILE_SIZE).invokeExact(result, f);
            files.add(new StepFile(
                    orEmpty(NativeStrings.borrow(nameText)), orEmpty(NativeStrings.borrow(typeText)), size));
        }
        MemorySegment errorText =
                (MemorySegment) ffi.handle(Ffi.Symbol.STEP_RESULT_ERROR).invokeExact(result);
        Optional<String> error = Optional.ofNullable(NativeStrings.borrow(errorText));
        return new StepResult(step, tracks, elapsed, files, error);
    }

    @SuppressWarnings("restricted") // reinterpret: bounded by the lengths the accessors report
    private static StepFrame readFrame(Ffi ffi, MemorySegment result, String track, int t, int f) throws Throwable {
        MemorySegment encodingText = (MemorySegment)
                ffi.handle(Ffi.Symbol.STEP_RESULT_FRAME_ENCODING).invokeExact(result, t, f);
        MemorySegment dtypeText =
                (MemorySegment) ffi.handle(Ffi.Symbol.STEP_RESULT_FRAME_DTYPE).invokeExact(result, t, f);
        String encoding = orEmpty(NativeStrings.borrow(encodingText));
        String dtype = orEmpty(NativeStrings.borrow(dtypeText));

        // Out-parameters, confined to this read: the accessors write them during the call and the
        // values are copied into Java locals before the arena goes.
        try (Arena out = Arena.ofConfined()) {
            MemorySegment ndim = out.allocate(ValueLayout.JAVA_INT);
            MemorySegment shapePointer = (MemorySegment)
                    ffi.handle(Ffi.Symbol.STEP_RESULT_FRAME_SHAPE).invokeExact(result, t, f, ndim);
            int dimensions = ndim.get(ValueLayout.JAVA_INT, 0);
            int[] shape = new int[0];
            if (!shapePointer.equals(MemorySegment.NULL) && dimensions > 0) {
                int[] unsigned = shapePointer
                        .reinterpret((long) dimensions * Integer.BYTES)
                        .toArray(ValueLayout.JAVA_INT);
                for (int dimension : unsigned) {
                    if (dimension < 0) {
                        throw decodeFailed("frame " + f + " of \"" + track + "\" has a dimension of "
                                + Integer.toUnsignedString(dimension) + ", past what a Java array can index");
                    }
                }
                shape = unsigned;
            }

            MemorySegment length = out.allocate(ValueLayout.JAVA_LONG);
            MemorySegment dataPointer = (MemorySegment)
                    ffi.handle(Ffi.Symbol.STEP_RESULT_FRAME_DATA).invokeExact(result, t, f, length);
            long byteCount = length.get(ValueLayout.JAVA_LONG, 0);
            if (byteCount < 0 || byteCount > Integer.MAX_VALUE - 8) {
                throw decodeFailed("frame " + f + " of \"" + track + "\" is " + Long.toUnsignedString(byteCount)
                        + " bytes, past what a Java array can hold");
            }
            byte[] data = dataPointer.equals(MemorySegment.NULL) || byteCount == 0
                    ? new byte[0]
                    : dataPointer.reinterpret(byteCount).toArray(ValueLayout.JAVA_BYTE);
            checkSize(track, f, dtype, shape, data.length);

            MemorySegment metadataLength = out.allocate(ValueLayout.JAVA_LONG);
            MemorySegment metadataPointer = (MemorySegment)
                    ffi.handle(Ffi.Symbol.STEP_RESULT_FRAME_METADATA).invokeExact(result, t, f, metadataLength);
            long metadataBytes = metadataLength.get(ValueLayout.JAVA_LONG, 0);
            // NULL is "attached nothing"; non-NULL with length 0 is an attached, empty tag. The
            // header keeps the two apart, so this does too.
            byte[] metadata = metadataPointer.equals(MemorySegment.NULL)
                    ? null
                    : metadataBytes == 0
                            ? new byte[0]
                            : metadataPointer.reinterpret(metadataBytes).toArray(ValueLayout.JAVA_BYTE);

            MemorySegment captured = out.allocate(ValueLayout.JAVA_LONG);
            int hasCaptureTime = (int)
                    ffi.handle(Ffi.Symbol.STEP_RESULT_FRAME_CAPTURE_TIME_US).invokeExact(result, t, f, captured);
            Long captureTimeUs = hasCaptureTime != 0 ? captured.get(ValueLayout.JAVA_LONG, 0) : null;

            return Media.stepFrame(encoding, dtype, shape, data, metadata, captureTimeUs);
        }
    }

    private static void checkSize(String track, int frame, String dtype, int[] shape, int actual) {
        Integer elementSize = DTYPE_SIZES.get(dtype);
        if (elementSize == null) {
            return;
        }
        long expected = elementSize;
        for (int dimension : shape) {
            expected = Math.multiplyExact(expected, (long) dimension);
        }
        if (expected != actual) {
            throw decodeFailed("frame " + frame + " of \"" + track + "\" has shape "
                    + java.util.Arrays.toString(shape) + " and dtype " + dtype + ", which needs " + expected
                    + " bytes, but carries " + actual);
        }
    }

    private static String orEmpty(@Nullable String text) {
        return text == null ? "" : text;
    }

    private static ReactorException decodeFailed(String message) {
        return ReactorException.of(ErrorCode.DECODE_FAILED.code(), message, null, "step", null);
    }
}
