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
 * method of the same name taking one more parameter — the continuation — so ordinary reflection
 * sees both surfaces without kotlin-reflect on the test path.
 *
 * Counted by name **and parameter count**, not by name alone. Comparing names let two overloads go
 * missing while the check stayed green: the facade had `sendCommand(name, args)` but not the
 * three-argument one that attaches uploads, and only the unscoped `fetchJwt`, so a Kotlin caller
 * could neither send a file to a model nor mint a least-privilege token without dropping to
 * [ReactorClient.java]. Both were reported by a reviewer rather than by this test, which is what
 * this test exists to prevent.
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
    fun `an overload of a covered name is not itself covered`() {
        // The specific hole the name-only version had: sendCommand appeared covered because one of
        // its overloads was. Against a facade that has the two-argument form and nothing else, the
        // three-argument one has to be reported.
        assertEquals(
            listOf("sendCommand/3"),
            missingFrom(Reactor::class.java, TwoArgumentFacadeOnly::class.java).filter {
                it.startsWith("sendCommand")
            },
        )
    }

    /** A stand-in carrying exactly one sendCommand overload, so the check can be checked. */
    @Suppress("unused")
    private class TwoArgumentFacadeOnly {
        fun sendCommand(name: String, args: Any?, continuation: Any?): Any? = null
    }

    @Test
    fun `the check would notice an omission`() {
        // Against a facade that covers nothing, every future-returning method must be reported —
        // otherwise the two passing tests above prove only that the reflection found nothing.
        val reported = missingFrom(Reactor::class.java, Object::class.java)
        assertEquals(
            listOf(
                "connect/0",
                "connect/2",
                "disconnect/0",
                "downloadClip/3",
                "downloadClip/4",
                "fetchJwt/2",
                "fetchJwt/4",
                "getStats/0",
                "reconnect/0",
                "requestClip/1",
                "requestRecording/0",
                "requestSchema/0",
                "sendCommand/1",
                "sendCommand/2",
                "sendCommand/3",
                "setBitrate/3",
                "uploadBytes/3",
                "uploadFile/1",
            ),
            reported,
        )
    }

    private fun missingFrom(java: Class<*>, facade: Class<*>): List<String> {
        // The companion object too: a static on the Java side has its twin there, and Kotlin
        // compiles that into a nested class rather than onto the facade itself.
        //
        // A suspend function takes the continuation as one extra parameter, and a facade method may
        // carry defaults the Java side does not — so the match is "some overload of this name takes
        // at least as many parameters as the Java one", rather than an exact count. Loose enough to
        // survive a default argument, tight enough that a two-argument twin no longer answers for a
        // three-argument original.
        val covered =
            (facade.declaredMethods.asSequence() +
                    facade.declaredClasses.asSequence().flatMap { it.declaredMethods.asSequence() })
                .groupBy({ it.name }, { it.parameterCount })
                .mapValues { (_, counts) -> counts.max() }
        return java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) }
            .filter { CompletableFuture::class.java.isAssignableFrom(it.returnType) }
            .map { it.name to it.parameterCount }
            .distinct()
            .filterNot { (name, parameters) -> (covered[name] ?: -1) >= parameters + 1 }
            .map { (name, parameters) -> "$name/$parameters" }
            .sorted()
    }
}
