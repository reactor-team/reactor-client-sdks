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
    private final Cleaner.Cleanable cleanable;

    private Reactor(ClientPeer peer) {
        this.peer = peer;
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
        return new Reactor(ClientPeer.create(options));
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
     * @param handler called with every application message the model sends, as JSON
     * @return a subscription that removes it
     */
    public Subscription onMessage(Consumer<String> handler) {
        return peer.onMessage(handler);
    }

    /**
     * @param handler called with every runtime (platform) message, as JSON
     * @return a subscription that removes it
     */
    public Subscription onRuntimeMessage(Consumer<String> handler) {
        return peer.onRuntimeMessage(handler);
    }

    /**
     * @param handler called with the session's capabilities, as JSON
     * @return a subscription that removes it
     */
    public Subscription onCapabilities(Consumer<String> handler) {
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
