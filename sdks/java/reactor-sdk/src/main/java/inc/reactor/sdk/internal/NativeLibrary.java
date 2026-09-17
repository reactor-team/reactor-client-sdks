package inc.reactor.sdk.internal;

import inc.reactor.sdk.ReactorSdk;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
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

    private static @org.jspecify.annotations.Nullable Ffi shared;

    private NativeLibrary() {}

    /**
     * The library, loaded once for the whole process.
     *
     * <p>{@link Arena#global()}, and never a client's. `reactor_destroy` ends a session; it does
     * not stop the core's shared Tokio runtime, whose worker threads go on running, and a detached
     * download is documented as outliving the handle it was given. A client arena that owned the
     * library would unload it on close and pull the code out from under both — a jump into an
     * unmapped page, with nothing in Java to catch and no stack to read afterwards.
     *
     * <p>There is deliberately no way to unload it. There is no moment at which doing so is known
     * to be safe.
     *
     * @return the bound ABI, its version already checked
     */
    public static synchronized Ffi shared() {
        if (shared == null) {
            shared = load(Arena.global());
        }
        return shared;
    }

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
            createPrivateDirectory(userCacheRoot(), cached.getParent());
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

    /**
     * A directory this user owns.
     *
     * <p>No fallback into the shared temporary directory. A per-user *name* there —
     * {@code /tmp/reactor-sdk-<user>} — reads like ownership and is not: any account can create
     * that path first, and this class would then load whatever library it found inside. A name is
     * not a claim. If none of these exist there is nowhere safe to cache, and saying so beats
     * inventing somewhere.
     */
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
        throw new NativeLibraryNotFoundException("no per-user cache directory exists (tried LOCALAPPDATA,"
                + " XDG_CACHE_HOME and the home directory), so there is nowhere this SDK can safely unpack a"
                + " native library. Set " + OVERRIDE_ENV + " to a library you control.");
    }

    /**
     * Creates the cache path such that only this user can write anywhere along it.
     *
     * <p>Every component from {@code root} down is created private, or — if it is already there —
     * checked. Checking only the ones this call happens to create is worth nothing: a private leaf
     * under a directory somebody else can write to is a leaf they can replace wholesale, and the
     * first version of this walked straight past an existing parent for exactly that reason.
     *
     * <p>Above {@code root} it stops. Those are the user's own home and the system's, and refusing
     * them would mean refusing every machine.
     *
     * @param root where this user's own space begins
     * @param directory the leaf to end up with
     */
    private static void createPrivateDirectory(Path root, Path directory) throws IOException {
        Path relative = root.relativize(directory);
        Path at = root;
        if (!Files.isDirectory(root)) {
            createOnePrivateDirectory(root);
        } else {
            refuseIfNotOurs(root);
        }
        for (Path component : relative) {
            at = at.resolve(component);
            if (Files.isDirectory(at)) {
                refuseIfNotOurs(at);
            } else {
                createOnePrivateDirectory(at);
            }
        }
    }

    private static void createOnePrivateDirectory(Path directory) throws IOException {
        Path parent = directory.getParent();
        if (parent != null && !Files.isDirectory(parent)) {
            createOnePrivateDirectory(parent);
        }
        try {
            // At creation, not afterwards: a chmod later leaves a window in which the directory is
            // open, and that window is all an attacker needs.
            Files.createDirectory(
                    directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException notPosix) {
            // Windows, where the per-user root is the protection.
            Files.createDirectory(directory);
        } catch (java.nio.file.FileAlreadyExistsException raced) {
            // Another JVM of ours got there first. Still checked.
            refuseIfNotOurs(directory);
        }
    }

    /**
     * Refuses a directory this user does not own, or that anyone else can write to.
     *
     * <p>Both halves matter and neither implies the other. A directory owned by somebody else with
     * ordinary 0755 permissions passes a permissions check — nobody but its owner can write to it —
     * and its owner is exactly the person who should not be choosing what this JVM loads.
     */
    private static void refuseIfNotOurs(Path directory) throws IOException {
        UserPrincipal owner;
        Set<PosixFilePermission> permissions;
        try {
            owner = Files.getOwner(directory);
            permissions = Files.getPosixFilePermissions(directory);
        } catch (UnsupportedOperationException notPosix) {
            return;
        }
        String us = System.getProperty("user.name");
        if (us != null && !owner.getName().equals(us)) {
            throw new IOException(directory + " is owned by " + owner.getName()
                    + ", not by this user, so a library cached there cannot be trusted. Remove it, or point "
                    + OVERRIDE_ENV + " at a library you control.");
        }
        if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
            throw new IOException(directory
                    + " is writable by other users, so a library cached there cannot be trusted."
                    + " Remove it, or point " + OVERRIDE_ENV + " at a library you control.");
        }
    }
}
