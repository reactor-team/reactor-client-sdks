package inc.reactor.sdk.internal;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

/**
 * Reading C strings, with ownership in the method name.
 *
 * <p>The FFI hands strings back under three different contracts and the header states which for
 * every function. Getting it wrong is not a compile error and usually not a crash either — not
 * immediately:
 *
 * <ul>
 *   <li><b>static</b> — {@code reactor_status} returns a literal. Freeing it corrupts the heap.
 *   <li><b>owned</b> — {@code reactor_session_id}, {@code reactor_tracks}, {@code
 *       reactor_paused_tracks} and the error object from {@code reactor_unpublish_track} are the
 *       caller's to free. Not freeing them leaks on every property read.
 *   <li><b>borrowed</b> — every string handed <i>to</i> a callback. The FFI frees it when the
 *       callback returns, so freeing it here is a double free and keeping the pointer is a
 *       use-after-free.
 * </ul>
 *
 * <p>Three names, one per contract, so a call site says which one it believes it is looking at and
 * a reviewer can check that against the header.
 */
public final class NativeStrings {

    /**
     * How far {@link #read} will scan for a terminating NUL.
     *
     * <p>A returned pointer carries no length, so reading it means reinterpreting an unbounded
     * region. Bounding it means a library that somehow hands back an unterminated string scans 16
     * MiB and fails, instead of walking the address space until it hits something unmapped.
     */
    private static final long MAX_STRING_BYTES = 16L * 1024 * 1024;

    private NativeStrings() {}

    /**
     * Reads a string the FFI owns and keeps — a static literal. Never freed.
     *
     * @param segment the returned pointer; must not be {@code NULL}
     * @return the string
     */
    public static String staticRef(MemorySegment segment) {
        if (segment.equals(MemorySegment.NULL)) {
            throw new IllegalArgumentException(
                    "a static string from the FFI is documented as never NULL, but this one was");
        }
        return read(segment);
    }

    /**
     * Reads a string the FFI lends for the duration of a callback. Never freed here.
     *
     * <p>The caller must have copied whatever it keeps before returning from the callback: this
     * method's result is a Java {@code String} and therefore already a copy, which is the point.
     *
     * @param segment the pointer handed to the callback, possibly {@code NULL}
     * @return the string, or {@code null} when the pointer was {@code NULL}
     */
    public static String borrow(MemorySegment segment) {
        return segment == null || segment.equals(MemorySegment.NULL) ? null : read(segment);
    }

    /**
     * Reads a string the FFI allocated and handed over, and frees it.
     *
     * <p>Frees even when reading throws: the caller cannot free it afterwards, because it no longer
     * has the pointer.
     *
     * @param segment the returned pointer, possibly {@code NULL}
     * @param freeString a handle to {@code reactor_free_string}
     * @return the string, or {@code null} when the pointer was {@code NULL}
     */
    public static String takeOwned(MemorySegment segment, MethodHandle freeString) {
        if (segment == null || segment.equals(MemorySegment.NULL)) {
            return null;
        }
        try {
            return read(segment);
        } finally {
            free(segment, freeString);
        }
    }

    private static void free(MemorySegment segment, MethodHandle freeString) {
        try {
            freeString.invokeExact(segment);
        } catch (Throwable t) {
            throw sneak(t);
        }
    }

    @SuppressWarnings("restricted") // reinterpret: the FFI's contract is a NUL-terminated string
    private static String read(MemorySegment segment) {
        return segment.reinterpret(MAX_STRING_BYTES).getString(0);
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneak(Throwable t) throws T {
        throw (T) t;
    }
}
