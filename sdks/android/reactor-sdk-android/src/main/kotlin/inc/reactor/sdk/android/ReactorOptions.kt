package inc.reactor.sdk.android

/**
 * How to reach the platform.
 *
 * An Android app should carry a **short-lived token**, not an API key: a key shipped in an APK is
 * a key anyone can extract. Mint the token on your own backend and pass it as [jwt]. [apiKey] is
 * for development, and the SDK says so rather than quietly accepting it.
 */
public data class ReactorOptions(
    /** The coordinator, e.g. `https://api.reactor.inc`. */
    public val apiUrl: String = "https://api.reactor.inc",
    /** A short-lived token minted by your backend. Preferred. */
    public val jwt: String? = null,
    /** Development only — see the note on this class. */
    public val apiKey: String? = null,
    /** Accept a development coordinator's self-signed certificate. */
    public val local: Boolean = false,
)
