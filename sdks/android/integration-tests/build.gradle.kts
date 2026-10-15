/*
 * The instrumented suite that drives the real client against a live reactor/echo session.
 * Empty until A13, which also makes it a required job in ci-complete.
 */

plugins {
    id("reactor-android-conventions")
}

android {
    namespace = "inc.reactor.sdk.android.integration"
}
