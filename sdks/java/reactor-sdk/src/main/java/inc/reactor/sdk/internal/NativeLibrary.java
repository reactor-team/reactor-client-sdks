package inc.reactor.sdk.internal;

import inc.reactor.sdk.ReactorSdk;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
        List<String> tried = new ArrayList<>();

        // Before the platform is resolved, not after. REACTOR_FFI_LIB is documented as the first
        // place looked and UnsupportedPlatformException tells the reader to use it — but
        // NativePlatform.current() threw first, so the one escape hatch offered to somebody on an
        // unsupported OS was unreachable from the moment they needed it.
        Optional<Path> override = fromOverride(tried);
        if (override.isPresent()) {
            return override.get();
        }

        NativePlatform platform = NativePlatform.current();
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
        String resource = "reactor-native/" + platform.token() + "/" + platform.libraryFileName();
        // Through the class loader, not through this class. `Class.getResourceAsStream` on a type in
        // a named module searches that module and no other — and this resource is in
        // reactor-sdk-natives, a different one. On the class path it happened to work, which is why
        // it went unnoticed; on the module path it returned null and the SDK reported the artifact
        // missing while it sat resolved on the module path.
        //
        // The class loader finds it because "reactor-native" is not a valid package name, so JPMS
        // does not encapsulate it. That is why the natives jar puts it there, and this is the half
        // that makes the choice pay off.
        ClassLoader loader = NativeLibrary.class.getClassLoader();
        try (InputStream in = loader == null
                ? ClassLoader.getSystemResourceAsStream(resource)
                : loader.getResourceAsStream(resource)) {
            if (in == null) {
                tried.add("no packaged resource at /" + resource + " (the natives artifact for " + platform.token()
                        + " is not on the classpath)");
                return Optional.empty();
            }
            Path cached = cacheDirectory(platform).resolve(platform.libraryFileName());
            createPrivateDirectory(cached.getParent());
            if (!Files.isRegularFile(cached)) {
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

    /**
     * Where an extracted library is kept between runs.
     *
     * <p>Under the user's own cache directory, not the shared temporary one. The temporary
     * directory is world-writable on every Unix, so a predictable path inside it —
     * {@code /tmp/reactor-sdk-natives/<version>/<platform>/libreactor_ffi.so} — is one any other
     * account on the machine can create first. The extraction below skips a file that is already
     * there, so that account chooses which shared object this JVM loads, and loading it is the
     * whole purpose of this class. A sticky bit does not help: it stops one user deleting another's
     * files, not creating their own.
     *
     * <p>Versioned, so two applications running different SDK versions do not share one extracted
     * library and an upgrade does not keep loading the old one.
     */
    private static Path cacheDirectory(NativePlatform platform) {
        return userCacheRoot()
                .resolve("reactor-sdk-natives")
                .resolve(ReactorSdk.version())
                .resolve(platform.token());
    }

    /** A directory this user owns. Falls back to a per-user name under the temporary directory. */
    private static Path userCacheRoot() {
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && !local.isBlank()) {
            return Path.of(local);
        }
        String xdg = System.getenv("XDG_CACHE_HOME");
        if (xdg != null && !xdg.isBlank()) {
            return Path.of(xdg);
        }
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank() && Files.isDirectory(Path.of(home))) {
            return Path.of(home).resolve(".cache");
        }
        // Last resort, and still not a shared name: a directory another account cannot have created
        // under this user's own name without already being this user.
        return Path.of(System.getProperty("java.io.tmpdir"))
                .resolve("reactor-sdk-" + System.getProperty("user.name", "unknown"));
    }

    /**
     * Creates the cache directory such that only this user can write into it.
     *
     * <p>The permissions are the point, not the directory. Where POSIX permissions exist they are
     * set at creation — not afterwards, which would leave a window in which the directory is open —
     * and an existing directory that anyone else can write to is refused rather than used.
     */
    private static void createPrivateDirectory(Path directory) throws IOException {
        if (Files.isDirectory(directory)) {
            refuseIfOthersCanWrite(directory);
            return;
        }
        Path parent = directory.getParent();
        if (parent != null) {
            createPrivateDirectory(parent);
        }
        try {
            if (Files.getFileStore(directory.getRoot() == null ? Path.of(".") : directory.getRoot())
                    .supportsFileAttributeView(PosixFileAttributeView.class)) {
                Files.createDirectory(
                        directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                return;
            }
        } catch (UnsupportedOperationException | IOException notPosix) {
            // Windows, or a file store that cannot answer. Fall through to a plain create: the path
            // is already under a per-user root there.
        }
        Files.createDirectory(directory);
    }

    private static void refuseIfOthersCanWrite(Path directory) throws IOException {
        Set<PosixFilePermission> permissions;
        try {
            permissions = Files.getPosixFilePermissions(directory);
        } catch (UnsupportedOperationException notPosix) {
            return;
        }
        if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
            throw new IOException(directory
                    + " is writable by other users, so a library cached there cannot be trusted."
                    + " Remove it, or point REACTOR_FFI_LIB at a library you control.");
        }
    }
}
