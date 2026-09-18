package inc.reactor.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** The code list, and the one rule about it that is not a lookup table. */
final class ErrorCodeTest {

    /**
     * The six the core calls recoverable. Written out rather than derived, because a test that
     * computes its expectation the same way the code does asserts nothing.
     */
    private static final Set<String> RECOVERABLE = Set.of(
            "DISCONNECTED", "NETWORK_ERROR", "REQUEST_TIMEOUT", "TRANSPORT_ERROR", "RATE_LIMITED", "SERVER_ERROR");

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    @DisplayName("recoverability matches the core's list, code for code")
    void recoverabilityMatchesTheCore(ErrorCode code) {
        assertEquals(
                RECOVERABLE.contains(code.code()),
                code.isRecoverable(),
                code.code() + " disagrees with reactor-core about whether retrying is worth anything");
    }

    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    @DisplayName("every code builds its own exception type, carrying that code")
    void everyCodeBuildsItsOwnType(ErrorCode code) {
        ReactorException thrown = ReactorException.of(code.code(), "something went wrong", null, "connect", null);

        assertEquals(code.code(), thrown.code());
        assertEquals(code.isRecoverable(), thrown.isRecoverable());
        assertFalse(
                thrown.getClass().equals(ReactorException.class),
                code.code() + " fell back to the base class instead of its own type");
    }

    @Test
    @DisplayName("a code the platform sent and this SDK does not know is carried, not rejected")
    void anUnknownCodeIsCarried() {
        // The platform's set is open-ended: a rejected command may carry anything. Treating that
        // as a parse failure would lose the one piece of information the caller was sent.
        ReactorException thrown = ReactorException.of("MODEL_SAID_NO", "the model refused", 422, "send_command", null);

        assertEquals("MODEL_SAID_NO", thrown.code());
        assertEquals(ReactorException.class, thrown.getClass());
        assertFalse(thrown.isRecoverable(), "an unknown code must not promise that a retry will help");
        assertEquals(422, thrown.status());
    }

    @Test
    @DisplayName("the typed subclasses are what a caller catches")
    void typedSubclassesAreCatchable() {
        assertInstanceOf(
                RateLimitedException.class, ReactorException.of("RATE_LIMITED", "slow down", 429, "connect", 1500L));
        assertInstanceOf(
                InvalidStateException.class, ReactorException.of("INVALID_STATE", "not connected", null, null, null));
        assertEquals(
                1500L,
                ReactorException.of("RATE_LIMITED", "slow down", 429, "connect", 1500L)
                        .retryAfterMs());
    }

    @Test
    @DisplayName("the base code has no typed subclass, and is not in the enum")
    void theBaseCodeIsNotAnEnumConstant() {
        assertTrue(ErrorCode.of(ReactorException.INTERNAL_ERROR).isEmpty());
        assertEquals(
                ReactorException.class,
                ReactorException.of(ReactorException.INTERNAL_ERROR, "something", null, null, null)
                        .getClass());
    }
}
