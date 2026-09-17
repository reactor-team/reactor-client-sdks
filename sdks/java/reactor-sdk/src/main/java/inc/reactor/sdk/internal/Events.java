package inc.reactor.sdk.internal;

import inc.reactor.sdk.Subscription;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Handlers for one kind of event, and the rule that one of them cannot silence the others.
 *
 * <p>Host code has bugs. A handler that throws must not stop the handlers registered after it —
 * otherwise one caller's typo makes every other listener for that event stop working, which is a
 * failure nobody attributes correctly. So each handler is called inside its own try/catch, and the
 * loop runs over a snapshot so a handler that unsubscribes during delivery does not disturb it.
 *
 * <p>Reported once per distinct message: a handler that throws on every event throws at the rate
 * the events arrive, and a log that repeats at that rate hides the first occurrence rather than
 * highlighting it.
 */
final class Events<T> {

    private static final System.Logger LOG = System.getLogger(Events.class.getName());
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private final String name;
    private final List<Consumer<T>> handlers = new CopyOnWriteArrayList<>();

    Events(String name) {
        this.name = name;
    }

    Subscription add(Consumer<T> handler) {
        handlers.add(handler);
        return new Subscription() {
            private volatile boolean removed;

            @Override
            public void close() {
                if (!removed) {
                    removed = true;
                    handlers.remove(handler);
                }
            }
        };
    }

    void clear() {
        handlers.clear();
    }

    int size() {
        return handlers.size();
    }

    void emit(T event) {
        for (Consumer<T> handler : handlers) {
            try {
                handler.accept(event);
            } catch (Throwable thrown) {
                report(thrown);
            }
        }
    }

    private void report(Throwable thrown) {
        String message = name + " handler threw " + thrown.getClass().getName() + ": " + thrown.getMessage();
        if (REPORTED.add(message)) {
            LOG.log(System.Logger.Level.ERROR, message + " (further occurrences are not reported)", thrown);
        }
    }
}
