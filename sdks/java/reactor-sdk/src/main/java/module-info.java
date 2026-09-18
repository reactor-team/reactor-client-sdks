/**
 * The Reactor client SDK.
 *
 * <p>This module calls native code through the Foreign Function &amp; Memory API, so a consumer must
 * grant it native access: {@code --enable-native-access=inc.reactor.sdk} on the module path, or
 * {@code --enable-native-access=ALL-UNNAMED} on the classpath. Without it the JVM warns on every
 * restricted call, and a future release will refuse them outright.
 */
module inc.reactor.sdk {
    // transitive: @Nullable appears in this module's own public signatures, so a consumer
    // compiling against them has to be able to read it. static: the annotations are
    // class-retained and absent at run time, so nothing is required on the module path.
    requires static transitive org.jspecify;

    // The shared libraries live in their own artifact, and a consumer on the module path resolves
    // modules rather than artifacts: without this, an application declaring only
    // `requires inc.reactor.sdk` never pulls the natives into the graph, and the class-loader
    // lookup that finds the packaged library finds nothing. It worked on the class path, where
    // there is no graph to be outside of, which is why it went unnoticed.
    //
    // Not `requires static`: a static requires is not resolved at run time, which is precisely when
    // the library is needed.
    requires inc.reactor.sdk.natives;

    exports inc.reactor.sdk;
}
