package inc.reactor.sdk.internal;

/**
 * Thrown at load when the native library is not the one this SDK was built against.
 *
 * <p>Its own exception type because of how the alternative fails: the exported surface is
 * hand-copied in several places and checked by name, so a library older than the crates links,
 * resolves and then misbehaves at the call. That is not a version error to whoever sees it — it is
 * a hang, or an operation that silently does nothing. Refusing at load is the only place the truth
 * is still cheap to say.
 */
public final class AbiMismatchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    AbiMismatchException(String message) {
        super(message);
    }

    AbiMismatchException(String message, Throwable cause) {
        super(message, cause);
    }
}
