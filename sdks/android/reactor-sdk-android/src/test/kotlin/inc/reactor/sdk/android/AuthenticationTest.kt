package inc.reactor.sdk.android

import inc.reactor.sdk.android.internal.Authentication
import inc.reactor.sdk.android.internal.JsonException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The API-key exchange's decision and decoding.
 *
 * [Authentication] is pure for exactly this reason: which connects mint, and with what scope, is
 * the behaviour the other SDKs already got right, and the exchange itself needs a live
 * coordinator. One is testable here; the other belongs on a device.
 */
class AuthenticationTest {
    private val newSession = Authentication.TokenScope(modelName = "reactor/echo", sessionId = null)
    private val adoptee = Authentication.TokenScope(modelName = "reactor/echo", sessionId = "ses_123")

    @Test
    fun `a caller-supplied token always wins`() {
        // With both present the key must stay unused: the caller's token is theirs, not this
        // client's to replace.
        assertNull(
            Authentication.planMint(
                apiKey = "rk_dev",
                callerJwt = "theirs",
                local = false,
                currentToken = "theirs",
                mintedFor = null,
                wanted = newSession,
            ),
        )
    }

    @Test
    fun `local never mints`() {
        assertNull(
            Authentication.planMint(
                apiKey = "rk_dev",
                callerJwt = null,
                local = true,
                currentToken = null,
                mintedFor = null,
                wanted = newSession,
            ),
        )
    }

    @Test
    fun `without a key there is nothing to exchange`() {
        assertNull(
            Authentication.planMint(
                apiKey = null,
                callerJwt = null,
                local = false,
                currentToken = null,
                mintedFor = null,
                wanted = newSession,
            ),
        )
    }

    @Test
    fun `creating a session mints with model scope`() {
        assertEquals(
            """{"models":["reactor/echo"]}""",
            Authentication.planMint(
                apiKey = "rk_dev",
                callerJwt = null,
                local = false,
                currentToken = null,
                mintedFor = null,
                wanted = newSession,
            ),
        )
    }

    @Test
    fun `adopting a session mints bound to it`() {
        assertEquals(
            """{"sessions":["ses_123"]}""",
            Authentication.planMint(
                apiKey = "rk_dev",
                callerJwt = null,
                local = false,
                currentToken = null,
                mintedFor = null,
                wanted = adoptee,
            ),
        )
    }

    @Test
    fun `an unchanged scope does not mint again`() {
        assertNull(
            Authentication.planMint(
                apiKey = "rk_dev",
                callerJwt = null,
                local = false,
                currentToken = "minted",
                mintedFor = newSession,
                wanted = newSession,
            ),
        )
    }

    /**
     * The failure this whole decision exists against: a cached model-scoped token quietly handed
     * to a connect that adopts a session, which the platform can only answer with 403.
     */
    @Test
    fun `a changed scope mints again`() {
        assertEquals(
            """{"sessions":["ses_123"]}""",
            Authentication.planMint(
                apiKey = "rk_dev",
                callerJwt = null,
                local = false,
                currentToken = "minted",
                mintedFor = newSession,
                wanted = adoptee,
            ),
        )
    }

    @Test
    fun `a scope that differs in every part is a different scope`() {
        val otherModel = Authentication.TokenScope(modelName = "reactor/other", sessionId = null)
        assertEquals(
            """{"models":["reactor/other"]}""",
            Authentication.planMint(
                apiKey = "rk_dev",
                callerJwt = null,
                local = false,
                currentToken = "minted",
                mintedFor = newSession,
                wanted = otherModel,
            ),
        )
    }

    @Test
    fun `the scope JSON escapes what it quotes`() {
        val scope = Authentication.TokenScope(modelName = """reactor/qu"ote""", sessionId = null)
        assertEquals("""{"models":["reactor/qu\"ote"]}""", scope.optionsJson)
    }

    @Test
    fun `a scope is never nothing`() {
        assertThrows(IllegalArgumentException::class.java) {
            Authentication.TokenScope(modelName = null, sessionId = null)
        }
    }

    // ── The reply ────────────────────────────────────────────────────────────

