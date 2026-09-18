package inc.reactor.sdk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class ReactorSdkTest {

    @Test
    @DisplayName("version() always answers something, jar or no jar")
    void versionIsNeverNullOrBlank() {
        String version = ReactorSdk.version();
        assertNotNull(version, "version() must not return null — it is sent to the coordinator");
        assertFalse(version.isBlank(), "version() must not return blank");
    }
}
