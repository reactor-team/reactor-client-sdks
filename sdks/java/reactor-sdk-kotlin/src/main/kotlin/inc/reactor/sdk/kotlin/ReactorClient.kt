package inc.reactor.sdk.kotlin

import inc.reactor.sdk.Clip
import inc.reactor.sdk.ClipProgress
import inc.reactor.sdk.CommandReply
import inc.reactor.sdk.ConnectionStatus
import inc.reactor.sdk.DownloadedClip
import inc.reactor.sdk.FileRef
import inc.reactor.sdk.JsonValue
import inc.reactor.sdk.Reactor
import inc.reactor.sdk.ReactorOptions
import inc.reactor.sdk.Stats
import java.nio.file.Path
import kotlinx.coroutines.future.await

/**
 * The Reactor client, with `suspend` where the Java client returns a `CompletableFuture`.
 *
 * A facade over [Reactor] and nothing more. It holds no native symbol, opens no handle of its own
 * and adds no path to the FFI boundary — every method here forwards to the one below it, which is
 * what keeps the two from being two bindings that can disagree. The Java client is [java], for
 * anything this has not been given a Kotlin shape.
 *
 * ```kotlin
 * ReactorClient.open(reactorOptions(apiUrl, "reactor/echo") { jwt(token) }).use { client ->
 *     client.connect()
 *     client.track("output").frames().collect { render(it) }
 * }
 * ```
 *
 * ### Cancellation
 *
 * Cancelling a coroutine that is awaiting one of these cancels **the await, not the operation**.
 * The native call is already in flight and has no way to be recalled; what the FFI answers is then
 * dropped. A command whose caller walked away still reached the model.
 */
public class ReactorClient(
    /** The Java client underneath. Everything here forwards to it. */
    public val java: Reactor
) : AutoCloseable {

    public companion object {

        /**
         * Opens a client.
         *
         * @param options what to connect to
         * @return a client the caller closes
         */
        public fun open(options: ReactorOptions): ReactorClient =
            ReactorClient(Reactor.open(options))

        /**
         * Exchanges an API key for a session token.
         *
         * @param apiUrl the platform
         * @param apiKey the key
         * @return the token
         */
        public suspend fun fetchJwt(apiUrl: String, apiKey: String): String =
            Reactor.fetchJwt(apiUrl, apiKey).await()
    }

    /** Connects, and settles once the session is ready. */
    public suspend fun connect(): Unit = java.connect().await().let {}

    /**
     * Rejoins a session that already exists.
     *
     * @param sessionId the session to rejoin
     * @param connectionId which connection within it, or null for a new one
     */
    public suspend fun connect(sessionId: String, connectionId: Int? = null): Unit =
        java.connect(sessionId, connectionId).await().let {}

    /** Leaves the session without closing the client. */
    public suspend fun disconnect(): Unit = java.disconnect().await().let {}

    /** Reconnects to the same session. */
    public suspend fun reconnect(): Unit = java.reconnect().await().let {}

    /** Where the connection currently is. */
    public val status: ConnectionStatus
        get() = java.status()

    /** The session this client is in, or null before one exists. */
    public val sessionId: String?
        get() = java.sessionId().orElse(null)

    /** Every track the session declared, in the order it declared them. */
    public val tracks: List<ReactorTrack>
        get() = java.tracks().map(::ReactorTrack)

    /**
     * One track by name.
     *
     * @param name as the session declared it
     * @return the track
     * @throws inc.reactor.sdk.ReactorException when the session declared no such name
     */
    public fun track(name: String): ReactorTrack = ReactorTrack(java.track(name))

    // ── Commands, messages and uploads ──────────────────────────────────────

    /**
     * Sends a command to the model.
     *
     * @param name the command
     * @param args its arguments, or null for a command that takes none
     * @return the model's reply, or null when it answered without one
     */
    public suspend fun sendCommand(name: String, args: JsonValue? = null): CommandReply? =
        (if (args == null) java.sendCommand(name) else java.sendCommand(name, args))
            .await()
            .orElse(null)

    /**
     * Uploads a file for a command to refer to.
     *
     * @param path the file
     * @return a reference a command argument can carry
     */
    public suspend fun uploadFile(path: Path): FileRef = java.uploadFile(path).await()

    /**
     * Uploads bytes already in memory.
     *
     * @param data the bytes
     * @param name what to call them
     * @param mimeType what they are
     * @return a reference a command argument can carry
     */
    public suspend fun uploadBytes(data: ByteArray, name: String, mimeType: String): FileRef =
        java.uploadBytes(data, name, mimeType).await()

    /** The schema of the commands this model accepts. */
    public suspend fun requestSchema(): JsonValue = java.requestSchema().await()

    /** What the platform reports about this session. */
    public suspend fun getStats(): Stats = java.getStats().await()

    // ── Recording ───────────────────────────────────────────────────────────

    /**
     * Asks for a clip of what has been generated.
     *
     * @param durationSeconds how far back to take
     * @return the clip, once the platform has it
     */
    public suspend fun requestClip(durationSeconds: Double): Clip =
        java.requestClip(durationSeconds).await()

    /** Asks for the whole session so far. */
    public suspend fun requestRecording(): Clip = java.requestRecording().await()

    /**
     * Downloads a clip.
     *
     * A download outlives the client that started it, as the FFI documents. Closing this client
     * while one is in flight settles the caller rather than leaving it waiting, and says the file
     * may still arrive.
     *
     * @param clip what to download
     * @param destination where to write it
     * @param onProgress told how many segments have been written, or null
     * @param readyTimeoutSeconds how long to wait past the clip's own prediction, measured from
     *   there rather than from now. Negative waits as long as the session lives, which is the
     *   default: a model generating slower than real time reaches its last chunk later than any
     *   fixed number would allow for, and once the session is gone a "not ready" is permanent
     * @return where it landed, and how large it was
     */
    public suspend fun downloadClip(
        clip: Clip,
        destination: Path,
        onProgress: ClipProgress? = null,
        readyTimeoutSeconds: Double = -1.0,
    ): DownloadedClip =
        java.downloadClip(clip, destination, readyTimeoutSeconds, onProgress).await()

    /** How far into the generated timeline this session is. */
    public val timeMicros: Long
        get() = java.timeMicros()

    /**
     * Asks the platform for a different bitrate.
     *
     * @param minBps the floor
     * @param startBps where to start
     * @param maxBps the ceiling
     */
    public suspend fun setBitrate(minBps: Int, startBps: Int, maxBps: Int): Unit =
        java.setBitrate(minBps, startBps, maxBps).await().let {}

    /** Whether this client has been closed. */
    public val isClosed: Boolean
        get() = java.isClosed()

    override fun close(): Unit = java.close()

    override fun toString(): String = "ReactorClient(${java})"
}
