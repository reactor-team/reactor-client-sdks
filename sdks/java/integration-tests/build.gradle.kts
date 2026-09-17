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
    enabled = false
    dependsOn(integrationTest.map { emptyList<Any>() })
}
