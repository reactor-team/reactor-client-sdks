package inc.reactor.sdk.internal;

import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Thrown when this JVM is not one of the platforms a native library is published for.
 *
 * <p>Deliberately verbose. Resolution failing is the one thing a user on an unsupported platform
 * ever sees of this SDK, and "no such library" tells them nothing about whether they are holding it
 * wrong or whether it was never going to work.
 */
public final class UnsupportedPlatformException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    UnsupportedPlatformException(String osName, String osArch) {
        super(message(osName, osArch));
    }

    UnsupportedPlatformException(String osName, String osArch, String libc) {
        super("The Reactor SDK's Linux builds need glibc 2.34 or newer, and this host runs " + libc
                + " (os.name=\"" + osName + "\", os.arch=\"" + osArch + "\")."
                + " Nothing published here will load against it. Build the library yourself and point "
                + NativeLibrary.OVERRIDE_ENV + " at it, or run on a glibc host.");
    }

    private static String message(String osName, String osArch) {
        String supported =
                Stream.of(NativePlatform.values()).map(NativePlatform::token).collect(Collectors.joining(", "));
        return "The Reactor SDK publishes no native library for this JVM: os.name=\"%s\", os.arch=\"%s\"."
                        .formatted(osName, osArch)
                + " Supported platforms are: %s.".formatted(supported)
                + " Note that os.arch is the JVM's architecture, not the machine's — a 32-bit or"
                + " emulated JVM reports its own. Point REACTOR_FFI_LIB at a library you built"
                + " yourself to use one anyway.";
    }
}
