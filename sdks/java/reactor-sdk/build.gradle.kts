plugins {
    id("reactor-java-conventions")
    id("reactor-maven-central")
    // The fake library lives here, and the Kotlin facade's tests need the same one: a second copy
    // over there would be a second thing to keep in step with the FFI.
    `java-test-fixtures`
}

description =
    "Reactor client SDK for desktop JVM applications, bound to libreactor_ffi " +
        "through the Foreign Function & Memory API."

dependencies {
    // The fixtures are ordinary sources of this module and carry the same nullness annotations.
    // Declared here rather than in the conventions: only this module has a testFixtures source set,
    // and the configuration does not exist anywhere else.
    testFixturesCompileOnly("org.jspecify:jspecify:1.0.1")
}

// ── Zero configuration, and what it costs ────────────────────────────────────
//
// One dependency line, and the library for whatever machine the code ends up on is already there.
// It is an ordinary runtime dependency on a jar carrying all five platforms, roughly 50 MB of it,
// because that is the only shape both build tools understand without the consumer opting into
// anything.
//
// The design it replaced looked cheaper and did not work. This module published five extra
// variants keyed on operating system and architecture, on the theory that Gradle would match the
// host's. A plain JVM project requests neither attribute, so Gradle chose runtimeElements and no
// native artifact reached the classpath at all — proven against a clean consumer project, which
// opened a client and then failed at load with "the natives artifact for macos-arm64 is not on the
// classpath". Setting those attributes in the consumer made it worse: the extra variants compete
// with runtimeElements rather than adding to it, so resolution became ambiguous. There is no fix
// on this side — a consumer would have to apply a plugin to express the attributes — and a
// zero-configuration promise that needs a plugin is not one.
//
// A build that wants exactly one platform, a container image that knows what it runs on, excludes
// this and names its own classifier. See the README.
dependencies {
    // Not runtimeOnly: module-info names this module, so javac needs it on the module path to
    // compile the descriptor at all.
    implementation(project(":reactor-sdk-natives"))
}

// Test fixtures are for this repository's tests, not for a consumer's. Left in, the published
// module metadata carries two more variants and attaches a jar holding a fake FFI — this
// repository's internal test double, on a Maven Central coordinate.
//
// Lost once already: the block below was written with the fixtures themselves and then dropped by
// the rewrite that replaced this module's native-platform variants, which had nothing to do with
// it. A reviewer caught it before it shipped.
val javaComponent = components["java"] as AdhocComponentWithVariants

listOf("testFixturesApiElements", "testFixturesRuntimeElements").forEach { name ->
    javaComponent.withVariantsFromConfiguration(configurations[name]) { skip() }
}

publishing {
    publications {
        create<MavenPublication>("sdk") {
            // Carries the jar, both companions and every native variant. Not `artifact(javadocJar)`
            // beside it: the conventions' withJavadocJar() already puts that jar in this component,
            // and naming it twice makes the publication invalid — "multiple artifacts with the
            // identical extension and classifier" — at publish time rather than at build time.
            from(components["java"])
            pom { standardPom("Reactor SDK", project.description ?: "") }
        }
    }
}
