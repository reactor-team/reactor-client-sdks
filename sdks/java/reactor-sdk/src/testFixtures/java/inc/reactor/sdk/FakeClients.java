package inc.reactor.sdk;

import inc.reactor.sdk.internal.FakeNativeLibrary;
import inc.reactor.sdk.internal.Ffi;

/**
 * A client over a fake library, for tests in other modules.
 *
 * <p>The facade module has the same thing to prove this one does — that a refusal is refused, that
 * a subscription is dropped when its collector goes away — and both need a real client to prove it
 * against. Re-implementing the fake there would give it a second thing to keep in step with the
 * FFI, which is exactly what the facade exists not to be.
 *
 * <p>Published as a test-fixtures variant rather than an ordinary one, and excluded from the
 * release: it is for this repository's own tests, not for a consumer's.
 */
public final class FakeClients {

    private FakeClients() {}

    /**
     * Opens a client over a fake library.
     *
     * @param fake the library it binds to
     * @param options what it would connect to
     * @return the client, which the caller closes
     */
    public static Reactor open(FakeNativeLibrary fake, ReactorOptions options) {
        return Reactor.open(options, arena -> Ffi.open(fake.lookup()));
    }

    /**
     * How many control-event handlers a client is holding.
     *
     * <p>The only way to prove a handler was removed: a subscription nobody dropped goes on being
     * called, and from outside, still-registered and gone look identical until an event arrives
     * with nobody left to want it.
     *
     * @param client the client to ask
     * @return the count, across every control event
     */
    public static int controlHandlerCount(Reactor client) {
        return client.peer().controlHandlerCount();
    }
}
