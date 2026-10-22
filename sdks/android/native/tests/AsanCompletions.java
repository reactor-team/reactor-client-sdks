/**
 * A stand-in for Kotlin's Completions, for the native side of the completion path.
 *
 * The bridge resolves `settleFromNative` by name and signature, so this is as good a target as the
 * real registry — and the real one is Kotlin, which would drag the Android toolchain into a C++
 * sanitizer build. What is under test here is the *native* half: attaching a thread, copying two
 * borrowed strings before the FFI frees them, and calling a static method. The registry's own
 * logic — settle once, decode before claiming, cancellation — has eleven JVM unit tests, and the
 * seam between the two is exercised on a device.
 */
public class AsanCompletions {
    public static volatile long lastTicket = -1;
    public static volatile boolean lastOk = false;
    public static volatile String lastResult = null;
    public static volatile String lastError = null;
    public static volatile int settleCount = 0;

    public static void settleFromNative(long ticket, boolean ok, String resultJson, String errorJson) {
        lastTicket = ticket;
        lastOk = ok;
        lastResult = resultJson;
        lastError = errorJson;
        settleCount++;
    }

    public static volatile long lastStepTicket = -1;
    public static volatile boolean lastStepOk = false;
    public static volatile long lastStepResult = 0;
    public static volatile String lastStepError = null;
    public static volatile int stepSettleCount = 0;
    /** Set to make the next step settle throw, as a Kotlin reader that fails would. */
    public static volatile boolean throwOnStep = false;

    public static void settleStepFromNative(long ticket, boolean ok, long result, String errorJson) {
        lastStepTicket = ticket;
        lastStepOk = ok;
        lastStepResult = result;
        lastStepError = errorJson;
        stepSettleCount++;
        if (throwOnStep) {
            throwOnStep = false;
            throw new IllegalStateException("a step reader that fails");
        }
    }
}
