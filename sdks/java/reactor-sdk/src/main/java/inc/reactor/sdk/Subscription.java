package inc.reactor.sdk;

/**
 * A registered handler, and the way to remove it.
 *
 * <p>{@link AutoCloseable} so a handler can be scoped to a try-with-resources block, and {@link
 * #close()} is idempotent so it can also just be called.
 */
public interface Subscription extends AutoCloseable {

    /** Removes the handler. Calling this twice is not an error; the second call does nothing. */
    @Override
    void close();
}
