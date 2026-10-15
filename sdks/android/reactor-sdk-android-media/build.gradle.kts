/*
 * Optional camera, microphone and Surface-renderer helpers, off the mandatory dependency path so
 * that importing the SDK opens no hardware. Empty until A11.
 */

plugins {
    id("reactor-android-conventions")
}

android {
    namespace = "inc.reactor.sdk.android.media"
}
