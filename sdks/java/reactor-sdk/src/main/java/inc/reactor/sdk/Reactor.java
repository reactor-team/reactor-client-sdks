package inc.reactor.sdk;

import inc.reactor.sdk.internal.ClientPeer;
import java.lang.ref.Cleaner;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * A Reactor client.
 *
 * <p>Create one, connect, work, close:
 *
 * <pre>{@code
 * try (Reactor reactor = Reactor.open(ReactorOptions.builder("https://api.reactor.inc", "reactor/echo")
 *         .jwt(token)
 *         .build())) {
 *     reactor.onStatus(status -> System.out.println("status: " + status));
 *     reactor.connect().join();
 *     // ...
 * }
 * }</pre>
 *
 * <p><b>Close it.</b> A creator that goes away without disconnecting orphans the session, and the
 * next run cannot start until that clears. Try-with-resources is the reliable way; the {@link
 * Cleaner} registered here is a safety net for a client somebody forgot, not a plan.
 *
 * <p>Control events — status, error, message, track, capabilities, session id — are delivered
 * through the {@link Dispatcher}, so a Swing or JavaFX application can have them arrive on the
 * thread its toolkit requires. Media does not go through it: see {@link Dispatcher}.
 *
 * <p>Thread-safe. {@link #close()} is idempotent and may be called from an event handler.
 */
public final class Reactor implements AutoCloseable {

    private static final Cleaner CLEANER = Cleaner.create();

    private final ClientPeer peer;
    private final ReactorOptions options;
    private final Cleaner.Cleanable cleanable;

    private Reactor(ClientPeer peer, ReactorOptions options) {
        this.peer = peer;
        this.options = options;
        // The action is the peer itself, and the peer holds no reference back to this object. A
        // cleaning action that captured its own referent would keep it reachable forever, so the
        // cleaner would never run and the native handle would never be destroyed — the leak this
        // registration exists to prevent.
        this.cleanable = CLEANER.register(this, peer);
    }

    /**
     * Creates a client. Nothing is connected until {@link #connect()}.
     *
     * @param options what to connect to
     * @return the client
     */
    public static Reactor open(ReactorOptions options) {
        return new Reactor(ClientPeer.create(options), options);
    }

    /**
     * The peer underneath, for this SDK's own tests.
     *
     * <p>Package-private, and read by the test fixtures rather than by anything a consumer can
     * reach.
     *
     * @return the peer
     */
    inc.reactor.sdk.internal.ClientPeer peer() {
        return peer;
    }

    /**
     * Creates a client over a given library, for this SDK's own tests.
     *
     * <p>Package-private, and the only thing it changes is where the ABI comes from: everything
     * below it is the same code the public factory runs, which is the point of having a seam rather
     * than a test double for the client itself.
     */
    static Reactor open(
            ReactorOptions options,
            java.util.function.Function<java.lang.foreign.Arena, inc.reactor.sdk.internal.Ffi> loader) {
        return new Reactor(ClientPeer.create(options, loader), options);
    }

    /**
     * Exchanges an API key for a token.
     *
     * <p>Everything else here wants a JWT, and a caller holding a key needs this first. It takes no
     * client: the exchange is one request, and it is not bounded by any session's lifetime.
     *
     * <p>A token minted this way carries everything the key's roles allow. That is fine server to
     * server and wrong to hand to a client you do not control — scope it there.
     *
     * @param apiUrl the coordinator's base URL
     * @param apiKey the key to exchange
     * @return the token
     */
    public static CompletableFuture<String> fetchJwt(String apiUrl, String apiKey) {
        return fetchJwt(apiUrl, apiKey, null, false);
    }

    /**
     * Exchanges an API key for a scoped token.
     *
     * @param apiUrl the coordinator's base URL
     * @param apiKey the key to exchange
     * @param options scoping options, or {@code null} for everything the key allows. An
     *     unrecognised key in this object is an error rather than ignored: dropping a misspelt
     *     {@code models} in silence would mint exactly the unscoped token the caller was avoiding
     * @param local whether to accept a dev coordinator's certificate
     * @return the token
     */
    public static CompletableFuture<String> fetchJwt(
            String apiUrl, String apiKey, @Nullable JsonValue options, boolean local) {
        return inc.reactor.sdk.internal.Authentication.fetchJwt(
                inc.reactor.sdk.internal.NativeLibrary.shared(),
                apiUrl,
                apiKey,
                options == null ? null : options.toJsonString(),
                local);
    }

    /**
     * Creates a session and establishes the transport.
     *
     * @return settles when the transport is up
     */
    public CompletableFuture<Void> connect() {
        return peer.connect(null, null);
    }

    /**
     * Adopts an existing session rather than creating one.
     *
     * @param sessionId the session to join
     * @param connectionId a connection id a backend already registered for it, or {@code null} to
     *     register a new one — most callers pass {@code null}
     * @return settles when the transport is up
     */
    public CompletableFuture<Void> connect(String sessionId, @Nullable Integer connectionId) {
        return peer.connect(sessionId, connectionId);
    }

    /**
     * Leaves the session. Unlike {@link #reconnect()}, this ends it.
     *
     * @return settles when the session has been left
     */
    public CompletableFuture<Void> disconnect() {
        return peer.disconnect();
    }

    /**
     * Re-establishes the transport on the same session.
     *
     * <p>A reconnect resumes recvonly tracks and nothing else — anything published before one is
     * not published after it.
     *
     * @return settles when the transport is up again
     */
    public CompletableFuture<Void> reconnect() {
        return peer.reconnect();
    }

    /** @return where this client is between disconnected and ready */
    public ConnectionStatus status() {
        return peer.status();
    }

    /** @return the session id, or empty when there is no session */
    public Optional<String> sessionId() {
        return peer.sessionId();
    }

    /**
     * The tracks this session declared, in the order it declared them.
     *
     * @return the tracks; empty before the session is accepted and after it is torn down, so "no
     *     tracks yet" is distinguishable from a name that does not exist
     */
    public TrackList tracks() {
        return new TrackList(peer.trackDeclarations().stream()
                .map(declaration -> new Track(
                        peer, declaration.name(), declaration.kind(), declaration.direction(), declaration.mid()))
                .toList());
    }

    /**
     * One track, by the name the session declared it under.
     *
     * <p>This is how an application that knows its model asks. Unknown names raise rather than
     * answering with nothing: a handler registered on a track that does not exist never fires, and
     * a session that looks healthy and produces nothing is the failure this SDK exists to refuse.
     *
     * @param name the declared name
     * @return the track
     * @throws ReactorException when the session declared no track by that name, listing the names
     *     it did declare
     */
    public Track track(String name) {
        TrackList declared = tracks();
        return declared.byName(name)
                .orElseThrow(() -> ReactorException.of(
                        ErrorCode.NOT_FOUND.code(),
                        "this session declares no track named \"" + name + "\". It declares: "
                                + declared.stream().map(Track::name).toList() + ".",
                        null,
                        "track",
                        null));
    }

    /**
     * @param handler called with every status change
     * @return a subscription that removes it
     */
    public Subscription onStatus(Consumer<ConnectionStatus> handler) {
        return peer.onStatus(handler);
    }

    /**
     * @param handler called with every error the session reports, carrying the same exception type
     *     a failed call would throw
     * @return a subscription that removes it
     */
    public Subscription onError(Consumer<ReactorException> handler) {
        return peer.onError(handler);
    }

    /**
     * @param handler called with every application message the model sends
     * @return a subscription that removes it
     */
    public Subscription onMessage(Consumer<JsonValue> handler) {
        return peer.onMessage(handler);
    }

    /**
     * @param handler called with every runtime (platform) message
     * @return a subscription that removes it
     */
    public Subscription onRuntimeMessage(Consumer<JsonValue> handler) {
        return peer.onRuntimeMessage(handler);
    }

    /**
     * @param handler called with the session's capabilities
     * @return a subscription that removes it
     */
    public Subscription onCapabilities(Consumer<JsonValue> handler) {
        return peer.onCapabilities(handler);
    }

    /**
     * @param handler called when the session id is assigned, and again with empty when it clears
     * @return a subscription that removes it
     */
    public Subscription onSessionId(Consumer<Optional<String>> handler) {
        return peer.onSessionId(handler);
    }

    /**
     * Sends a command and waits for the model's reply.
     *
     * @param name the command, as the model's schema declares it
     * @param args its arguments
     * @return the reply, empty when the model acknowledged without producing a message
     */
    public CompletableFuture<java.util.Optional<CommandReply>> sendCommand(String name, JsonValue args) {
        return peer.sendCommand(name, args.toJsonString(), null);
    }

    /**
     * Sends a command that takes no arguments.
     *
     * @param name the command
     * @return the reply, empty when the model acknowledged without producing a message
     */
    public CompletableFuture<java.util.Optional<CommandReply>> sendCommand(String name) {
        return peer.sendCommand(name, null, null);
    }

    /**
     * Sends a command with files the platform is already holding.
     *
     * @param name the command
     * @param args its arguments
     * @param uploads the files, keyed by the parameter each one fills
     * @return the reply, empty when the model acknowledged without producing a message
     */
    public CompletableFuture<java.util.Optional<CommandReply>> sendCommand(
            String name, JsonValue args, java.util.Map<String, FileRef> uploads) {
        if (uploads.isEmpty()) {
            return sendCommand(name, args);
        }
        JsonValue.ObjectBuilder named = JsonValue.object();
        uploads.forEach((parameter, ref) -> named.put(parameter, ref.toJsonValue()));
        return peer.sendCommand(name, args.toJsonString(), named.build().toJsonString());
    }

    /**
     * Uploads a file for a later command.
     *
     * <p>The path crosses the boundary rather than the bytes, so the file's size is the platform's
     * business and not the heap's.
     *
     * @param path the file
     * @return the reference to pass into a command
     */
    public CompletableFuture<FileRef> uploadFile(java.nio.file.Path path) {
        return peer.uploadFile(path);
    }

    /**
     * Uploads bytes already in memory.
     *
     * @param data the bytes
     * @param name what to call it
     * @param mimeType what it is
     * @return the reference to pass into a command
     */
    public CompletableFuture<FileRef> uploadBytes(byte[] data, String name, String mimeType) {
        return peer.uploadBytes(data, name, mimeType);
    }

    /**
     * Asks the model what commands it accepts and what they take.
     *
     * @return the schema, as the model declares it
     */
    public CompletableFuture<JsonValue> requestSchema() {
        return peer.requestSchema();
    }

    /**
     * Reads the connection.
     *
     * @return what the platform reported
     */
    public CompletableFuture<Stats> getStats() {
        return peer.getStats();
    }

    /**
     * Asks for a clip of the last {@code durationSeconds} of this session.
     *
     * <p>The clip is not ready when this settles. See {@link Clip} for why waiting on a wall clock
     * is the wrong instinct.
     *
     * @param durationSeconds how far back the window reaches
     * @return the clip
     */
    public CompletableFuture<Clip> requestClip(double durationSeconds) {
        return peer.requestClip(durationSeconds);
    }

    /**
     * Starts recording the whole session.
     *
     * @return the recording
     */
    public CompletableFuture<Clip> requestRecording() {
        return peer.requestRecording();
    }

    /**
     * Downloads a clip into one playable file, waiting for it to become ready.
     *
     * <p><b>This outlives the client.</b> Closing the client settles this future with an error
     * saying so, but does not cancel the download — the file may still arrive.
     *
     * @param clip what to download
     * @param outPath the file to write
     * @param progress told how many segments have been written, or {@code null}
     * @return the assembled file
     */
    public CompletableFuture<DownloadedClip> downloadClip(
            Clip clip, java.nio.file.Path outPath, @Nullable ClipProgress progress) {
        // Negative: wait as long as the session lives. A model generating slower than real time
        // reaches the boundary chunk later than any fixed number would allow for, and once the
        // session is gone a "not ready" is a "not ready" forever.
        return downloadClip(clip, outPath, -1, progress);
    }

    /**
     * Downloads a clip, giving up if it is not ready within a bound.
     *
     * @param clip what to download
     * @param outPath the file to write
     * @param readyTimeoutSeconds how long to wait past the clip's own prediction — measured from
     *     there, not from now. Negative or infinite waits as long as the session lives
     * @param progress told how many segments have been written, or {@code null}
     * @return the assembled file
     */
    public CompletableFuture<DownloadedClip> downloadClip(
            Clip clip, java.nio.file.Path outPath, double readyTimeoutSeconds, @Nullable ClipProgress progress) {
        return peer.downloadClip(
                clip,
                options.jwt(),
                outPath,
                readyTimeoutSeconds,
                options.local(),
                progress == null ? null : progress::report);
    }

    /**
     * The engine's own clock.
     *
     * <p>Capture times handed to {@link Track#pushFrame(byte[], int, int, byte[], Long)} are
     * compared against this, not against {@link System#currentTimeMillis()}.
     *
     * @return microseconds on the engine clock
     */
    public long timeMicros() {
        return peer.timeMicros();
    }

    /**
     * Bounds the connection's bitrate.
     *
     * @param minBps lower bound, or a negative value for none
     * @param startBps where to start, or a negative value for none
     * @param maxBps upper bound, or a negative value for none
     * @return settles when the bounds are applied
     */
    public CompletableFuture<Void> setBitrate(int minBps, int startBps, int maxBps) {
        return peer.setBitrate(minBps, startBps, maxBps);
    }

    /**
     * Destroys the client.
     *
     * <p>Idempotent, safe from an event handler, and it settles every operation still waiting —
     * leaving one out would leave its caller waiting for the life of the process.
     */
    @Override
    public void close() {
        cleanable.clean();
    }

    /** @return whether this client has been closed */
    public boolean isClosed() {
        return peer.isClosed();
    }
}
