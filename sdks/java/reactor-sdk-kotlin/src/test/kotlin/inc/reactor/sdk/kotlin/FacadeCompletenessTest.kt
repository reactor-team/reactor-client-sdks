package inc.reactor.sdk.kotlin

import inc.reactor.sdk.Reactor
import inc.reactor.sdk.Track
import java.lang.reflect.Modifier
import java.util.concurrent.CompletableFuture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The facade has to keep up with the binding, and nothing else here would notice if it did not.
 *
 * A method added to the Java client is invisible from Kotlin until someone remembers to give it a
 * `suspend` twin — and the symptom is not a failure, it is a Kotlin user reaching through
 * [ReactorClient.java] and wondering why. This reads both surfaces and compares them, so the
 * omission fails a build rather than waiting to be noticed.
 *
 * Only the future-returning methods. Those are the ones that have a different shape on this side; a
 * plain getter reads the same either way and is not what drifts. A `suspend` function compiles to a
 * method of the same name taking one more parameter, so ordinary reflection sees both surfaces
 * without kotlin-reflect on the test path.
 */
class FacadeCompletenessTest {

    @Test
    fun `every future-returning method on the client has a suspend twin`() {
        assertEquals(
            emptyList<String>(),
            missingFrom(Reactor::class.java, ReactorClient::class.java),
        )
    }

    @Test
    fun `every future-returning method on a track has a suspend twin`() {
        assertEquals(emptyList<String>(), missingFrom(Track::class.java, ReactorTrack::class.java))
    }

    @Test
    fun `the check would notice an omission`() {
        // Against a facade that covers nothing, every future-returning method must be reported —
        // otherwise the two passing tests above prove only that the reflection found nothing.
        val reported = missingFrom(Reactor::class.java, Object::class.java)
        assertEquals(
            listOf(
                "connect",
                "disconnect",
                "downloadClip",
                "fetchJwt",
                "getStats",
                "reconnect",
                "requestClip",
                "requestRecording",
                "requestSchema",
                "sendCommand",
                "setBitrate",
                "uploadBytes",
                "uploadFile",
            ),
            reported,
        )
    }

    private fun missingFrom(java: Class<*>, facade: Class<*>): List<String> {
        // The companion object too: a static on the Java side has its twin there, and Kotlin
        // compiles that into a nested class rather than onto the facade itself.
        val covered =
            (facade.declaredMethods.asSequence() +
                    facade.declaredClasses.asSequence().flatMap { it.declaredMethods.asSequence() })
                .map { it.name }
                .toSet()
        return java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) }
            .filter { CompletableFuture::class.java.isAssignableFrom(it.returnType) }
            .map { it.name }
            .distinct()
            .filterNot { it in covered }
            .sorted()
    }
}
