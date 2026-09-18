package inc.reactor.sdk.internal;

import java.util.List;
import java.util.Locale;

/**
 * Which native library this JVM needs, worked out from the JVM itself.
 *
 * <p>The architecture is read from {@code os.arch}, which is the JVM's own, never the machine's.
 * That distinction is the whole point on Apple silicon: an x86_64 JVM under Rosetta reports {@code
 * x86_64} and genuinely needs the Intel library, because that is the process the library is loaded
 * into.
 */
public enum NativePlatform {
    LINUX_X86_64("linux-x86_64", "libreactor_ffi.so"),
    LINUX_AARCH64("linux-aarch64", "libreactor_ffi.so"),
    MACOS_ARM64("macos-arm64", "libreactor_ffi.dylib"),
    MACOS_X86_64("macos-x86_64", "libreactor_ffi.dylib"),
    WINDOWS_X86_64("windows-x86_64", "reactor_ffi.dll");

    private final String token;
    private final String libraryFileName;

    NativePlatform(String token, String libraryFileName) {
        this.token = token;
        this.libraryFileName = libraryFileName;
    }

    /** The name this platform goes by in artifact classifiers and packaged resource paths. */
    public String token() {
        return token;
    }

    /** The file name the native library has on this platform. */
    public String libraryFileName() {
        return libraryFileName;
    }

    /**
     * The platform this JVM is running as.
     *
     * @throws UnsupportedPlatformException when no supported platform matches, naming what was
     *     detected and what is supported — the only message a user on an unsupported platform will
     *     ever read
     */
    public static NativePlatform current() {
        String osName = System.getProperty("os.name", "");
        String osArch = System.getProperty("os.arch", "");
        return detect(osName, osArch, LibC.detect(osName));
    }

    /**
     * Which C library a Linux host runs.
     *
     * <p>{@code os.arch} says nothing about this, and the published Linux builds need glibc 2.34 or
     * newer. On Alpine and friends the library resolves, extracts, and then fails to load with an
     * error from the dynamic linker that names neither musl nor glibc — so it is worth a check that
     * can say which.
     */
    enum LibC {
        /** Not Linux, so the question does not arise. */
        NOT_LINUX,
        /** glibc, which is what the published builds need. */
        GLIBC,
        /** musl, which they will not load against. */
        MUSL;

        static LibC detect(String osName) {
            if (!osName.toLowerCase(Locale.ROOT).startsWith("linux")) {
                return NOT_LINUX;
            }
            // musl's loader is at a well-known name. Reading it is cheaper and more reliable than
            // parsing the output of `ldd --version`, which musl does not implement consistently.
            try (java.util.stream.Stream<java.nio.file.Path> entries =
                    java.nio.file.Files.list(java.nio.file.Path.of("/lib"))) {
                boolean musl = entries.map(path -> path.getFileName().toString())
                        .anyMatch(name -> name.startsWith("ld-musl-"));
                return musl ? MUSL : GLIBC;
            } catch (java.io.IOException | RuntimeException unreadable) {
                // A host whose /lib cannot be listed is not one to guess about. glibc is the
                // answer that lets the load proceed and fail with the linker's own message.
                return GLIBC;
            }
        }
    }

    /**
     * The platform an {@code os.name} / {@code os.arch} pair describes. Package-visible so the
     * tests can put pairs through it that this machine cannot produce.
     */
    static NativePlatform detect(String osName, String osArch) {
        return detect(osName, osArch, LibC.NOT_LINUX);
    }

    /**
     * The platform an {@code os.name} / {@code os.arch} / libc triple describes. Package-visible so
     * the tests can put combinations through it that this machine cannot produce.
     */
    static NativePlatform detect(String osName, String osArch, LibC libc) {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = osArch.toLowerCase(Locale.ROOT);

        boolean x86_64 = arch.equals("x86_64") || arch.equals("amd64");
        boolean aarch64 = arch.equals("aarch64") || arch.equals("arm64");

        if (os.startsWith("linux")) {
            if (libc == LibC.MUSL) {
                throw new UnsupportedPlatformException(osName, osArch, "musl");
            }
            if (x86_64) {
                return LINUX_X86_64;
            }
            if (aarch64) {
                return LINUX_AARCH64;
            }
        } else if (os.startsWith("mac") || os.startsWith("darwin")) {
            if (aarch64) {
                return MACOS_ARM64;
            }
            if (x86_64) {
                return MACOS_X86_64;
            }
        } else if (os.startsWith("windows")) {
            if (x86_64) {
                return WINDOWS_X86_64;
            }
        }
        throw new UnsupportedPlatformException(osName, osArch);
    }

    /** Every platform a published artifact carries a library for. */
    public static List<NativePlatform> supported() {
        return List.of(values());
    }
}
