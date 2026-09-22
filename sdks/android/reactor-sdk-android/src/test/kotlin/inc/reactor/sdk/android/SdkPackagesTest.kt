package inc.reactor.sdk.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The invariants of `sdks/android/sdk-packages.txt`.
 *
 * That file is the single source of truth for the SDK components mise cannot pin, and
 * `scripts/setup-android-sdk.sh` enforces it against a real SDK root. This suite covers the half
 * of that which needs no SDK and no network, so a malformed pin fails on a laptop and in every
 * CI job rather than only on a machine that runs the installer.
 *
 * Worth stating why the unversioned-spec rule is a test rather than a convention: `android sdk
 * install platform-tools` succeeds and installs whatever is newest that day, and `android sdk
 * install` exits 0 even when it ignores a spec it cannot resolve. Neither failure is visible in
 * the build that causes it.
 */
class SdkPackagesTest {
    private val specs: List<String> =
        File(
            requireNotNull(System.getProperty("reactor.sdkPackagesFile")) {
                "reactor.sdkPackagesFile is unset — see reactor-android-conventions.gradle.kts"
            },
        ).readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }

    @Test
    fun `pins at least the platform, build-tools and ndk`() {
        assertTrue("sdk-packages.txt pins nothing", specs.isNotEmpty())
        for (kind in listOf("platforms/", "build-tools/", "ndk/")) {
            assertTrue(
                "sdk-packages.txt pins no $kind package",
                specs.any { it.startsWith(kind) },
            )
        }
    }

    @Test
    fun `every spec carries a version`() {
        for (spec in specs) {
            assertTrue(
                "'$spec' pins no version — use a versioned path (build-tools/36.1.0) " +
                    "or name@version (platform-tools@37.0.1)",
                spec.contains('/') || spec.contains('@'),
            )
        }
    }

    @Test
    fun `no component is pinned twice`() {
        val kinds = specs.map { it.substringBefore('@').substringBeforeLast('/') }
        val duplicates =
            kinds
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        assertEquals(
            "sdk-packages.txt pins more than one version of: $duplicates",
            emptySet<String>(),
            duplicates,
        )
    }

    @Test
    fun `the pinned platform matches compileSdk`() {
        // compileSdk lives in reactor-android-conventions.gradle.kts. If one moves without the
        // other, the build compiles against a platform the SDK root does not have.
        val platform = specs.single { it.startsWith("platforms/") }
        assertEquals("platforms/android-36", platform)
    }
}
