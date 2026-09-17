package inc.reactor.sdk;

import java.util.concurrent.Executor;

/**
 * Where control events are delivered.
 *
 * <p>They arrive on the FFI's own threads, which are not threads a host's concurrency primitives
 * expect to be touched from. Every control event — status, error, message, track, capabilities,
 * session id — is handed to this instead.
 *
 * <p>Media is not. {@code Track} frame handlers run inline on the FFI's delivery thread, on
 * purpose: blocking there is the backpressure, and the FFI keeps only the newest video frame while
 * a handler runs. Putting frames through a dispatcher would trade a bounded drop for unbounded
 * latency and memory.
 *
 * <p>A desktop application usually wants its own toolkit thread:
 *
 * <pre>{@code
 * // Swing
 * ReactorOptions.builder(...).dispatcher(SwingUtilities::invokeLater)
 *
 * // JavaFX
 * ReactorOptions.builder(...).dispatcher(Platform::runLater)
 * }</pre>
 *
 * <p>The default is a single background thread the client owns and shuts down when it closes.
 */
@FunctionalInterface
public interface Dispatcher extends Executor {

    /**
     * Runs one event handler.
     *
     * <p>Never throws on behalf of the handler: whatever it throws is caught before it reaches
     * here.
     *
     * @param event the handler call
     */
    @Override
    void execute(Runnable event);
}
