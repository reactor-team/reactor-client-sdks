package inc.reactor.sdk.internal;

import java.util.List;

/**
 * Thrown when no native library could be found or unpacked for this platform.
 *
 * <p>Carries every place that was looked at, because the three-step resolution order is only useful
 * to someone who can see which step they are failing at.
 */
public final class NativeLibraryNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    NativeLibraryNotFoundException(String message) {
        super(message);
    }

    NativeLibraryNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }

    NativeLibraryNotFoundException(NativePlatform platform, List<String> tried) {
        super("No Reactor native library for " + platform.token() + ". Looked in three places:"
                + tried.stream().reduce("", (all, one) -> all + "\n  - " + one)
                + "\nAdd the natives artifact for this platform to the classpath, or point "
                + NativeLibrary.OVERRIDE_ENV
                + " at a library you built with: cargo build -p reactor-ffi --release");
    }
}
