package inc.reactor.sdk.android.internal

/**
 * Exchanging an API key for a token, and deciding whether a connect needs to.
 *
 * Mirrors the Java SDK's `internal/Authentication` and the token half of its `ClientPeer`, which
 * is the behaviour REA-7260 requires this SDK to match: a caller-supplied token always wins,
 * `local` never authenticates, and every other connect exchanges the key for a token scoped to
 * what **that** connect is about to reach — one minted for creating a session cannot adopt one,
 * and vice versa.
 *
 * Everything here is pure. The exchange itself is a native call ([NativeClient.fetchJwt]); the
 * decision of whether and what to mint is testable without one, which is exactly why it lives
 * here rather than inline in the connection.
 */
internal object Authentication {
    /**
     * What one `connect()` needs its token to reach.
     *
     * A bare model name mints `{"models":[…]}`, which can only *create* sessions there; a session
     * id mints `{"sessions":[…]}`, bound to the session being adopted. Both null would be an
     * unscoped mint — everything the key's roles allow — which is fine server to server and wrong
     * to hand to a client nobody controls, so this type refuses to represent it.
     */
    internal data class TokenScope(
        val modelName: String?,
        val sessionId: String?,
    ) {
        init {
            require(modelName != null || sessionId != null) {
                "A token scope is a model to create under or a session to adopt — it cannot be nothing"
            }
        }

        /**
         * The scope as the exchange's options JSON.
         *
         * `max_sessions` is deliberately absent: the server's own default is what every other
         * binding gets, and with no budget a bound token cannot create sessions.
         */
        val optionsJson: String
            get() =
                if (sessionId != null) {
                    """{"sessions":[${Json.quote(sessionId)}]}"""
                } else {
                    """{"models":[${Json.quote(modelName!!)}]}"""
                }
    }

    /**
     * The options of the mint a connect needs, or null when it needs none.
     *
     * No mint when there is nothing to exchange (no key), nothing to exchange for (a transport
     * that does not authenticate), or nothing to replace — a caller-supplied token is theirs, not
     * this client's to swap out. And no mint when the client already holds a token minted for
     * exactly this scope: minting again would only rotate a value that already reaches the target.
     *
     * Caching regardless of scope is the bug this decision exists against: it quietly hands a
     * model-scoped token to a connect that adopts a session, or a token bound to one session to a
     * connect that adopts another.
     */
    internal fun planMint(
        apiKey: String?,
        callerJwt: String?,
        local: Boolean,
        currentToken: String?,
        mintedFor: TokenScope?,
        wanted: TokenScope,
    ): String? {
        if (apiKey == null || callerJwt != null || local) return null
        if (currentToken != null && mintedFor == wanted) return null
        return wanted.optionsJson
    }

    /** Why a connect has to replace its native client. */
    internal enum class Rebuild(
        val reason: String,
    ) {
        TOKEN_CHANGED(
            "The token was re-minted for a different scope, which rebuilt the client. " +
                "Native work already started may still be running.",
        ),
        MODEL_CHANGED(
            "The client was rebuilt to connect to a different model. " +
                "Native work already started may still be running.",
        ),
    }

    /**
     * Whether a connect to [modelName] must replace the native client, and why; null to keep it.
     *
     * The native client receives its token and its model at creation and neither changes after,
     * so either one changing means a new client. [handleModel] is the model of the client there
     * is now, null when there is none — and with nothing to replace, nothing is rebuilt: the
     * connect simply creates one.
     *
     * The model is checked on its own because it holds even when the token text is unchanged:
     * without it a connect to model B went out on the client created for model A.
     */
    internal fun needsRebuild(
        handleModel: String?,
        modelName: String,
        tokenChanged: Boolean,
    ): Rebuild? =
        when {
            handleModel == null -> null
            tokenChanged -> Rebuild.TOKEN_CHANGED
            handleModel != modelName -> Rebuild.MODEL_CHANGED
            else -> null
        }

    /**
     * The token to download a clip with: the one its session was connected with, when known.
     *
     * Not simply the latest token. A connect that adopts another session re-mints a token bound
     * to that session, and it cannot fetch a still-valid clip of the one before. A clip with no
     * session, or one from a session this client never reached, falls back to [current].
     */
    internal fun tokenForClip(
        clipSessionId: String?,
        sessionTokens: Map<String, String>,
        current: String?,
    ): String? = clipSessionId?.let { sessionTokens[it] } ?: current

    /**
     * The key exchange's reply, as the token.
     *
     * Runs before the promise is claimed, so throwing here is how the caller's DECODE_FAILED is
     * made — see Completions. An exchange that "succeeded" without carrying a token is not an
     * empty answer to paper over: a binding that returned one would mint nothing and connect
     * anonymously, reporting success where the platform was about to answer 401.
     */
    internal fun decodeJwt(resultJson: String?): String {
        if (resultJson.isNullOrBlank()) {
            throw JsonException("the coordinator answered the key exchange with nothing")
        }
        val parsed = Json.parse(resultJson)

        @Suppress("UNCHECKED_CAST")
        val fields =
            parsed as? Map<String, Any?>
                ?: throw JsonException(
                    "the key exchange answered with something that is not an object: $resultJson",
                )
        val jwt = fields["jwt"] as? String
        if (jwt.isNullOrBlank()) {
            throw JsonException("the key exchange answered without a \"jwt\": $resultJson")
        }
        return jwt
    }
}
