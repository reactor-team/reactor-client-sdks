package inc.reactor.sdk.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import inc.reactor.sdk.ReactorException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Decoding an error must never fail.
 *
 * <p>This runs while the SDK is already handling a failure. Anything thrown from here replaces the
 * error the caller needs to see with one about the decoding, which is the least useful moment
 * possible to lose information.
 */
final class ErrorPayloadsTest {

    @Test
    @DisplayName("a status too large for an int is no status, not an exception")
    void anOutOfRangeStatusDoesNotReplaceTheError() {
        // Math.toIntExact threw here. The caller lost "the model rejected the command" and got an
        // ArithmeticException about a number instead.
        ReactorException parsed = ErrorPayloads.parse(
                "{\"code\":\"invalid_command\",\"message\":\"the model rejected it\",\"status\":9999999999}",
                "send_command");

        assertEquals("invalid_command", parsed.code());
        assertEquals("the model rejected it", parsed.getMessage());
        assertNull(parsed.status());
    }

    @Test
    @DisplayName("a status that fits still comes through")
    void anOrdinaryStatusIsKept() {
        ReactorException parsed =
                ErrorPayloads.parse("{\"code\":\"not_found\",\"message\":\"no such model\",\"status\":404}", "connect");

        assertEquals(404, parsed.status());
    }
}
