package inc.reactor.sdk.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Binding the ABI, and refusing to when the library is not the one this SDK was built against. */
final class FfiTest {

    @Test
    @DisplayName("every declared symbol binds against a library that has them all")
    void bindsEverySymbol() {
        try (FakeNativeLibrary fake = new FakeNativeLibrary()) {
            Ffi ffi = Ffi.open(fake.lookup());
            for (Ffi.Symbol symbol : Ffi.Symbol.values()) {
                assertNotNull(ffi.handle(symbol), symbol.cName() + " did not bind");
            }
        }
    }

    @Test
    @DisplayName("a library reporting another ABI version is refused at load, naming both numbers")
    void refusesAnAbiMismatch() {
        try (FakeNativeLibrary fake = new FakeNativeLibrary()) {
            fake.setAbiVersion(Ffi.ABI_VERSION + 1);

            AbiMismatchException thrown = assertThrows(AbiMismatchException.class, () -> Ffi.open(fake.lookup()));

            // The numbers matter more than the wording: whoever reads this has a library and a
            // build, and needs to know which is which.
            assertTrue(
                    thrown.getMessage().contains(String.valueOf(Ffi.ABI_VERSION)),
                    "the message must name the version this SDK speaks: " + thrown.getMessage());
            assertTrue(
                    thrown.getMessage().contains(String.valueOf(Ffi.ABI_VERSION + 1)),
                    "the message must name the version the library speaks: " + thrown.getMessage());
            assertTrue(
                    thrown.getMessage().contains("cargo build -p reactor-ffi --release"),
                    "the message must say how to fix it: " + thrown.getMessage());
        }
    }

    @Test
    @DisplayName("a missing symbol is refused at load, naming the symbol")
    void refusesAMissingSymbol() {
        try (FakeNativeLibrary fake = new FakeNativeLibrary()) {
            fake.removeSymbol("reactor_send_command");

            AbiMismatchException thrown = assertThrows(AbiMismatchException.class, () -> Ffi.open(fake.lookup()));

            assertTrue(
                    thrown.getMessage().contains("reactor_send_command"),
                    "the message must name the missing symbol: " + thrown.getMessage());
        }
    }

    @Test
    @DisplayName("the environment-driven create is not declared — the synthetic ADM is structural")
    void doesNotDeclareTheEnvironmentDrivenCreate() {
        // reactor_create takes its audio device mode from an environment variable, and under the
        // platform module a model declaring a sendonly audio track is enough to put a live
        // microphone on the wire. The binding uses reactor_create_with_adm instead, and the absence
        // of the other one is the guarantee. scripts/check-abi-parity.py enforces this too; this
        // test is here so the rule fails at the same moment the code breaks it.
        String declared =
                Arrays.stream(Ffi.Symbol.values()).map(Ffi.Symbol::cName).collect(Collectors.joining(","));
        assertTrue(declared.contains("reactor_create_with_adm"), "the ADM-pinning create must be declared");
        // Built rather than written out. scripts/check-abi-parity.py scans these sources for
        // reactor_* identifiers and forbids this one outright, so a test asserting its absence
        // must not spell it — the guard cannot tell a literal in an assertion from a call.
        String forbidden = "reactor_" + "create";
        assertEquals(-1, indexOfExact(declared, forbidden), forbidden + " must not be declared — use the ADM form");
    }

    /** Finds {@code reactor_create} as a whole entry rather than as a prefix of another name. */
    private static int indexOfExact(String csv, String name) {
        String[] entries = csv.split(",");
        for (int i = 0; i < entries.length; i++) {
            if (entries[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }
}
