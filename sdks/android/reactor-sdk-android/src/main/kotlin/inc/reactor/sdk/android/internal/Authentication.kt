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
                    """{"sessions":[${quote(sessionId)}]}"""
                } else {
                    """{"models":[${quote(modelName!!)}]}"""
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

    /**
     * One JSON string literal.
     *
     * Hand-rolled for the same reason [Json] is: the model name and session id are the only
     * strings this binding ever *writes*, and neither justifies a serialization dependency on
     * every consumer's classpath. A name or id with a quote in it is not something the platform
     * accepts, but a malformed request here is a coordinator's 400 — escaping it is a client one.
     */
    private fun quote(value: String): String =
        buildString(value.length + 2) {
            append('"')
            for (c in value) {
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c < ' ' -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
            }
            append('"')
        }
}
