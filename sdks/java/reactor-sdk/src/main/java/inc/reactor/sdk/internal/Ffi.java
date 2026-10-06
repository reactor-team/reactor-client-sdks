package inc.reactor.sdk.internal;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.EnumMap;
import java.util.Map;

/**
 * Every C function this SDK calls, and nothing else.
 *
 * <p>No policy and no object model live here: this is the one place that knows what the ABI looks
 * like, so there is one place to check it against {@code crates/reactor-ffi/include/reactor_ffi.h}.
 * Read the header for what each function promises about threading, nullability and string
 * ownership — it is written for a reader, and paraphrasing it here would only give it a second
 * version to drift from.
 *
 * <p>The exported surface exists in several hand-copied places and {@code
 * scripts/check-abi-parity.py} compares them. For every other binding it can only compare function
 * <i>names</i>, because a C++ or ctypes declaration is not something a script can take apart. A
 * {@link FunctionDescriptor} is a data structure, so for this binding the script also counts
 * arguments — which closes the failure mode the name check cannot see: a function that gained a
 * parameter still links, still resolves, and corrupts the stack at the call.
 *
 * <p>{@code reactor_create} is deliberately absent, and the parity script enforces its absence. It
 * takes its audio device mode from an environment variable; this SDK uses {@link
 * Symbol#CREATE_WITH_ADM} so that nothing in the environment can put a live microphone on the wire
 * because a model happened to declare a sendonly audio track.
 */
public final class Ffi {

    /**
     * The ABI this binding was written against, matching {@code REACTOR_ABI_VERSION} in the header.
     *
     * <p>Checked at load, because the alternative is worse than a version error: a library older
     * than the crates still links and still resolves every symbol, and only misbehaves at the call
     * — which looks like a hang, or like an operation silently doing nothing.
     */
    public static final int ABI_VERSION = 3;

    /**
     * {@code size_t}, as every platform this SDK publishes for defines it. All five are 64-bit; a
     * 32-bit JVM is refused by {@link NativePlatform} before anything gets this far.
     */
    private static final MemoryLayout SIZE_T = JAVA_LONG;