    // ── Rebuilding the native client ─────────────────────────────────────────

    @Test
    fun `with no client there is nothing to rebuild`() {
        assertNull(Authentication.needsRebuild(handleModel = null, modelName = "reactor/echo", tokenChanged = true))
        assertNull(Authentication.needsRebuild(handleModel = null, modelName = "reactor/echo", tokenChanged = false))
    }

    @Test
    fun `the same model with the same token keeps the client`() {
        assertNull(Authentication.needsRebuild(handleModel = "reactor/echo", modelName = "reactor/echo", tokenChanged = false))
    }

    @Test
    fun `a re-minted token rebuilds the client`() {
        assertEquals(
            Authentication.Rebuild.TOKEN_CHANGED,
            Authentication.needsRebuild(handleModel = "reactor/echo", modelName = "reactor/echo", tokenChanged = true),
        )
    }

    /** The bug this was found by: model B connected on the client created for model A. */
    @Test
    fun `another model rebuilds the client even when the token is unchanged`() {
        assertEquals(
            Authentication.Rebuild.MODEL_CHANGED,
            Authentication.needsRebuild(handleModel = "reactor/echo", modelName = "reactor/helios", tokenChanged = false),
        )
    }

    @Test
    fun `another model with a re-minted token rebuilds once`() {
        assertTrue(
            Authentication.needsRebuild(handleModel = "reactor/echo", modelName = "reactor/helios", tokenChanged = true) != null,
        )
    }

    // ── Which token downloads a clip ────────────────────────────────────────

    @Test
    fun `a clip downloads with its own session's token, not the latest one`() {
        val tokens = mapOf("ses_a" to "token-a", "ses_b" to "token-b")
        assertEquals("token-a", Authentication.tokenForClip("ses_a", tokens, current = "token-b"))
    }

    @Test
    fun `a clip from an unknown or absent session falls back to the current token`() {
        val tokens = mapOf("ses_a" to "token-a")
        assertEquals("token-b", Authentication.tokenForClip("ses_other", tokens, current = "token-b"))
        assertEquals("token-b", Authentication.tokenForClip(null, tokens, current = "token-b"))
        assertNull(Authentication.tokenForClip(null, emptyMap(), current = null))
    }

    @Test
    fun `a token in an object is the answer`() {
        assertEquals("minted", Authentication.decodeJwt("""{"jwt":"minted"}"""))
    }

    /**
     * An "empty" token cannot be papered over: returning one would connect anonymously where the
     * platform was about to answer 401, reporting success for a call that failed.
     */
    @Test
    fun `an absent or blank payload throws rather than passing as an empty token`() {
        assertThrows(JsonException::class.java) { Authentication.decodeJwt(null) }
        assertThrows(JsonException::class.java) { Authentication.decodeJwt("") }
        assertThrows(JsonException::class.java) { Authentication.decodeJwt("   ") }
    }

    @Test
    fun `a payload that is not an object throws`() {
        assertThrows(JsonException::class.java) { Authentication.decodeJwt("""["jwt"]""") }
    }

    @Test
    fun `an object without a usable jwt throws`() {
        assertThrows(JsonException::class.java) { Authentication.decodeJwt("""{}""") }
        assertThrows(JsonException::class.java) { Authentication.decodeJwt("""{"jwt":""}""") }
        assertThrows(JsonException::class.java) { Authentication.decodeJwt("""{"jwt":42}""") }
    }

    @Test
    fun `a malformed payload throws`() {
        assertThrows(JsonException::class.java) { Authentication.decodeJwt("{not json") }
    }

    /** Extra fields ride along untouched, as the platform is free to add them. */
    @Test
    fun `a token with extra fields around it still decodes`() {
        assertEquals("minted", Authentication.decodeJwt("""{"jwt":"minted","expires_in":3600}"""))
    }

    @Test
    fun `a decode failure names the payload it could not use`() {
        val error = assertThrows(JsonException::class.java) { Authentication.decodeJwt("""{}""") }
        assertTrue("the raw payload is what a decode bug is diagnosed from", error.message!!.contains("{}"))
    }
}
