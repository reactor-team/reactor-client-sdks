package inc.reactor.sdk.kotlin

import inc.reactor.sdk.FakeClients
import inc.reactor.sdk.ReactorOptions
import inc.reactor.sdk.internal.FakeNativeLibrary
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach

/**
 * A facade over a client over a fake library.
 *
 * Events run on the calling thread, so a test can fire one and assert on the next line.
 */
abstract class FacadeFixture {

    protected lateinit var fake: FakeNativeLibrary
    protected lateinit var client: ReactorClient

    @BeforeEach
    fun openClient() {
        fake = FakeNativeLibrary()
        fake.tracksJson = TRACKS
        client =
            ReactorClient(
                FakeClients.open(
                    fake,
                    ReactorOptions.builder("https://api.example.test", "owner/model")
                        .dispatcher { it.run() }
                        .build(),
                )
            )
    }

    @AfterEach
    fun closeClient() {
        client.close()
        fake.close()
    }

    companion object {
        const val TRACKS: String =
            """[{"name":"camera_in","kind":"video","direction":"sendonly"},""" +
                """{"name":"screen_out","kind":"video","direction":"recvonly"},""" +
                """{"name":"mic_in","kind":"audio","direction":"sendonly"}]"""
    }
}