    /** The C functions, with the signature each one is called through. */
    public enum Symbol {
        ABI_VERSION_OF("reactor_abi_version", FunctionDescriptor.of(JAVA_INT)),
        FETCH_JWT(
                "reactor_fetch_jwt", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS)),
        CREATE_WITH_ADM(
                "reactor_create_with_adm",
                FunctionDescriptor.of(
                        ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS)),
        DESTROY("reactor_destroy", FunctionDescriptor.of(JAVA_INT, ADDRESS)),
        CONNECT("reactor_connect", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS)),
        DISCONNECT("reactor_disconnect", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS)),
        RECONNECT("reactor_reconnect", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS)),
        PUBLISH_TRACK("reactor_publish_track", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS)),
        PAUSE_TRACK("reactor_pause_track", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS)),
        RESUME_TRACK("reactor_resume_track", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS)),
        SET_BITRATE(
                "reactor_set_bitrate",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS)),
        SET_TRACK_BITRATE(
                "reactor_set_track_bitrate",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS)),
        REQUEST_CLIP("reactor_request_clip", FunctionDescriptor.ofVoid(ADDRESS, JAVA_DOUBLE, ADDRESS, ADDRESS)),
        REQUEST_RECORDING("reactor_request_recording", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS)),
        REQUEST_SCHEMA("reactor_request_schema", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS)),
        GET_STATS("reactor_get_stats", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS)),
        SEND_COMMAND(
                "reactor_send_command",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS)),
        UPLOAD_FILE("reactor_upload_file", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS)),
        UPLOAD_BYTES(
                "reactor_upload_bytes",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, SIZE_T, ADDRESS, ADDRESS, ADDRESS, ADDRESS)),
        DOWNLOAD_CLIP(
                "reactor_download_clip",
                FunctionDescriptor.ofVoid(
                        ADDRESS,
                        ADDRESS,
                        ADDRESS,
                        ADDRESS,
                        JAVA_DOUBLE,
                        JAVA_DOUBLE,
                        JAVA_INT,
                        ADDRESS,
                        ADDRESS,
                        ADDRESS)),
        UNPUBLISH_TRACK("reactor_unpublish_track", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS)),
        STATUS("reactor_status", FunctionDescriptor.of(ADDRESS, ADDRESS)),
        SESSION_ID("reactor_session_id", FunctionDescriptor.of(ADDRESS, ADDRESS)),
        TRACKS("reactor_tracks", FunctionDescriptor.of(ADDRESS, ADDRESS)),
        PAUSED_TRACKS("reactor_paused_tracks", FunctionDescriptor.of(ADDRESS, ADDRESS)),
        FREE_STRING("reactor_free_string", FunctionDescriptor.ofVoid(ADDRESS)),
        PUSH_VIDEO_FRAME(
                "reactor_push_video_frame", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT)),
        PUSH_VIDEO_FRAME_WITH_METADATA(
                "reactor_push_video_frame_with_metadata",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT)),
        TIME_MICROS("reactor_time_micros", FunctionDescriptor.of(JAVA_LONG)),
        PUSH_VIDEO_FRAME_WITH_METADATA_AT(
                "reactor_push_video_frame_with_metadata_at",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG)),
        PUSH_AUDIO_FRAME(
                "reactor_push_audio_frame",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));

        private final String cName;
        private final FunctionDescriptor descriptor;

        Symbol(String cName, FunctionDescriptor descriptor) {
            this.cName = cName;
            this.descriptor = descriptor;
        }

        /** The symbol's name in the native library. */
        public String cName() {
            return cName;
        }

        /** The signature it is called through. */
        public FunctionDescriptor descriptor() {
            return descriptor;
        }
    }

    /**
     * The signatures the FFI calls back through. Every one returns void, and {@link Upcalls} is the
     * only thing that turns them into pointers.
     */
    public static final class Callbacks {

        /** {@code (const char *status, void *userdata)} */
        public static final FunctionDescriptor ON_STATUS = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);

        /** {@code (const char *error_json, void *userdata)} */
        public static final FunctionDescriptor ON_ERROR = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);

        /** {@code (const char *msg_json, void *userdata)} */
        public static final FunctionDescriptor ON_MESSAGE = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);

        /** {@code (const char *msg_json, void *userdata)} */
        public static final FunctionDescriptor ON_RUNTIME_MESSAGE = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);

        /** {@code (const char *name, const char *mid_or_null, void *userdata)} */
        public static final FunctionDescriptor ON_TRACK = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS);

        /** {@code (const char *caps_json, void *userdata)} */
        public static final FunctionDescriptor ON_CAPABILITIES = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);

        /** {@code (const char *session_id_or_null, void *userdata)} */
        public static final FunctionDescriptor ON_SESSION_ID = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);

        /**
         * {@code (const char *track_name, const uint8_t *data, uint32_t width, uint32_t height,
         * uint64_t frame_id, uint64_t timestamp_us, const uint8_t *user_data, uint32_t
         * user_data_len, void *userdata)}
         */
        public static final FunctionDescriptor ON_FRAME = FunctionDescriptor.ofVoid(
                ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG, ADDRESS, JAVA_INT, ADDRESS);

        /**
         * {@code (const char *track_name, const int16_t *samples, uint32_t num_samples, uint32_t
         * sample_rate, uint32_t channels, void *userdata)}
         */
        public static final FunctionDescriptor ON_AUDIO =
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS);

        /** {@code (int ok, const char *result_json, const char *error_json, void *userdata)} */
        public static final FunctionDescriptor COMPLETION =
                FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);

        /** {@code (uint32_t done, uint32_t total, void *userdata)} */
        public static final FunctionDescriptor PROGRESS = FunctionDescriptor.ofVoid(JAVA_INT, JAVA_INT, ADDRESS);

        /**
         * The {@code ReactorCallbacks} struct, in the header's order. Order is the contract here:
         * these are positional slots, so a field out of place hands the FFI one callback where it
         * expects another and nothing complains until it is called.
         */
        public static final StructLayout LAYOUT = MemoryLayout.structLayout(
                ADDRESS.withName("on_status"),
                ADDRESS.withName("on_error"),
                ADDRESS.withName("on_message"),
                ADDRESS.withName("on_runtime_message"),
                ADDRESS.withName("on_track"),
                ADDRESS.withName("on_capabilities"),
                ADDRESS.withName("on_session_id"),
                ADDRESS.withName("on_frame"),
                ADDRESS.withName("on_audio"),
                ADDRESS.withName("userdata"));

        private Callbacks() {}
    }

    private final Map<Symbol, MethodHandle> handles;

    private Ffi(Map<Symbol, MethodHandle> handles) {
        this.handles = handles;
    }

    /**
     * Binds every symbol in {@code lookup} and checks the library's ABI version before returning.
     *
     * <p>Both halves fail loudly and early on purpose. A missing symbol names itself here rather
     * than at the first call of a feature nobody exercised; a version mismatch is caught before any
     * handle exists, because past that point the symptom is a hang rather than an error.
     *
     * @param lookup where the symbols come from — a loaded library, or a fake in a test
     * @return the bound ABI
     */
    @SuppressWarnings("restricted") // downcallHandle: the descriptors above are the safety argument
    public static Ffi open(SymbolLookup lookup) {
        Linker linker = nativeLinker();
        Map<Symbol, MethodHandle> bound = new EnumMap<>(Symbol.class);
        for (Symbol symbol : Symbol.values()) {
            MemorySegment address = lookup.find(symbol.cName())
                    .orElseThrow(() ->
                            new AbiMismatchException("the native library exports no symbol named " + symbol.cName()
                                    + ". The library on disk is older than the crates it was built"
                                    + " from — rebuild it with: cargo build -p reactor-ffi --release"));
            bound.put(symbol, linker.downcallHandle(address, symbol.descriptor()));
        }
        Ffi ffi = new Ffi(Map.copyOf(bound));
        ffi.checkAbiVersion();
        return ffi;
    }

    /**
     * The handle for a symbol.
     *
     * @param symbol which function
     * @return its method handle, never {@code null}
     */
    public MethodHandle handle(Symbol symbol) {
        return handles.get(symbol);
    }

    private void checkAbiVersion() {
        int actual;
        try {
            actual = (int) handle(Symbol.ABI_VERSION_OF).invokeExact();
        } catch (Throwable t) {
            throw new AbiMismatchException("the native library's reactor_abi_version() could not be called", t);
        }
        if (actual != ABI_VERSION) {
            throw new AbiMismatchException("this SDK speaks reactor-ffi ABI version " + ABI_VERSION
                    + ", the native library speaks " + actual
                    + ". They are not compatible, and a mismatch this SDK did not catch here would"
                    + " look like a hang rather than a version error. Rebuild the library with:"
                    + " cargo build -p reactor-ffi --release");
        }
    }

    @SuppressWarnings("restricted") // nativeLinker: binding the ABI is what this class is for
    private static Linker nativeLinker() {
        return Linker.nativeLinker();
    }
}
