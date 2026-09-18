package inc.reactor.sdk.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Which library this JVM needs, including the pairs this machine cannot produce. */
final class NativePlatformTest {

    @ParameterizedTest(name = "{0} / {1} -> {2}")
    @CsvSource({
        "Linux, amd64, linux-x86_64",
        "Linux, x86_64, linux-x86_64",
        "Linux, aarch64, linux-aarch64",
        "Linux, arm64, linux-aarch64",
        "Mac OS X, aarch64, macos-arm64",
        "Mac OS X, arm64, macos-arm64",
        // Rosetta: an x86_64 JVM on Apple silicon. It reports the JVM's architecture, and the
        // Intel library is genuinely the right answer — that is the process it loads into.
        "Mac OS X, x86_64, macos-x86_64",
        "Windows 11, amd64, windows-x86_64",
        "Windows Server 2022, x86_64, windows-x86_64",
    })
    void mapsAJvmToItsLibrary(String osName, String osArch, String expected) {
        assertEquals(expected, NativePlatform.detect(osName, osArch).token());
    }

    @ParameterizedTest(name = "{0} / {1} is unsupported")
    @CsvSource({
        // A 32-bit JVM on a 64-bit machine. os.arch is the JVM's, so this is a real refusal
        // rather than a mis-detection.
        "Linux, i386",
        "Windows 11, x86",
        // Windows on ARM resolves to nothing rather than falling through to the x86_64 library
        // and letting the emulation layer decide.
        "Windows 11, aarch64",
        "FreeBSD, amd64",
        "SunOS, sparcv9",
    })
    void refusesAJvmWithNoPublishedLibrary(String osName, String osArch) {
        UnsupportedPlatformException thrown =
                assertThrows(UnsupportedPlatformException.class, () -> NativePlatform.detect(osName, osArch));

        // This message is the only thing a user on an unsupported platform ever reads, so what it
        // contains is part of the contract, not a formatting detail.
        assertTrue(thrown.getMessage().contains(osName), "must name what was detected: " + thrown.getMessage());
        assertTrue(thrown.getMessage().contains(osArch), "must name what was detected: " + thrown.getMessage());
        for (NativePlatform platform : NativePlatform.values()) {
            assertTrue(
                    thrown.getMessage().contains(platform.token()),
                    "must list " + platform.token() + ": " + thrown.getMessage());
        }
        assertTrue(
                thrown.getMessage().contains(NativeLibrary.OVERRIDE_ENV),
                "must point at the override: " + thrown.getMessage());
    }

    @Test
    @DisplayName("this JVM is one of the supported platforms")
    void thisJvmIsSupported() {
        assertTrue(NativePlatform.supported().contains(NativePlatform.current()));
    }

    @Test
    @DisplayName("every platform names the library file it carries")
    void everyPlatformNamesItsLibraryFile() {
        for (NativePlatform platform : NativePlatform.values()) {
            assertTrue(
                    platform.libraryFileName().contains("reactor_ffi"),
                    platform + " does not name a reactor_ffi library");
        }
    }
}
