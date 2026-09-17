package inc.reactor.sdk.internal;

import inc.reactor.sdk.ReactorSdk;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Finding {@code libreactor_ffi} and loading it.
 *
 * <p>Three places are tried, in this order, and the order is part of the SDK's documented contract
 * rather than an implementation detail:
 *
 * <ol>
 *   <li>{@code REACTOR_FFI_LIB}, pointing straight at a library file. An override that always wins,
 *       because someone debugging a native change needs one.
 *   <li>The packaged resource, from the natives artifact for this platform, extracted to a
 *       versioned cache directory. This is how a normal consumer gets one, and it needs nothing
 *       configured.
 *   <li>An enclosing checkout's {@code target/release}. This is what makes "run the published SDK
 *       against a local build" work.
 * </ol>
 *
 * <p>Failing to find one reports all three attempts. A library that is missing for a reason the
 * user can fix is worth more words than an {@code UnsatisfiedLinkError} is.
 */
public final class NativeLibrary {

    /** The override, checked first and documented in the README. */
    public static final String OVERRIDE_ENV = "REACTOR_FFI_LIB";

    private NativeLibrary() {}

    /**
     * Loads the native library for this platform and binds the ABI.
     *
     * @param arena the lifetime the loaded library belongs to
     * @return the bound ABI, its version already checked
     */
    public static Ffi load(Arena arena) {
        return Ffi.open(lookup(arena));
    }

    /**
     * Loads the native library without binding anything.
     *
     * @param arena the lifetime the loaded library belongs to
     * @return a lookup over the loaded library
     */
    @SuppressWarnings("restricted") // libraryLookup: loading the library is the point of this class
    public static SymbolLookup lookup(Arena arena) {
        return SymbolLookup.libraryLookup(resolve(), arena);
    }

    /**
     * Where the native library is, without loading it.
     *
     * @return the path that will be loaded
     * @throws NativeLibraryNotFoundException when none of the three places has one
     */
    public static Path resolve() {
        NativePlatform platform = NativePlatform.current();
        List<String> tried = new ArrayList<>();

        Optional<Path> override = fromOverride(tried);
        if (override.isPresent()) {
            return override.get();
        }
        Optional<Path> packaged = fromPackagedResource(platform, tried);
        if (packaged.isPresent()) {
            return packaged.get();
        }
        Optional<Path> checkout = fromEnclosingCheckout(platform, tried);
        if (checkout.isPresent()) {
            return checkout.get();
        }
        throw new NativeLibraryNotFoundException(platform, tried);
    }

    private static Optional<Path> fromOverride(List<String> tried) {
        String configured = System.getenv(OVERRIDE_ENV);
        if (configured == null || configured.isBlank()) {
            tried.add(OVERRIDE_ENV + " is not set");
            return Optional.empty();
        }
        Path path = Path.of(configured);
        if (!Files.isRegularFile(path)) {
            // Deliberately fatal rather than a fallthrough. Someone who set this variable meant to
            // use that library, and silently using a different one is how a native change gets
            // debugged against the wrong binary for an afternoon.
            throw new NativeLibraryNotFoundException(
                    OVERRIDE_ENV + " points at " + path.toAbsolutePath() + ", which is not a file.");
        }
        return Optional.of(path.toAbsolutePath());
    }

    private static Optional<Path> fromPackagedResource(NativePlatform platform, List<String> tried) {
        String resource = "/reactor-native/" + platform.token() + "/" + platform.libraryFileName();
        try (InputStream in = NativeLibrary.class.getResourceAsStream(resource)) {
            if (in == null) {
                tried.add("no packaged resource at " + resource + " (the natives artifact for " + platform.token()
                        + " is not on the classpath)");
                return Optional.empty();
            }
            Path cached = cacheDirectory(platform).resolve(platform.libraryFileName());
            if (!Files.isRegularFile(cached)) {
                Files.createDirectories(cached.getParent());
                // Written beside the target and moved into place, so two JVMs starting at once
                // cannot have one of them load a half-written file.
                Path partial = Files.createTempFile(cached.getParent(), "partial-", ".tmp");
                Files.copy(in, partial, StandardCopyOption.REPLACE_EXISTING);
                Files.move(partial, cached, StandardCopyOption.REPLACE_EXISTING);
            }
            return Optional.of(cached);
        } catch (IOException e) {
            throw new NativeLibraryNotFoundException(
                    "the packaged native library for " + platform.token() + " could not be unpacked", e);
        }
    }

    private static Optional<Path> fromEnclosingCheckout(NativePlatform platform, List<String> tried) {
        Path directory = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve("target").resolve("release").resolve(platform.libraryFileName());
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
            directory = directory.getParent();
        }
        tried.add("no target/release/" + platform.libraryFileName() + " in any directory enclosing "
                + System.getProperty("user.dir", "."));
        return Optional.empty();
    }

    private static Path cacheDirectory(NativePlatform platform) {
        // Versioned, so two applications on one machine running different SDK versions do not share
        // one extracted library — and so an upgrade does not keep loading the old one.
        return Path.of(System.getProperty("java.io.tmpdir"))
                .resolve("reactor-sdk-natives")
                .resolve(ReactorSdk.version())
                .resolve(platform.token());
    }
}
