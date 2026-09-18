package inc.reactor.sdk;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.foreign.Linker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scaffold's own contract, which every later slice depends on and none of them restates: this
 * build produces bytecode the published floor can load, and the JVM running these tests may call
 * the restricted methods the SDK is made of.
 */
final class ToolchainTest {

    @Test
    @DisplayName("the runtime meets the published floor of Java 22")
    void runtimeMeetsThePublishedFloor() {
        int feature = Runtime.version().feature();
        assertTrue(feature >= 22, "the Foreign Function & Memory API is final from Java 22; this JVM is " + feature);
    }

    @Test
    @DisplayName("native access is granted, so a restricted call neither warns nor throws")
    void nativeAccessIsGranted() {
        // Linker.nativeLinker() is itself a restricted method. On a JVM without
        // --enable-native-access this prints a warning; with
        // --illegal-native-access=deny, which the build passes from JDK 24 on, it
        // throws instead. Either way this test is the thing that notices.
        assertNotNull(Linker.nativeLinker(), "the platform has no native linker");
    }
}
