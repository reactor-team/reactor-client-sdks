package inc.reactor.sdk;

/**
 * What this SDK reports about itself.
 *
 * <p>The version matters beyond diagnostics: it is what the client sends the coordinator as {@code
 * client_info.sdk_version}. The FFI defaults that field to reactor-core's own workspace version — a
 * Rust-internal number the coordinator has no reason to see — so every binding has to pass its own
 * published version explicitly, and this is where the Java one comes from.
 */
public final class ReactorSdk {

    /**
     * Where the version falls back to when there is no jar to read it from — a test run, an IDE, a
     * {@code build/classes} directory. Never reported to the coordinator from a released artifact,
     * because a released artifact always has the manifest.
     */
    private static final String DEVELOPMENT_VERSION = "0.0.0-dev";

    private ReactorSdk() {}

    /**
     * This SDK's published version, as the artifact declares it.
     *
     * @return the jar manifest's {@code Implementation-Version}, or {@code 0.0.0-dev} when running
     *     outside a packaged jar
     */
    public static String version() {
        String declared = ReactorSdk.class.getPackage().getImplementationVersion();
        return declared == null || declared.isBlank() ? DEVELOPMENT_VERSION : declared;
    }
}
