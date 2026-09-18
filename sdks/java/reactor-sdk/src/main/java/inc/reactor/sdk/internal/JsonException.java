package inc.reactor.sdk.internal;

/** Thrown when text that had to be JSON was not. Carries the offset, because a shape is easier to fix than a feeling. */
final class JsonException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int offset;

    JsonException(String message, int offset) {
        super(message + " (at offset " + offset + ")");
        this.offset = offset;
    }

    int offset() {
        return offset;
    }
}
