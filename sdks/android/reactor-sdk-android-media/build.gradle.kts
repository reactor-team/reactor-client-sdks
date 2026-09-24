/*
 * Optional camera, microphone and renderer helpers.
 *
 * A **separate artifact**, and that is the point rather than an organisational detail: importing
 * `reactor-sdk-android` must open no hardware and request no permissions. A model declaring a
 * sendonly audio track is not the application asking for a microphone, and an SDK that took a
 * media dependency on every consumer would make that distinction impossible to hold.
 *
 * Nothing here is required to use the SDK. It depends on the core; the core does not depend on it.
 */

plugins {
    id("reactor-android-conventions")
}

android {
    namespace = "inc.reactor.sdk.android.media"
}

dependencies {
    api(project(":reactor-sdk-android"))
}
