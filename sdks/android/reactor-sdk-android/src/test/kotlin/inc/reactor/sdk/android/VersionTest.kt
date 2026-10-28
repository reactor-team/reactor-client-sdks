package inc.reactor.sdk.android

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The version the coordinator is told is the version Maven Central is given.
 *
 * Both come from gradle.properties. Before they did, the SDK reported a literal that nothing tied
 * to a release, and every session from a published AAR would have claimed to be a snapshot.
 */
class VersionTest {
    @Test
    fun `SDK_VERSION is the version this build publishes`() {
        val published = System.getProperty("reactor.publishedVersion")
        assertEquals(published, Reactor.SDK_VERSION)
    }
}
