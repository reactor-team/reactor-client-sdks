/**
 * The Reactor client SDK.
 *
 * <p>This module calls native code through the Foreign Function &amp; Memory API, so a consumer must
 * grant it native access: {@code --enable-native-access=inc.reactor.sdk} on the module path, or
 * {@code --enable-native-access=ALL-UNNAMED} on the classpath. Without it the JVM warns on every
 * restricted call, and a future release will refuse them outright.
 */
module inc.reactor.sdk {
    requires static org.jspecify;

    exports inc.reactor.sdk;
}
