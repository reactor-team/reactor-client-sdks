plugins {
    id("reactor-java-conventions")
}

description =
    "Long-running leak and resource-trend scenarios. Not published, manual-only: these read a " +
        "trend over minutes to hours rather than a right-or-wrong answer."

dependencies {
    testImplementation(project(":reactor-sdk"))
    // The native library comes from the natives artifact, the same way a consumer's would.
    testRuntimeOnly(project(":reactor-sdk-natives"))
}

// Manual-only, and long. Nothing here belongs in a task a contributor runs to check their change
// compiles, and a trend over minutes is not a right-or-wrong answer a pull request can gate on.
val enduranceTest =
    tasks.register<Test>("enduranceTest") {
        group = "verification"
        description = "Long-running leak and resource-trend scenarios"
        testClassesDirs = sourceSets["test"].output.classesDirs
        classpath = sourceSets["test"].runtimeClasspath
        useJUnitPlatform()
        // Never up-to-date: the thing being measured is time, which Gradle cannot hash.
        outputs.upToDateWhen { false }
        jvmArgs(
            "--enable-native-access=ALL-UNNAMED",
            // A fixed heap, so RSS measures native growth rather than the GC moving under it.
            // Without this the loudest thing in the trend is garbage collection.
            "-Xms512m",
            "-Xmx512m",
            // A second series that is unambiguously native, for the same reason.
            "-XX:NativeMemoryTracking=summary",
        )
        testLogging {
            showStandardStreams = true
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            events("passed", "failed")
        }
        listOf(
                        "ENDURANCE_TESTS_REACTOR_API_KEY",
                        "INTEGRATION_TESTS_REACTOR_API_KEY",
                        "ENDURANCE_TESTS_REACTOR_MODEL",
                        "ENDURANCE_DURATION_MINUTES",
                        "ENDURANCE_VERBOSE",
                        "REACTOR_API_URL",
                        "GITHUB_SHA",
                )
                .forEach { name -> System.getenv(name)?.let { environment(name, it) } }
    }

tasks.named("test") {
    // This module's tests are not unit tests, and `test` is what every other module's unit run
    // resolves to. Disabled rather than left to loop for the run's full duration on a laptop.
    //
    // Nothing is wired from here to `enduranceTest`, and nothing may be. A `dependsOn` on a
    // provider derived from it — even one mapping to an empty list, which adds no dependency of
    // its own — still leaves Gradle tracking the task the provider came from, which is what put
    // the live suite into every `gradle test` in this build until REA-6446 caught it in CI. Same
    // line, same mistake, and this one would have spent the endurance duration rather than a
    // session.
    enabled = false
}
