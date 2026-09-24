/*
 * The long-running leak and resource-trend suite. Gates nothing; runs from workflow_dispatch and
 * after a real release.
 *
 * Split across two source sets on purpose:
 *
 *   main       Sample, MetricResult, Trends, Report — the reading and rendering, which import
 *              nothing from the SDK.
 *   test       host-JVM tests of exactly that, so the trend rules and the report format are
 *              checked on every CI run without a device, a key or a live session.
 *   androidTest the scenarios and the harness, which need all three.
 *
 * That split is what makes any of this verifiable. A trend rule is only wrong on a shape you did
 * not happen to run, and the scenarios take minutes on hardware to produce one shape.
 */

plugins {
    id("reactor-android-conventions")
}

fun runnerArg(
    gradleProperty: String,
    environmentVariable: String,
): String =
    (project.findProperty(gradleProperty) as String?)
        ?: System.getenv(environmentVariable)
        ?: ""

android {
    namespace = "inc.reactor.sdk.android.endurance"

    defaultConfig {
        // The device has no environment of ours; everything the run needs crosses as an
        // instrumentation argument. See the integration-tests module for the same reasoning.
        testInstrumentationRunnerArguments +=
            mapOf(
                "reactorApiKey" to
                    runnerArg("reactorApiKey", "ENDURANCE_TESTS_REACTOR_API_KEY")
                        .ifEmpty { runnerArg("reactorApiKey", "INTEGRATION_TESTS_REACTOR_API_KEY") },
                "reactorApiUrl" to runnerArg("reactorApiUrl", "REACTOR_API_URL"),
                "reactorModel" to runnerArg("reactorModel", "ENDURANCE_TESTS_REACTOR_MODEL"),
                "enduranceDurationMinutes" to
                    runnerArg("enduranceDurationMinutes", "ENDURANCE_DURATION_MINUTES").ifEmpty { "5" },
                "enduranceVerbose" to runnerArg("enduranceVerbose", "ENDURANCE_VERBOSE"),
                "commitSha" to runnerArg("commitSha", "GITHUB_SHA").ifEmpty { "(local)" },
            )

        // Minutes to hours per scenario. The default is 30 minutes, which a five-minute run does
        // not need and a two-hour one is killed by.
        testInstrumentationRunnerArguments["timeout_msec"] =
            (
                (
                    runnerArg("enduranceDurationMinutes", "ENDURANCE_DURATION_MINUTES")
                        .ifEmpty { "5" }
                        .toDouble() + 15
                ) * 60_000
            ).toLong().toString()
    }
}

dependencies {
    // The scenarios reach into `internal` for the live-client and orphaned-global counts, which is
    // the whole point of this suite and is not something a consumer-facing API should expose.
    androidTestImplementation(project(":reactor-sdk-android"))
}
