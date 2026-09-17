plugins {
    id("reactor-java-conventions")
}

description =
    "The live suite: the real client against a real model. Not published, and " +
        "not part of `check` — it needs a session, so it cannot gate a unit-test run."

dependencies {
    testImplementation(project(":reactor-sdk"))
    // The native library it loads comes from the natives artifact, the same way a consumer's
    // would — not from a path this build happens to know.
    testRuntimeOnly(project(":reactor-sdk-natives"))
}

// A task of its own, and not part of `check`. `mise run test:java` must stay runnable without
// credentials and without spending a live session — this one needs both.
val integrationTest =
    tasks.register<Test>("integrationTest") {
        group = "verification"
        description = "The live suite, against a real model"
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        useJUnitPlatform()
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        // Never up-to-date: the platform is not an input Gradle can hash, so a second run has to
        // actually reach it rather than replay the first one's verdict.
        outputs.upToDateWhen { false }
        testLogging {
            showStandardStreams = true
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            events("passed", "failed", "skipped")
        }
        listOf("INTEGRATION_TESTS_REACTOR_API_KEY", "INTEGRATION_TESTS_REACTOR_MODEL", "REACTOR_API_URL", "CI")
                .forEach { name -> System.getenv(name)?.let { environment(name, it) } }
    }

tasks.named("test") {
    // This module's tests are not unit tests, and `test` is what every other module's unit run
    // resolves to. Disabled rather than left to reach the platform from a laptop.
    //
    // Nothing is wired from here to `integrationTest`, and nothing may be. A `dependsOn` on a
    // provider derived from it — even one mapping to an empty list, which adds no dependency of
    // its own — still leaves Gradle tracking the task the provider came from. `mise run test:java`
    // therefore pulled the live suite into its graph and ran it: green on a laptop, where a
    // missing key is a skip, and a hard failure in CI's unit job, which has no key and no reason
    // to be given one.
    enabled = false
}
