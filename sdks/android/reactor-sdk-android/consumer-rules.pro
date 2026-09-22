# Kept for every consumer that minifies.
#
# R8 has no way to see that these are reached from native code: the JNI bridge resolves them by
# their mangled, fully-qualified names at load time, so to R8 they are unused and its default is
# to remove or rename them. Either produces an UnsatisfiedLinkError in a release build and never
# in a debug one, which is the worst shape a bug of this kind can take.
-keepclasseswithmembernames,includedescriptorclasses class inc.reactor.sdk.android.internal.** {
    native <methods>;
}
-keep class inc.reactor.sdk.android.internal.NativeAbi { *; }

# WebRTC's own Java half, looked up from native code by name for the same reason.
-keep class inc.reactor.org.webrtc.** { *; }
-keep class inc.reactor.org.jni_zero.** { *; }
