/*
 * The AAR: the binding, the object model, the per-ABI native libraries and the relocated WebRTC
 * classes. Empty for now — A02 adds the native pipeline and A03 the JNI bridge.
 */

plugins {
    id("reactor-android-conventions")
}

android {
    namespace = "inc.reactor.sdk.android"
}
