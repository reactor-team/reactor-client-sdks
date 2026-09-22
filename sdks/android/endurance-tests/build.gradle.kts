/*
 * The long-running leak and resource-trend suite. Empty until A14; it gates nothing, and runs
 * from workflow_dispatch and after a real release.
 */

plugins {
    id("reactor-android-conventions")
}

android {
    namespace = "inc.reactor.sdk.android.endurance"
}
