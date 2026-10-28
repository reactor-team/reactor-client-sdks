package inc.reactor.sdk.android

/**
 * How to reach the platform.
 *
 * An Android app should carry a **short-lived token**, not an API key: a key shipped in an APK is
 * a key anyone can extract. Mint the token on your own backend and pass it as [jwt].
 *
 * [apiKey] is for development. There the SDK does the exchange for you, at `connect()`: the key
 * becomes a token scoped to what that connect needs — the model when creating a session, the
 * session when adopting one — and re-mints when the scope changes. The key still sits in the
 * APK for anyone to extract; none of that is a reason to ship one. A [jwt] passed alongside wins,
 * and is never exchanged, and [local] never authenticates.
 */
public data class ReactorOptions(
    /** The coordinator, e.g. `https://api.reactor.inc`. */
    public val apiUrl: String = "https://api.reactor.inc",
    /** A short-lived token minted by your backend. Preferred — when set, [apiKey] is never used. */
    public val jwt: String? = null,
    /** Development only — exchanged for a scoped token at connect; see the note on this class. */
    public val apiKey: String? = null,
    /** Accept a development coordinator's self-signed certificate. Does not authenticate. */
    public val local: Boolean = false,
)
