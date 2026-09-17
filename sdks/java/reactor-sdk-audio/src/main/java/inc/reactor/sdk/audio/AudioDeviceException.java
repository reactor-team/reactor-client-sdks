package inc.reactor.sdk.audio;

/**
 * Thrown when a device cannot be opened, is lost, or cannot carry the format the core needs.
 *
 * <p>Its own type rather than a {@code ReactorException}: nothing here is a failure of the Reactor
 * platform, and a caller catching {@code ReactorException} to handle a session problem should not
 * also catch "there is no microphone".
 */
public final class AudioDeviceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    AudioDeviceException(String message) {
        super(message);
    }

    AudioDeviceException(String message, Throwable cause) {
        super(message, cause);
    }
}
