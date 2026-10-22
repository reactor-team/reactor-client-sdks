/*
 * The instrumented suite that drives the real client against a live reactor/echo session.
 *
 * Nothing here is faked. The unit suite proves the binding behaves against a library it can make
 * misbehave on purpose; this proves it behaves against the platform, which is the only place the
 * whole path exists — the coordinator, the codecs the fleet negotiates, the model contracts as
 * deployed rather than as declared in a manifest.
 *
 * It is a library module whose tests live in androidTest, so they run on a device against the
 * same merged native libraries a consumer's APK gets. What it does not prove on its own is AAR
 * packaging — that is what the examples app covers, by assembling an APK and carrying the
 * arm64-v8a shared objects into it.
 */

plugins {
    id("reactor-android-conventions")
}

// A value the tests need on the device, from a Gradle property or the environment.
//
// Instrumented tests run in a process on the device, which has neither this build's environment
// nor its properties — so anything they need has to be handed to the runner explicitly. The empty
// default is deliberate: a missing key must reach Live, which decides whether that is a skip or a
// failure. Defaulting to something plausible here would hide the decision.
fun runnerArg(
    gradleProperty: String,
    environmentVariable: String,
): String =
    (project.findProperty(gradleProperty) as String?)
        ?: System.getenv(environmentVariable)
        ?: ""

android {
    namespace = "inc.reactor.sdk.android.integration"

    defaultConfig {
        // Its own runner, so the shared session is torn down once when the run ends rather than
        // per class. See LiveRunner.
        testInstrumentationRunner = "inc.reactor.sdk.android.integration.LiveRunner"

        testInstrumentationRunnerArguments +=
            mapOf(
                // Passed to `am instrument -e`, because the device has no environment of ours. CI
                // masks the secret in logs, and Gradle does not echo the command at
                // --console=plain.
                "reactorApiKey" to runnerArg("reactorApiKey", "INTEGRATION_TESTS_REACTOR_API_KEY"),
                "reactorApiUrl" to runnerArg("reactorApiUrl", "REACTOR_API_URL"),
                "reactorModel" to runnerArg("reactorModel", "INTEGRATION_TESTS_REACTOR_MODEL"),
                // Whether a missing key is a skip or a failure — the device cannot see $CI either.
                "ci" to runnerArg("ci", "CI"),
            )
    }
}

dependencies {
    androidTestImplementation(project(":reactor-sdk-android"))
}
