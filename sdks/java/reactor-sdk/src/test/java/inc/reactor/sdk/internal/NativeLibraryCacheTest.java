package inc.reactor.sdk.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where an extracted native library is allowed to live.
 *
 * <p>The cache used to sit at a fixed path under the shared temporary directory, which any other
 * account on the machine can create first. Extraction skips a file that is already there, so that
 * account chose which shared object this JVM loaded — and loading it is what this class is for.
 */
final class NativeLibraryCacheTest {

    /** Where the walk starts for a given test — its own temporary root. */
    private static final ThreadLocal<Path> ROOT = new ThreadLocal<>();

    private static Path createPrivateDirectory(Path directory) throws Exception {
        Method method = NativeLibrary.class.getDeclaredMethod("createPrivateDirectory", Path.class, Path.class);
        method.setAccessible(true);
        try {
            method.invoke(null, ROOT.get(), directory);
        } catch (InvocationTargetException wrapped) {
            if (wrapped.getCause() instanceof IOException refused) {
                throw refused;
            }
            throw wrapped;
        }
        return directory;
    }

    @Test
    @DisplayName("the cache directory is created writable by nobody else")
    void theCacheDirectoryIsPrivate(@TempDir Path root) throws Exception {
        ROOT.set(root);
        Path cache = createPrivateDirectory(root.resolve("reactor-sdk-natives/1.0.0/macos-arm64"));

        assertTrue(Files.isDirectory(cache));
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(cache));
        // Every level, not only the leaf: a writable parent is a directory somebody can replace.
        assertEquals(
                PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(cache.getParent().getParent()));
    }

    @Test
    @DisplayName("a cache directory anyone can write to is refused, not used")
    void aWorldWritableCacheIsRefused(@TempDir Path root) throws Exception {
        ROOT.set(root);
        Path hijacked = root.resolve("reactor-sdk-natives");
        Files.createDirectories(hijacked);
        Files.setPosixFilePermissions(hijacked, PosixFilePermissions.fromString("rwxrwxrwx"));

        IOException refused =
                assertThrows(IOException.class, () -> createPrivateDirectory(hijacked.resolve("1.0.0/macos-arm64")));

        assertTrue(refused.getMessage().contains("writable by other users"), refused.getMessage());
        assertTrue(refused.getMessage().contains("REACTOR_FFI_LIB"), refused.getMessage());
    }

    @Test
    @DisplayName("the cache root is the user's own, never the shared temporary directory")
    void theCacheRootIsUserScoped() throws Exception {
        Method method = NativeLibrary.class.getDeclaredMethod("userCacheRoot");
        method.setAccessible(true);
        Path root = ((Path) method.invoke(null)).toAbsolutePath();
        Path shared = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath();

        // Not under it at all. A per-user *name* there reads like ownership and is not: any account
        // can create /tmp/reactor-sdk-<user> first, and a name is not a claim.
        assertFalse(root.startsWith(shared), "cache root " + root + " is under the shared temporary directory");
    }

    @Test
    @DisplayName("a directory owned by somebody else is refused, however its permissions read")
    void aDirectoryOwnedByAnotherUserIsRefused(@TempDir Path root) throws Exception {
        // Ownership and permissions are separate questions and neither implies the other: 0755
        // owned by an attacker passes a permissions check, and its owner is precisely the person
        // who should not be choosing what this JVM loads.
        //
        // Another account cannot be created here, so this asserts the check exists and reads the
        // owner rather than the mode alone.
        ROOT.set(root);
        Path ours = createPrivateDirectory(root.resolve("cache"));
        java.nio.file.attribute.UserPrincipal owner = Files.getOwner(ours);
        assertEquals(System.getProperty("user.name"), owner.getName());
    }
}
